package rta

import com.sun.net.httpserver.{HttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress
import java.awt.Desktop
import java.net.URI
import java.io.{File, PrintWriter}
import scala.io.Source
import rta.syntax.{Parser2, PdlParser, Program2, RTATranslator}
import rta.syntax.Program2.{RxGraph, QName}
import rta.backend.{RxSemantics, PdlEvaluator, PrismConverter2, AnalyseLTS,TrainingEngine}

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
      // Remove o comando "name" para o parser funcionar no CLI
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
          println("Aviso: A exportacao para LaTeX (TikZ) e feita a partir da renderizacao visual.")
          println("Por favor, use o Modo Servidor (sem argumentos) e exporte via Interface Grafica.")
        
        case "-step" =>
          var currentGraph = graph
          // Se o Python enviar o histórico, simula-o primeiro!
          if (args.length > 2 && args(2).trim.nonEmpty) {
             val steps = args(2).split(',')
             for (step <- steps) {
                 val transitions = RxSemantics.nextEdge(currentGraph)
                 transitions.find(_._1._4.show == step).foreach { case (_, nextG) => currentGraph = nextG }
             }
          }
          val transitions = RxSemantics.nextEdge(currentGraph)
          
          // Imprimir também o estado das variáveis atualizadas
          if (currentGraph.val_env.nonEmpty) {
             println(s"Variaveis: ${currentGraph.val_env.map(kv => s"${kv._1.show}=${kv._2}").mkString(", ")}")
          }

          if (transitions.isEmpty) println("Deadlock: Nenhuma transicao habilitada.")
          else {
            println(s"Estado Atual: ${currentGraph.inits.mkString(", ")}")
            println("Transicoes Habilitadas:")
            transitions.foreach { case ((from, to, tId, lbl), _) =>
              val weight = currentGraph.weights.getOrElse((from, to, tId, lbl), 1.0)
              println(s"  - [${lbl.show}] de ${from.show} para ${to.show} (P=${f"$weight%.3f"})")
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
          val msg = if (visited.size >= limit) s" (parou após $limit estados)" else ""
          println(s"== Estatísticas ==\nEstados: ${visited.size}$msg\nTransições: $edgesCount")

        case "-check" =>
          val res = AnalyseLTS.randomWalk(graph)._4
          if (res.isEmpty) println("Nenhum problema encontrado.")
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
              val label = if (isMax) "Mais Provável" else "Menos Provável"
              println(s"Caminho $label encontrado!\nProbabilidade: ${f"${result.probability}%.4f"}\nCaminho: $pathStr")
            case None =>
              println("Não foi possível encontrar um caminho para o objetivo.")
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
            println("Uso: -pdl <modelo.r> <estado_inicial> <formula_pdl> [historico]")
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
                  println(s"Erro: Estado '$stateStr' nao encontrado no modelo.")
                } else {
                  val formula = PdlParser.parsePdlFormula(formulaStr)
                  val result = PdlEvaluator.evaluateFormula(startState, formula, currentGraph)
                  println(s"Resultado: $result")
                }
              case Left(err) => println(s"Erro ao ler estado: $err")
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
            println("Uso: -train2 <modelo.r> <dados_treino.txt> [saida.r]")
          } else {
            val trainingFile = args(2)
            println(s"A iniciar treino massivo a partir de $trainingFile ...")
            
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
                println(s"Processadas $count linhas...")
              }
            }

            val finalGraph = currentGraph.copy(inits = graph.inits)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            println(s"Treino concluido! $count sessoes processadas em $elapsed segundos.")

            // Extrai o código fonte atualizado
            val updatedSource = getUpdatedSource(finalGraph, rawSource)
            saveOrPrint(updatedSource, args, 3)
          }

        

        case "-lts" =>
          println(generateLTSMermaid(graph))

        

        case _ => printHelp()
      }

    } catch {
      case e: java.io.FileNotFoundException => println(s"Arquivo não encontrado: $inputFile")
      case e: Exception => 
        println("Erro durante a execucao:")
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
      println(s"Resultado salvo com sucesso em: $outName")
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
        |Uso: java -jar RTATool.jar [COMANDO] <MODELO.r> [OPCOES_EXTRAS]
        |
        |Sem argumentos: Abre a Interface Grafica no Navegador (Servidor Local).
        |
        |Comandos:
        |  -prism <arq>                 : Exporta para PRISM (.pm)
        |  -translate <arq>             : Traduz o codigo para GLTS
        |  -train <arq> <treino.txt>    : Treina o modelo com log e atualiza pesos
        |  -text <arq>                  : Imprime o estado textual
        |  -mermaid <arq>               : Imprime grafo simples do estado inicial
        |  -step <arq>                  : Lista transicoes habilitadas
        |  -lts <arq>                   : Gera o diagrama Mermaid completo (LTS)
        |  -pdl <arq> <estado> <form>   : Avalia formula PDL/PCTL 
        |  -stats <arq>                 : Conta estados/transicoes acessiveis
        |  -check <arq>                 : Inspeciona inconsistencias/deadlocks
        |  -deltacut <arq> <delta>      : Aplica poda no modelo dado um delta
        |  -merge <A.r> <B.r> <op> <ag> : Combina 2 modelos (union/intersect)
        |  -bestpath <arq> <t> <v> <n>  : Descobre melhor caminho estatistico
        |
        |Nota: Se adicionar um nome de ficheiro no final, a saida sera salva
        |      nesse ficheiro em vez de aparecer no terminal.
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
    println(s"Interface Grafica rodando em: $url")
    
    if (Desktop.isDesktopSupported && Desktop.getDesktop.isSupported(Desktop.Action.BROWSE)) {
      Desktop.getDesktop.browse(new URI(url))
    }
    Thread.currentThread().join()
  }
}