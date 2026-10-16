package rta

import com.sun.net.httpserver.{HttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress
import java.awt.Desktop
import java.net.URI
import java.io.{File, PrintWriter}
import scala.io.Source
import rta.syntax.{Parser2, PdlParser, Program2, RTATranslator}
import rta.syntax.Program2.{RxGraph, QName}
import rta.backend.{RxSemantics, PdlEvaluator, PrismConverter2, AnalyseLTS, TrainingEngine}

object RTACLI {

  def main(args: Array[String]): Unit = {
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    if (args.isEmpty || args(0) == "-server") {
      runServerMode()
    } else {
      runCliMode(args)
    }
  }

  private def applyHistory(graph: RxGraph, history: String): RxGraph = {
    var cur = graph
    for (raw <- history.split(',') if raw.trim.nonEmpty) {
      val step = raw.trim
      val candidates = RxSemantics.nextEdge(cur).toList
        .filter { case (e, _) => e._4.show == step || s"${e._4.show}(${e._3.show})" == step }
        .sortBy(t => Program2.showEdge(t._1))
      candidates match {
        case Nil => throw new RuntimeException(s"Step '$step' is not enabled in state ${cur.inits.map(_.show).mkString(",")}.")
        case (_, next) :: rest =>
          if (rest.nonEmpty) System.err.println(s"[WARN] Step '$step' is ambiguous (${candidates.map(c => s"${c._1._4.show}(${c._1._3.show}) -> ${c._1._2.show}").mkString(", ")}); taking the first.")
          cur = next
      }
    }
    cur
  }

  def runCliMode(args: Array[String]): Unit = {
    val command = args(0)

    if (command == "-help" || args.length < 2) {
      printHelp()
      return
    }

    val inputFile = args(1)

    try {
      val rawSource = Source.fromFile(inputFile).mkString
      val cleanSource = rawSource.replaceAll("(?m)^\\s*name\\s+[a-zA-Z0-9_]+[;\\s]*\n?", "")
      // the parser reads "name" itself: parsing the original text keeps the line numbers of error messages right
      val graph = Parser2.parseProgram(rawSource)

      command match {

        case "-text" =>
          println(graph.toString)

        case "-mermaid" =>
          println(RxGraph.toMermaid(graph))

        case "-translate" | "-glts" =>
          val translation = RTATranslator.translate_syntax(graph, cleanSource)
          saveOrPrint(translation, args, 2)

        case "-mcrl2" =>
          saveOrPrint(rta.backend.MCRL2(graph), args, 2)

        case "-prism" =>
          val prismCode = PrismConverter2(graph, cleanSource)
          saveOrPrint(prismCode, args, 2)

        case "-latex" =>
          println("Warning: LaTeX (TikZ) export is done from the visual rendering.")
          println("Please use Server Mode (no arguments) and export via the Graphical Interface.")

        case "-step" =>
          val currentGraph = applyHistory(graph, if (args.length > 2) args(2) else "")
          val transitions = RxSemantics.nextEdge(currentGraph)
          val probs = RxSemantics.transitionProbabilities(currentGraph, transitions)

          val userVars = currentGraph.val_env.filterNot(_._1.show.startsWith("__"))
          if (userVars.nonEmpty) {
             println(s"Variables: ${userVars.map(kv => s"${kv._1.show}=${kv._2}").mkString(", ")}")
          }

          if (transitions.isEmpty) println("Deadlock: No enabled transitions.")
          else {
            println(s"Current State: ${currentGraph.inits.mkString(", ")}")
            println("Enabled Transitions:")
            transitions.toList.sortBy(t => Program2.showEdge(t._1)).foreach { case (e @ (from, to, tId, lbl), _) =>
              val p = probs.getOrElse(e, 1.0)
              println(s"  - [${lbl.show}] from ${from.show} to ${to.show} (P=${f"$p%.3f"})")
            }
          }

        case "-stats" =>
          var visited = Set[RxGraph]()
          var toVisit = List(graph)
          var edgesCount = 0
          val limit = 2000
          while(toVisit.nonEmpty && visited.size < limit) {
            val current = toVisit.head
            toVisit = toVisit.tail
            if (!visited.contains(current)) {
              visited += current
              val nexts = RxSemantics.nextEdge(current).map(_._2)
              edgesCount += nexts.size
              toVisit = toVisit ++ nexts.toList
            }
          }
          val msg = if (visited.size >= limit) s" (stopped after $limit states)" else ""
          println(s"== Statistics ==\nStates: ${visited.size}$msg\nTransitions: $edgesCount")

        case "-check" =>
          val res = AnalyseLTS.randomWalk(graph)._4
          if (res.isEmpty) println("No problems found.")
          else println(res.mkString("\n"))

        case "-deltacut" =>
          val delta = args(2).toDouble
          val cutGraph = graph.applyDeltaCut(delta)
          saveOrPrint(cutGraph.toRta, args, 3)

        case "-merge" =>
          val sourceB = Source.fromFile(args(2)).mkString.replaceAll("(?m)^\\s*name\\s+[a-zA-Z0-9_]+[;\\s]*\n?", "")
          val graphB = Parser2.parseProgram(sourceB)
          val opType = args(3)
          val agg = args(4)
          val result = if (opType == "union") graph.union(graphB, agg) else graph.intersection(graphB, agg)
          saveOrPrint(result.toRta, args, 5)

        case "-bestpath" =>
          val targetType = args(2)
          val targetValue = args(3)
          val targetInt = if (args.length > 4) args(4).toInt else 0
          val criterion = if (args.length > 5) args(5) else "max"
          val isMax = criterion == "max"

          val goal: RxGraph => Boolean = targetType match {
            case "state" => AnalyseLTS.goalState(targetValue)
            case "variable" => AnalyseLTS.goalVariable(targetValue, targetInt)
            case _ => _ => false
          }

          AnalyseLTS.findBestPath(graph, goal, isMax) match {
            case Some(result) =>
              val pathStr = result.path.map(e => s"${e._4.show}").mkString(" -> ")
              val label = if (isMax) "Most Probable" else "Least Probable"
              println(s"$label path found!\nProbability: ${f"${result.probability}%.4f"}\nPath: $pathStr")
            case None =>
              println("Could not find a path to the target.")
          }

        case "-cytoscape" =>
          var currentGraph = graph
          var outIdx = 2
          if (args.length > 2 && !args(2).endsWith(".json")) {
             currentGraph = applyHistory(graph, args(2))
             outIdx = 3
          }
          val json = rta.backend.CytoscapeConverter(currentGraph)
          saveOrPrint(json, args, outIdx)

        case "-pdl" =>
          if (args.length < 4) {
            println("Usage: -pdl <model.r> <initial_state> <pdl_formula> [history]")
          } else {
            val stateStr = args(2)
            val formulaStr = args(3)

            val currentGraph = applyHistory(graph, if (args.length > 4) args(4) else "")

            val qnameRes = try { Right(QName(stateStr.split('.').toList)) } catch { case e: Exception => Left(e.getMessage) }
            qnameRes match {
              case Right(startState) =>
                if (!currentGraph.states.contains(startState) && !currentGraph.inits.contains(startState)) {
                  println(s"Error: State '$stateStr' not found in the model.")
                } else {
                  val formula = PdlParser.parsePdlFormula(formulaStr)
                  println(PdlEvaluator.evaluateFormula(startState, formula, currentGraph))
                }
              case Left(err) => println(s"Error reading state: $err")
            }
          }

        case "-train" | "-train2" =>
          if (args.length < 3) {
            println(s"Usage: $command <model.r> <training_data.txt> [output.r]")
          } else {
            val trainingFile = args(2)
            println(s"Starting training from $trainingFile ...")
            val startTime = System.currentTimeMillis()
            val src = Source.fromFile(trainingFile)
            val finalGraph =
              try TrainingEngine.trainFromLines(
                rx = graph,
                lines = src.getLines(),
                onProgress = (sessions, events) => println(s"Processed $sessions sessions ($events events)...")
              )
              finally src.close()
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            println(s"Training completed in $elapsed seconds. ${TrainingEngine.lastReport.summary}")
            saveOrPrint(getUpdatedSource(finalGraph, rawSource), args, 3)
          }

        case "-lts" =>
          println(generateLTSMermaid(graph))

        case _ => printHelp()
      }

    } catch {
      case e: java.io.FileNotFoundException =>
        System.err.println(s"File not found: ${e.getMessage}")
        System.exit(2)
      case e: Exception =>
        System.err.println(s"Error: ${e.getMessage}")
        if (System.getenv("RTA_DEBUG") != null) e.printStackTrace()
        System.exit(4)
    }
  }

  def getUpdatedSource(graph: RxGraph, currentSource: String): String =
    rta.backend.SourceUpdater.updateWeights(graph, currentSource)

  private def saveOrPrint(content: String, args: Array[String], outIdx: Int): Unit = {
    if (args.length > outIdx) {
      val outName = args(outIdx)
      new PrintWriter(outName) { write(content); close() }
      println(s"Result successfully saved to: $outName")
    } else {
      println(content)
    }
  }

  def generateLTSMermaid(root: RxGraph): String = {
    var visited = Set[RxGraph](root)
    var queue = List(root)
    var transitionsStr = List[String]()

    var stateToId = Map[RxGraph, Int](root -> 0)
    var idCounter = 0

    def getId(g: RxGraph): Int = {
      if (stateToId.contains(g)) stateToId(g)
      else {
        idCounter += 1
        stateToId += (g -> idCounter)
        idCounter
      }
    }

    val maxStates = 2000

    while(queue.nonEmpty && visited.size < maxStates) {
      val current = queue.head
      queue = queue.tail
      val sourceId = getId(current)

      val nexts = RxSemantics.nextEdge(current)

      for ((edge, nextState) <- nexts) {
        val targetId = getId(nextState)
        val label = edge._4.show
        transitionsStr = s"$sourceId -->|\"$label\"| $targetId" :: transitionsStr

        if (!visited.contains(nextState)) {
          visited += nextState
          queue = queue :+ nextState
        }
      }
    }

    val nodes = stateToId.map { case (st, id) =>
      val lbl = st.inits.mkString(",")
      val style = if(st == root) "style " + id + " fill:#bbf,stroke:#333,stroke-width:2px" else ""
      s"$id(\"$lbl\")\n$style"
    }.mkString("\n")

    s"""graph LR
       |${transitionsStr.reverse.mkString("\n")}
       |$nodes
       |""".stripMargin
  }

  def printHelp(): Unit = {
    println(
      """
        |===================================================================
        |                        RePA Tool CLI
        |===================================================================
        |Usage: java -jar RTATool.jar [COMMAND] <MODEL.r> [EXTRA_OPTIONS]
        |
        |No arguments: Opens the Graphical Interface in the Browser (Local Server).
        |
        |Commands:
        |  -prism <file>                 : Export to PRISM (.pm)
        |  -mcrl2 <file>                 : Export to mCRL2
        |  -translate <file>             : Translate code to GLTS
        |  -train <file> <train.txt>     : Train model with log and update weights
        |  -text <file>                  : Print textual state
        |  -mermaid <file>               : Print simple graph of initial state
        |  -step <file>                  : List enabled transitions
        |  -lts <file>                   : Generate full Mermaid diagram (LTS)
        |  -pdl <file> <state> <form>    : Evaluate PDL/PCTL formula
        |  -stats <file>                 : Count reachable states/transitions
        |  -check <file>                 : Inspect inconsistencies/deadlocks
        |  -deltacut <file> <delta>      : Prune model given a delta
        |  -merge <A.r> <B.r> <op> <ag>  : Combine 2 models (union/intersect)
        |  -bestpath <file> <t> <v> <n>  : Discover best statistical path
        |
        |Note: If you add a filename at the end, the output will be saved
        |      to that file instead of printing to the terminal.
        |===================================================================
        |""".stripMargin)
  }

  class ResourceHandler extends HttpHandler {
    override def handle(t: HttpExchange): Unit = {
      var path = t.getRequestURI.getPath
      if (path == "/" || path == "") path = "/index.html"
      val stream = getClass.getResourceAsStream(path)
      if (stream == null) {
        t.sendResponseHeaders(404, 0); t.getResponseBody.close()
      } else {
        if (path.endsWith(".html")) t.getResponseHeaders.set("Content-Type", "text/html; charset=utf-8")
        else if (path.endsWith(".js")) t.getResponseHeaders.set("Content-Type", "application/javascript")
        else if (path.endsWith(".css")) t.getResponseHeaders.set("Content-Type", "text/css")
        t.sendResponseHeaders(200, 0)
        stream.transferTo(t.getResponseBody)
        stream.close(); t.getResponseBody.close()
      }
    }
  }

  def runServerMode(): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/", new ResourceHandler())
    server.start()
    val url = s"http://localhost:${server.getAddress.getPort}/index.html"
    println(s"Graphical Interface running at: $url")

    if (Desktop.isDesktopSupported && Desktop.getDesktop.isSupported(Desktop.Action.BROWSE)) {
      Desktop.getDesktop.browse(new URI(url))
    }
    Thread.currentThread().join()
  }
}