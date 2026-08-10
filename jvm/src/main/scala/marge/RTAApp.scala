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
    if (args.isEmpty || args(0) == "-server") {
      runServerMode()
    } else {
      runCliMode(args)
    }
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
      // Remove the "name" command so the parser works in the CLI
      val cleanSource = rawSource.replaceAll("(?m)^\\s*name\\s+[a-zA-Z0-9_]+[;\\s]*\n?", "")
      val graph = Parser2.parseProgram(cleanSource)

      command match {
        
        case "-text" =>
          println(graph.toString)

        case "-mermaid" =>
          println(RxGraph.toMermaid(graph))

        case "-translate" | "-glts" =>
          val translation = RTATranslator.translate_syntax(graph, cleanSource)
          saveOrPrint(translation, args, 2)
        
        case "-prism" =>
          val prismCode = PrismConverter2(graph, cleanSource)
          saveOrPrint(prismCode, args, 2)
          
        case "-latex" =>
          println("Warning: LaTeX (TikZ) export is done from the visual rendering.")
          println("Please use Server Mode (no arguments) and export via the Graphical Interface.")
        
        case "-step" =>
          var currentGraph = graph
          // If Python sends the history, simulate it first!
          if (args.length > 2 && args(2).trim.nonEmpty) {
             val steps = args(2).split(',')
             for (step <- steps) {
                 val transitions = RxSemantics.nextEdge(currentGraph)
                 transitions.find(_._1._4.show == step).foreach { case (_, nextG) => currentGraph = nextG }
             }
          }
          val transitions = RxSemantics.nextEdge(currentGraph)
          
          // Also print the updated variable state
          if (currentGraph.val_env.nonEmpty) {
             println(s"Variables: ${currentGraph.val_env.map(kv => s"${kv._1.show}=${kv._2}").mkString(", ")}")
          }

          if (transitions.isEmpty) println("Deadlock: No enabled transitions.")
          else {
            println(s"Current State: ${currentGraph.inits.mkString(", ")}")
            println("Enabled Transitions:")
            transitions.foreach { case ((from, to, tId, lbl), _) =>
              val weight = currentGraph.weights.getOrElse((from, to, tId, lbl), 1.0)
              println(s"  - [${lbl.show}] from ${from.show} to ${to.show} (P=${f"$weight%.3f"})")
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
          if (args.length > 2) {
             if (!args(2).endsWith(".json")) {
                 val steps = args(2).split(',')
                 for (step <- steps if step.nonEmpty) {
                     val transitions = RxSemantics.nextEdge(currentGraph)
                     transitions.find(_._1._4.show == step).foreach { case (_, nextG) => currentGraph = nextG }
                 }
                 outIdx = 3
             }
          }
          val json = rta.backend.CytoscapeConverter(currentGraph)
          saveOrPrint(json, args, outIdx)

        case "-pdl" =>
          if (args.length < 4) {
            println("Usage: -pdl <model.r> <initial_state> <pdl_formula> [history]")
          } else {
            val stateStr = args(2)
            val formulaStr = args(3)
            
            var currentGraph = graph
            if (args.length > 4 && args(4).trim.nonEmpty) {
               val steps = args(4).split(',')
               for (step <- steps) {
                   val transitions = RxSemantics.nextEdge(currentGraph)
                   transitions.find(_._1._4.show == step).foreach { case (_, nextG) => currentGraph = nextG }
               }
            }

            val qnameRes = try { Right(QName(stateStr.split('.').toList)) } catch { case e: Exception => Left(e.getMessage) }
            qnameRes match {
              case Right(startState) =>
                if (!currentGraph.states.contains(startState) && !currentGraph.inits.contains(startState)) {
                  println(s"Error: State '$stateStr' not found in the model.")
                } else {
                  val formula = PdlParser.parsePdlFormula(formulaStr)
                  val result = PdlEvaluator.evaluateFormula(startState, formula, currentGraph)
                  println(s"Result: $result")
                }
              case Left(err) => println(s"Error reading state: $err")
            }
          }

        case "-train" =>
          if (args.length < 3) {
            println("Usage: -train <model.r> <training_data.txt> [output.r]")
          } else {
            val trainingFile = args(2)
            println(s"Starting training (TrainingEngine) from $trainingFile ...")
            
            val startTime = System.currentTimeMillis()
            val lines = Source.fromFile(trainingFile).getLines()
            
            val finalGraph = TrainingEngine.trainFromLines(
              rx = graph,
              lines = lines,
              onProgress = (sessions, events) =>
                println(s"Processed $sessions sessions ($events events)...")
            )
            
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            println(s"Training completed in $elapsed seconds.")
            
            val updatedSource = getUpdatedSource(finalGraph, rawSource)
            saveOrPrint(updatedSource, args, 3)
          }


        case "-train2" =>
          if (args.length < 3) {
            println("Usage: -train2 <model.r> <training_data.txt> [output.r]")
          } else {
            val trainingFile = args(2)
            println(s"Starting massive training from $trainingFile ...")
            
            var currentGraph = graph.copy(trainingMode = true)
            var count = 0
            val startTime = System.currentTimeMillis()

            val lines = Source.fromFile(trainingFile).getLines()
            
            for (line <- lines) {
              val tLine = line.trim
              if (tLine.nonEmpty) {
                val session = tLine.split(',').map(_.trim)
                var sessionGraph = currentGraph.copy(inits = graph.inits)
                
                for (eventName <- session) {
                  val nexts = RxSemantics.nextEdge(sessionGraph)
                  val trans = nexts.find(_._1._4.show == eventName)
                  trans.foreach { case (_, next) => sessionGraph = next }
                }
                currentGraph = sessionGraph
              }
              
              count += 1
              if (count % 100000 == 0) {
                println(s"Processed $count lines...")
              }
            }

            val finalGraph = currentGraph.copy(inits = graph.inits)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            println(s"Training completed! $count sessions processed in $elapsed seconds.")

            // Extract the updated source code
            val updatedSource = getUpdatedSource(finalGraph, rawSource)
            saveOrPrint(updatedSource, args, 3)
          }

        case "-lts" =>
          println(generateLTSMermaid(graph))

        case _ => printHelp()
      }

    } catch {
      case e: java.io.FileNotFoundException => println(s"File not found: $inputFile")
      case e: Exception => 
        println("Error during execution:")
        e.printStackTrace()
    }
  }

  def getUpdatedSource(graph: RxGraph, currentSource: String): String = {
    val lines = currentSource.split("\r?\n")

    val transRegex = """^(\s*[\w./]+)\s*(-[\w./]*->|->|->>|--!|--x|--->|---->|--#--)\s*([\w./]+)\s*:\s*([\w./]+)(\s*\([\d.]+\))?(.*)$""".r

    val updatedLines = lines.map {
      case line @ transRegex(fromStr, arrow, toStr, lblStr, oldWeight, rest) =>
        val edgeWeightOpt = graph.weights.find { case (edge, w) => 
          edge._4.show == lblStr.trim
        }

        edgeWeightOpt match {
          case Some((_, weight)) =>
            s"${fromStr.trim} $arrow ${toStr.trim} : ${lblStr.trim} (${f"$weight%.3f".replace(",", ".")})${rest}"
          case None => line 
        }
      case other => other 
    }
    updatedLines.mkString("\n")
  }

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