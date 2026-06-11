package rta.frontend

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import rta.syntax.Parser2
import rta.syntax.Program2.{RxGraph, Edge, QName}
import rta.backend.{RxSemantics, CytoscapeConverter, PdlEvaluator, MCRL2, AnalyseLTS,PrismConverter2,TrainingEngine}
import rta.syntax.PdlParser
import rta.syntax.RTATranslator
import rta.syntax.Condition

@JSExportTopLevel("RTA")
object RTAAPI {

  private var currentGraph: Option[RxGraph] = None
  private var currentSource: String = ""
  private var history: List[RxGraph] = Nil

  private def escapeJson(str: String): String = {
    if (str == null) "" else str
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "")
      .replace("\t", "\\t")
  }

  @JSExport
  def getAllStepsMermaid(): String = {
    currentGraph.map { root =>
      var visited = Set[RxGraph](root)
      var queue = List(root)
      var transitionsStr = List[String]()
      
      var stateToId = Map[RxGraph, Int](root -> 0)
      var idCounter = 0
      
      def getId(g: RxGraph): Int = {
        stateToId.getOrElse(g, {
          idCounter += 1
          stateToId += (g -> idCounter)
          idCounter
        })
      }

      val maxStates = 500 

      while(queue.nonEmpty && visited.size < maxStates) {
        val current = queue.head
        queue = queue.tail
        val sourceId = getId(current)
        
        val edgeNexts = RxSemantics.nextEdge(current)
        for ((edge, nextState) <- edgeNexts) {
          val targetId = getId(nextState)
          val label = if (edge._4.n.nonEmpty) s"${edge._3.show}:${edge._4.show}" else edge._3.show
          transitionsStr = s"""$sourceId --->|"$label"| $targetId""" :: transitionsStr
          if (!visited.contains(nextState)) {
            visited += nextState
            queue = queue :+ nextState
          }
        }
      }
       
      val nodeDefinitions = stateToId.map { case (state, id) =>
        val label = s"${state.inits.mkString(", ")}"
        val style = if (state == root) s"\nstyle $id fill:#9ece6a,stroke:#333,stroke-width:2px" else ""
        s"""$id("$label")$style"""
      }.mkString("\n")

      s"""graph LR
         |${transitionsStr.distinct.reverse.mkString("\n")}
         |$nodeDefinitions
         |""".stripMargin
      
    }.getOrElse("graph LR\n0(Nenhum modelo carregado)")
  }


  @JSExport
  def applySavedWeights(weightsJson: String): String = {
    currentGraph match {
      case Some(rx) =>
        try {
          val weightsMap = scala.scalajs.js.JSON.parse(weightsJson).asInstanceOf[scala.scalajs.js.Dictionary[Double]]
          var newWeights = rx.weights

          for ((actionNodeId, p) <- weightsMap) {
            val parts = actionNodeId.split("_")
            if (parts.length >= 5) {
              val from = stringToQName(parts(1))
              val to   = stringToQName(parts(2)) 
              val id   = stringToQName(parts(3)) 
              val lbl  = stringToQName(parts.slice(4, parts.length).mkString("_"))
              
              newWeights += ((from, to, id, lbl) -> p)
            }
          }

          val updatedGraph = rx.copy(weights = newWeights)
          currentGraph = Some(updatedGraph)
          
          history = updatedGraph :: history.tail
          
          generateSimulationJson(updatedGraph, None)
        } catch {
          case _: Throwable => generateSimulationJson(rx, None)
        }
      case None => """{"error": "No model loaded"}"""
    }
  }

  @JSExport
  def loadModel(sourceCode: String): String = {
    try {
      currentSource = sourceCode
      val graph = Parser2.parseProgram(sourceCode)
      currentGraph = Some(graph)
      history = List(graph)
      generateSimulationJson(graph, None)
    } catch {
      case e: Throwable =>
        s"""{"error": "${escapeJson("Erro ao fazer o parse: " + e.getMessage)}"}"""
    }
  }

  @JSExport
  def takeStep(edgeJson: String): String = {
    currentGraph match {
      case Some(graph) =>
        try {
          val edgeData = js.JSON.parse(edgeJson)
          
          val fromStr = edgeData.selectDynamic("from").toString
          val toStr   = edgeData.selectDynamic("to").toString
          
          val idStr   = edgeData.selectDynamic("tId").toString 
          
          val lblStr  = edgeData.selectDynamic("label").toString 

          val from = stringToQName(fromStr)
          val to   = stringToQName(toStr)
          val id   = stringToQName(idStr)
          val lbl  = stringToQName(lblStr)
          
          val clickedEdge: Edge = (from, to, id, lbl)

          RxSemantics.nextEdge(graph).find(_._1 == clickedEdge) match {
            case Some((_, nextGraph)) =>
              history = nextGraph :: history
              currentGraph = Some(nextGraph)
              generateSimulationJson(nextGraph, Some(clickedEdge))
            case None => 
              println(s"Falha ao encontrar: $clickedEdge")
              s"""{"error": "Transição inválida."}"""
          }
        } catch {
          case e: Throwable => s"""{"error": "${escapeJson(e.getMessage)}"}"""
        }
      case None => """{"error": "Nenhum modelo carregado."}"""
    }
  }


  @JSExport
  def trainSingleSession(jsonSession: String): String = {
    currentGraph match {
      case Some(startGraph) =>
        try {
          val session = js.JSON.parse(jsonSession).asInstanceOf[js.Array[String]].toSeq
          
          val finalGraph = TrainingEngine.trainFromBatch(startGraph, Seq(session))
          
          currentGraph = Some(finalGraph)
          history = finalGraph :: history
          
          generateSimulationJson(finalGraph, None)
        } catch {
          case e: Throwable => s"""{"error": "Erro na sessão: ${escapeJson(e.getMessage)}"}"""
        }
      case None => """{"error": "Sem modelo."}"""
    }
  }

  @JSExport
  def trainBatch(jsonBatch: String): String = {
    currentGraph match {
      case Some(startGraph) =>
        try {
          val batch = js.JSON.parse(jsonBatch).asInstanceOf[js.Array[js.Array[String]]]
          val sessions: Seq[Seq[String]] = batch.toSeq.map(_.toSeq)

          val finalGraph = TrainingEngine.trainFromBatch(startGraph, sessions)
          
          currentGraph = Some(finalGraph)
          history = finalGraph :: history
          generateSimulationJson(finalGraph, None)
        } catch {
          case e: Throwable => s"""{"error": "Erro no batch: ${escapeJson(e.getMessage)}"}"""
        }
      case None => """{"error": "Sem modelo."}"""
    }
  }
  
  @JSExport
  def undo(): String = {
    if (history.size > 1) {
      history = history.tail
      currentGraph = history.headOption
      generateSimulationJson(currentGraph.get, None)
    } else {
      currentGraph.map(g => generateSimulationJson(g, None)).getOrElse("{}")
    }
  }




  @JSExport
  def getMcrl2(): String = currentGraph.map(g => MCRL2(g)).getOrElse("Modelo vazio")

  @JSExport
  def translateToGLTS(): String = {
    currentGraph match {
      case Some(g) => RTATranslator.translate_syntax(g, currentSource)
      case None => "Erro: Carregue um modelo primeiro."
    }
  }

  @JSExport
  def exportDeltaCutModel(delta: Double): String = {
    currentGraph match {
      case Some(g) => g.applyDeltaCut(delta).toRta
      case None => "Erro: Carregue um modelo primeiro."
    }
  }


  @JSExport
  def getUpdatedSource(): String = {
    currentGraph match {
      case Some(graph) =>
        val lines = currentSource.split("\n")
        

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

      case None => currentSource
    }
  }


  @JSExport
  def checkProblems(): String = {
    currentGraph.map { g =>
      AnalyseLTS.randomWalk(g)._4 match {
        case Nil => "Nenhum problema encontrado."
        case m => m.mkString("\n")
      }
    }.getOrElse("Modelo vazio")
  }

  @JSExport
  def getStats(): String = {
    currentGraph.map { root =>
      var visited = Set[RxGraph]()
      var toVisit = List(root)
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
      s"""== Estatísticas ==\nEstados: ${visited.size}$msg\nTransições: $edgesCount"""
    }.getOrElse("Modelo vazio")
  }

  @JSExport
  def runPdl(stateStr: String, formulaStr: String): String = {
    currentGraph match {
      case Some(rx) =>
        try {
          val adaptedState = stateStr.replace('/', '.')
          Parser2.pp[QName](Parser2.qname, adaptedState) match {
            case Left(err) => s"Error parsing state '$stateStr': $err"
            case Right(startState) =>
              if (!rx.states.contains(startState)) {
                 s"State '${startState.show}' not found in the current model."
              } else {
                 val formula = PdlParser.parsePdlFormula(formulaStr)
                 println(formula)
                 val result = PdlEvaluator.evaluateFormula(startState, formula, rx)
                 result 
              }
          }
        } catch {
          case e: Throwable => 
            val msg = if (e.getMessage != null) e.getMessage else e.toString
            s"Evaluation Error: $msg"
        }
      case None => "Model not loaded."
    }
  }


  


  @JSExport
  def getPrismModel2(): String = {
    currentGraph.map(g => PrismConverter2(g,currentSource)).getOrElse("Erro: Modelo nao carregado")
  }


  @JSExport
  def trainWithDataStream(jsonEvents: String): String = {
    currentGraph match {
      case Some(startGraph) =>
        try {
          val events = scala.scalajs.js.JSON.parse(jsonEvents).asInstanceOf[scala.scalajs.js.Array[String]].toSeq
          
          val finalGraph = TrainingEngine.trainFromStream(startGraph, events)
          
          currentGraph = Some(finalGraph)
          history = finalGraph :: history
          
          generateSimulationJson(finalGraph, None)
        } catch {
          case e: Throwable => s"""{"error": "Erro no fluxo de dados: ${escapeJson(e.getMessage)}"}"""
        }
      case None => """{"error": "Carregue o modelo antes de treinar."}"""
    }
  }

    @JSExport
  def getExamples(): String = {
    val examples = List(
      "CheatSheet" ->
      """name CheatSheet_Reference
        |// ==========================================
        |// RTA DSL - QUICK REFERENCE GUIDE
        |// ==========================================
        |
        |// 1. GLOBAL SETTINGS
        |// ------------------------------------------
        |// Determines how remaining probability is distributed when a rule steals weight.
        |// Options: normalize (default), equal, proportional
        |calibration proportional
        |
        |// Uncomment the line below to use empirical frequency (ignores rules)
        |// training 
        |
        |// 2. VARIABLES AND INITIAL STATE
        |// ------------------------------------------
        |int counter = 0
        |int flag = 1
        |
        |
        |init start
        |
        |// 3. SIMPLE TRANSITIONS & AGGREGATIONS
        |// ------------------------------------------
        |// Syntax: source ---> target: label (weight) aggregation disabled?
        |// Aggregations define how the weight is calculated when this action triggers a rule.
        |// Options: arith (default), prod, max, min, geom
        |
        |start ---> state1: actA (0.4) max
        |start ---> state2: actB (0.6)
        |
        |// Transitions can start manually disabled
        |state1 ---> end: hiddenAct (1.0) disabled
        |
        |// 4. GUARDS AND UPDATES
        |// ------------------------------------------
        |// Syntax: ... if (condition) then { variable' := new_value }
        |state1 ---> state1: loop if (counter < 5 AND flag == 1) then {
        |    counter' := counter + 1
        |}
        |
        |// OR
        |
        |//state1 ---> state1: loop if (counter < 5 AND flag == 1)
        |
        |// OR
        |
        |//state1 ---> state1: loop counter' := counter + 1
        |
        |
        |// 5. HYPER-EDGES (DYNAMIC RULES)
        |// ------------------------------------------
        |// Syntax: trigger_label ->> target_label: rule_name (weight) aggregation
        |// ->> (ENABLE / BUFF): Increases target's probability
        |// --! (DISABLE / DEBUFF): Decreases target's probability
        |
        |// Example: Every time 'actA' is fired, it buffs 'actB'
        |actA ->> actB: buff_B (0.8)
        |
        |// Example: Every time 'actB' is fired, it severely debuffs 'actA'
        |actB --! actA: debuff_A (0.1)
        |
        |
        |// Rules can also be guarded and update variables
        |loop ->> hiddenAct: unlock (1.0) if (counter == 4) then {
        |    flag' := 0
        |}
        |
        |// 6. MODULAR SCOPES (Sub-Automata)
        |// ------------------------------------------
        |// You can encapsulate logic in specific modules
        |aut WorkerRobot {
        |    init idle
        |    idle ---> working: startWork (1.0)
        |}""".stripMargin,
      "Suene" ->
      """name suene
      |
      |init u
      |
      |u--->u:uu(0.8)
      |u ---> v: uv (0.2)
      |v ---> w: vw (0.8)
      |w ---> u: wu (0.4)
      |w ---> w: ww (0.6)
      |v ---> z:vz (0.2)
      """.stripMargin,
      "MM" ->
      """name MM
      |init x
      |x ---> x: xx(0.7)
      |x ---> y: xy (0.2)
      |x ---> z:xz (0.1)
      |z ---> y: zy (0.3) disabled
      |z ---> z: zz (0.2)
      |z ---> v: vz (0.5)
      |xy ->> zy: xyyz (0.4)
      """.stripMargin,
      "NN" ->
      """name NN
      |init x
      |x ---> x: xx(0.8)
      |x ---> z: xz (0.2) disabled
      |y ---> x:yx (0.1)
      |y ---> y:yy (0.9)
      |z ---> y: zy (0.4)
      |z ---> z: zz (0.6)
      |yx ->> xz: yxxz (0.3)
      """.stripMargin,
      "Recommender" ->
      """name AdvancedBot
        |init Home
        |Home ---> Office: go_work (0.5)
        |Home ---> Station: go_charge (0.5)
        |Home ---> Home: socialize (0.8)
        |Home ---> Home: battery_low
        |Home ---> Home: no_money
        |Office ---> Home: go_home (1.0)
        |Office ---> Office: easy_task (0.7)
        |Office ---> Office: high_stress
        |Station ---> Home: finish_charge (1.0)
        |battery_low ->> go_charge (0.6)
        |battery_low --! go_work (0.4)
        |no_money ->> go_work (0.7)
        |high_stress ->> easy_task (0.2)
        |finish_charge ->> socialize (0.1)""".stripMargin,
      "DroneSystem" ->
    """name DroneSystem
      |init Home
      |Home ---> Flying: launch (1.0)
      |Flying ---> Delivered: success (0.8)
      |Flying ---> Crashed: fail (0.2)
      |Delivered ---> Home: return (1.0)
      |fail ->> return (1.0)
    """.stripMargin,
    
  "Simple" ->
    """name Simple
      |init s0
      |s0 ---> s1: a
      |s1 ---> s0: b
      |a  --! a: offA""".stripMargin,

  "Conditions" ->
    """name Conditions
      |int counter = 0
      |init start
      |start ---> middle: step1  if (counter < 2) then {
      |  counter' := counter + 1
      |}
      |middle ---> endN: activateStep2 if (counter == 1)""".stripMargin,
  "LikeAlgorithm" ->
      """name LikeAlgorithm
      |init Feed
      |Feed ---> Watch: watch
      |Watch ---> Watch: like
      |Watch ---> Feed: dontLike
      |Watch ---> Feed: refresh disabled
      |Feed ---> List: watchLike disabled
      |List ---> Watch: watch2
      |watch ->> dontLike: wd
      |like --! dontLike: ld
      |like ->> refresh: lr
      |like ->> watchLike: lw
      |dontLike --! watchLike: dw""".stripMargin,
  
  "GRG" ->
   """name GRG
      |int a_active   = 1
      |int b_active   = 0
      |int c_active = 0
      |
      |init s0
      |
      |s0 ---> s1: aa  if (a_active == 1) then {
      |  b_active' := 1;
      |  if (c_active == 1) then {
      |  	a_active' := 0
      |  }
      |}
      |
      |s1 ---> s0: bb  if (b_active == 1) then {
      |  c_active' := 1;
      |  if (a_active == 0) then {
      |  	b_active' := 0
      |  }
      |}
      |
      |s1 ---> s2: cc  if (c_active == 1)
      |
      |
      |aa --! aa: offA2 disabled
      |aa ->> bb: onB if (b_active == 0)
      |bb ->> offA2: onOffA if (c_active == 0)
      |""".stripMargin,



  "Vending (max eur1)" ->
    """name Vending
      |init Insert
      |Insert ---> Coffee: ct50
      |Insert ---> Chocolate: eur1
      |Coffee ---> Insert: GetCoffee
      |Chocolate ---> Insert: GetChoc
      |
      |eur1 --! ct50
      |eur1 --! eur1
      |ct50 --! ct50: lastct50 disabled
      |ct50 --! eur1
      |ct50 ->> lastct50""".stripMargin,

  "Vending (max 3prod)" ->
    """name Vending
      |init pay
      |pay ---> select: insertCoin
      |select ---> soda: askSoda
      |select ---> beer: askBeer
      |soda ---> pay: getSoda
      |beer ---> pay: getBeer
      |
      |askSoda --! askSoda: noSoda disabled
      |askBeer --! askBeer: noBeer
      |askSoda ->> noSoda""".stripMargin,
    
    "BPI SIMPLE" ->
    """name BPI_2012_Automated;
    |init A_SUBMITTED;
    |A_PARTLYSUBMITTED -A_DECLINED-> A_DECLINED : e1 (0.5312)
    |A_PARTLYSUBMITTED -A_PREACCEPTED-> A_PREACCEPTED : e2 (0.0734)
    |A_PARTLYSUBMITTED -W_Afhandelen_leads-> W_Afhandelen_leads : e3 (0.3954)
    |W_Afhandelen_leads -W_Afhandelen_leads-> W_Afhandelen_leads : e4 (0.5110)
    |W_Afhandelen_leads -A_PREACCEPTED-> A_PREACCEPTED : e5 (0.0675)
    |W_Afhandelen_leads -W_Completeren_aanvraag-> W_Completeren_aanvraag : e6 (0.0675)
    |W_Afhandelen_leads -A_DECLINED-> A_DECLINED : e7 (0.3539)
    |A_SUBMITTED -A_PARTLYSUBMITTED-> A_PARTLYSUBMITTED : e8 (1.0000)
    |W_Completeren_aanvraag -W_Completeren_aanvraag-> W_Completeren_aanvraag : e9 (0.5168)
    |W_Completeren_aanvraag -A_CANCELLED-> A_CANCELLED : e10 (0.0826)
    |W_Completeren_aanvraag -W_Afhandelen_leads-> W_Afhandelen_leads : e11 (0.1530)
    |W_Completeren_aanvraag -A_DECLINED-> A_DECLINED : e12 (0.2476)
    |A_DECLINED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e13 (0.2360)
    |A_DECLINED -W_Afhandelen_leads-> W_Afhandelen_leads : e14 (0.7640)
    |A_PREACCEPTED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e15 (1.0000)
    |A_CANCELLED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e16 (1.0000)""".stripMargin,

    "BPI COMPLETE" ->
    """name BPI_2012_Automated;
    |init A_SUBMITTED;
    |A_ACCEPTED -O_SELECTED-> O_SELECTED : e1 (0.5631)
    |A_ACCEPTED -A_FINALIZED-> A_FINALIZED : e2 (0.4086)
    |A_ACCEPTED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e3 (0.0145)
    |A_ACCEPTED -A_CANCELLED-> A_CANCELLED : e4 (0.0090)
    |A_ACCEPTED -A_DECLINED-> A_DECLINED : e5 (0.0049)
    |A_FINALIZED -O_CREATED-> O_CREATED : e6 (0.5797)
    |A_FINALIZED -O_SELECTED-> O_SELECTED : e7 (0.4203)
    |O_ACCEPTED -A_ACTIVATED-> A_ACTIVATED : e8 (0.2523)
    |O_ACCEPTED -A_APPROVED-> A_APPROVED : e9 (0.2916)
    |O_ACCEPTED -A_REGISTERED-> A_REGISTERED : e10 (0.3437)
    |O_ACCEPTED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e11 (0.1025)
    |O_ACCEPTED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e12 (0.0098)
    |A_PREACCEPTED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e13 (1.0000)
    |O_DECLINED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e14 (0.3990)
    |O_DECLINED -A_DECLINED-> A_DECLINED : e15 (0.5224)
    |O_DECLINED -W_Nabellen_offertes-> W_Nabellen_offertes : e16 (0.0337)
    |O_DECLINED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e17 (0.0449)
    |O_SELECTED -A_FINALIZED-> A_FINALIZED : e18 (0.4135)
    |O_SELECTED -O_CREATED-> O_CREATED : e19 (0.4478)
    |O_SELECTED -O_CANCELLED-> O_CANCELLED : e20 (0.1385)
    |O_SELECTED -W_Nabellen_offertes-> W_Nabellen_offertes : e21 (0.0001)
    |A_APPROVED -O_ACCEPTED-> O_ACCEPTED : e22 (0.2110)
    |A_APPROVED -A_REGISTERED-> A_REGISTERED : e23 (0.3037)
    |A_APPROVED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e24 (0.1224)
    |A_APPROVED -A_ACTIVATED-> A_ACTIVATED : e25 (0.3513)
    |A_APPROVED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e26 (0.0116)
    |W_Wijzigen_contractgegevens -W_Valideren_aanvraag-> W_Valideren_aanvraag : e27 (0.2500)
    |W_Wijzigen_contractgegevens -W_Wijzigen_contractgegevens-> W_Wijzigen_contractgegevens : e28 (0.6250)
    |W_Wijzigen_contractgegevens -W_Nabellen_offertes-> W_Nabellen_offertes : e29 (0.1250)
    |O_CREATED -O_SENT-> O_SENT : e30 (1.0000)
    |W_Afhandelen_leads -W_Afhandelen_leads-> W_Afhandelen_leads : e31 (0.4879)
    |W_Afhandelen_leads -A_PREACCEPTED-> A_PREACCEPTED : e32 (0.1755)
    |W_Afhandelen_leads -W_Completeren_aanvraag-> W_Completeren_aanvraag : e33 (0.1756)
    |W_Afhandelen_leads -A_DECLINED-> A_DECLINED : e34 (0.1559)
    |W_Afhandelen_leads -W_Beoordelen_fraude-> W_Beoordelen_fraude : e35 (0.0051)
    |W_Afhandelen_leads -A_CANCELLED-> A_CANCELLED : e36 (0.0001)
    |A_SUBMITTED -A_PARTLYSUBMITTED-> A_PARTLYSUBMITTED : e37 (1.0000)
    |O_SENT -W_Nabellen_offertes-> W_Nabellen_offertes : e38 (0.9435)
    |O_SENT -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e39 (0.0563)
    |O_SENT -W_Completeren_aanvraag-> W_Completeren_aanvraag : e40 (0.0001)
    |O_CANCELLED -O_CREATED-> O_CREATED : e41 (0.2885)
    |O_CANCELLED -A_CANCELLED-> A_CANCELLED : e42 (0.2272)
    |O_CANCELLED -O_SELECTED-> O_SELECTED : e43 (0.3084)
    |O_CANCELLED -W_Nabellen_offertes-> W_Nabellen_offertes : e44 (0.1517)
    |O_CANCELLED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e45 (0.0207)
    |O_CANCELLED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e46 (0.0036)
    |A_ACTIVATED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e47 (0.4190)
    |A_ACTIVATED -A_REGISTERED-> A_REGISTERED : e48 (0.2431)
    |A_ACTIVATED -A_APPROVED-> A_APPROVED : e49 (0.2128)
    |A_ACTIVATED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e50 (0.0396)
    |A_ACTIVATED -O_ACCEPTED-> O_ACCEPTED : e51 (0.0855)
    |A_PARTLYSUBMITTED -A_PREACCEPTED-> A_PREACCEPTED : e52 (0.3707)
    |A_PARTLYSUBMITTED -A_DECLINED-> A_DECLINED : e53 (0.2620)
    |A_PARTLYSUBMITTED -W_Afhandelen_leads-> W_Afhandelen_leads : e54 (0.3621)
    |A_PARTLYSUBMITTED -W_Beoordelen_fraude-> W_Beoordelen_fraude : e55 (0.0051)
    |O_SENT_BACK -W_Valideren_aanvraag-> W_Valideren_aanvraag : e56 (1.0000)
    |W_Valideren_aanvraag -W_Nabellen_offertes-> W_Nabellen_offertes : e57 (0.1802)
    |W_Valideren_aanvraag -A_REGISTERED-> A_REGISTERED : e58 (0.0126)
    |W_Valideren_aanvraag -W_Valideren_aanvraag-> W_Valideren_aanvraag : e59 (0.3238)
    |W_Valideren_aanvraag -O_ACCEPTED-> O_ACCEPTED : e60 (0.0629)
    |W_Valideren_aanvraag -W_Wijzigen_contractgegevens-> W_Wijzigen_contractgegevens : e61 (0.0002)
    |W_Valideren_aanvraag -A_DECLINED-> A_DECLINED : e62 (0.0177)
    |W_Valideren_aanvraag -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e63 (0.3273)
    |W_Valideren_aanvraag -A_APPROVED-> A_APPROVED : e64 (0.0271)
    |W_Valideren_aanvraag -O_DECLINED-> O_DECLINED : e65 (0.0193)
    |W_Valideren_aanvraag -A_ACTIVATED-> A_ACTIVATED : e66 (0.0110)
    |W_Valideren_aanvraag -O_CANCELLED-> O_CANCELLED : e67 (0.0061)
    |W_Valideren_aanvraag -O_SELECTED-> O_SELECTED : e68 (0.0059)
    |W_Valideren_aanvraag -W_Beoordelen_fraude-> W_Beoordelen_fraude : e69 (0.0051)
    |W_Valideren_aanvraag -A_CANCELLED-> A_CANCELLED : e70 (0.0007)
    |W_Nabellen_offertes -W_Completeren_aanvraag-> W_Completeren_aanvraag : e71 (0.0989)
    |W_Nabellen_offertes -W_Nabellen_offertes-> W_Nabellen_offertes : e72 (0.7114)
    |W_Nabellen_offertes -O_SENT_BACK-> O_SENT_BACK : e73 (0.0641)
    |W_Nabellen_offertes -W_Valideren_aanvraag-> W_Valideren_aanvraag : e74 (0.0632)
    |W_Nabellen_offertes -O_SELECTED-> O_SELECTED : e75 (0.0151)
    |W_Nabellen_offertes -O_CANCELLED-> O_CANCELLED : e76 (0.0307)
    |W_Nabellen_offertes -A_DECLINED-> A_DECLINED : e77 (0.0005)
    |W_Nabellen_offertes -A_CANCELLED-> A_CANCELLED : e78 (0.0155)
    |W_Nabellen_offertes -W_Wijzigen_contractgegevens-> W_Wijzigen_contractgegevens : e79 (0.0000)
    |W_Nabellen_offertes -O_DECLINED-> O_DECLINED : e80 (0.0004)
    |W_Nabellen_offertes -O_CREATED-> O_CREATED : e81 (0.0000)
    |A_REGISTERED -A_APPROVED-> A_APPROVED : e82 (0.2588)
    |A_REGISTERED -A_ACTIVATED-> A_ACTIVATED : e83 (0.2984)
    |A_REGISTERED -O_ACCEPTED-> O_ACCEPTED : e84 (0.1479)
    |A_REGISTERED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e85 (0.2690)
    |A_REGISTERED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e86 (0.0258)
    |A_CANCELLED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e87 (0.3485)
    |A_CANCELLED -W_Nabellen_offertes-> W_Nabellen_offertes : e88 (0.2170)
    |A_CANCELLED -O_CANCELLED-> O_CANCELLED : e89 (0.4015)
    |A_CANCELLED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e90 (0.0265)
    |A_CANCELLED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e91 (0.0065)
    |W_Beoordelen_fraude -W_Completeren_aanvraag-> W_Completeren_aanvraag : e92 (0.0099)
    |W_Beoordelen_fraude -W_Beoordelen_fraude-> W_Beoordelen_fraude : e93 (0.6046)
    |W_Beoordelen_fraude -A_DECLINED-> A_DECLINED : e94 (0.0939)
    |W_Beoordelen_fraude -W_Valideren_aanvraag-> W_Valideren_aanvraag : e95 (0.1532)
    |W_Beoordelen_fraude -W_Afhandelen_leads-> W_Afhandelen_leads : e96 (0.1384)
    |W_Completeren_aanvraag -W_Completeren_aanvraag-> W_Completeren_aanvraag : e97 (0.7183)
    |W_Completeren_aanvraag -A_ACCEPTED-> A_ACCEPTED : e98 (0.0966)
    |W_Completeren_aanvraag -W_Nabellen_offertes-> W_Nabellen_offertes : e99 (0.0948)
    |W_Completeren_aanvraag -A_CANCELLED-> A_CANCELLED : e100 (0.0212)
    |W_Completeren_aanvraag -W_Afhandelen_leads-> W_Afhandelen_leads : e101 (0.0476)
    |W_Completeren_aanvraag -A_DECLINED-> A_DECLINED : e102 (0.0206)
    |W_Completeren_aanvraag -W_Beoordelen_fraude-> W_Beoordelen_fraude : e103 (0.0001)
    |W_Completeren_aanvraag -O_SELECTED-> O_SELECTED : e104 (0.0005)
    |W_Completeren_aanvraag -A_FINALIZED-> A_FINALIZED : e105 (0.0004)
    |A_DECLINED -W_Completeren_aanvraag-> W_Completeren_aanvraag : e106 (0.2646)
    |A_DECLINED -O_DECLINED-> O_DECLINED : e107 (0.0911)
    |A_DECLINED -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e108 (0.0119)
    |A_DECLINED -W_Afhandelen_leads-> W_Afhandelen_leads : e109 (0.5311)
    |A_DECLINED -W_Beoordelen_fraude-> W_Beoordelen_fraude : e110 (0.0136)
    |A_DECLINED -W_Valideren_aanvraag-> W_Valideren_aanvraag : e111 (0.0827)
    |A_DECLINED -W_Nabellen_offertes-> W_Nabellen_offertes : e112 (0.0050)
    |W_Nabellen_incomplete_dossiers -W_Valideren_aanvraag-> W_Valideren_aanvraag : e113 (0.2207)
    |W_Nabellen_incomplete_dossiers -W_Nabellen_incomplete_dossiers-> W_Nabellen_incomplete_dossiers : e114 (0.7468)
    |W_Nabellen_incomplete_dossiers -O_DECLINED-> O_DECLINED : e115 (0.0020)
    |W_Nabellen_incomplete_dossiers -O_SELECTED-> O_SELECTED : e116 (0.0040)
    |W_Nabellen_incomplete_dossiers -O_SENT_BACK-> O_SENT_BACK : e117 (0.0081)
    |W_Nabellen_incomplete_dossiers -O_CANCELLED-> O_CANCELLED : e118 (0.0061)
    |W_Nabellen_incomplete_dossiers -O_ACCEPTED-> O_ACCEPTED : e119 (0.0044)
    |W_Nabellen_incomplete_dossiers -A_APPROVED-> A_APPROVED : e120 (0.0018)
    |W_Nabellen_incomplete_dossiers -A_DECLINED-> A_DECLINED : e121 (0.0015)
    |W_Nabellen_incomplete_dossiers -A_ACTIVATED-> A_ACTIVATED : e122 (0.0009)
    |W_Nabellen_incomplete_dossiers -A_CANCELLED-> A_CANCELLED : e123 (0.0029)
    |W_Nabellen_incomplete_dossiers -A_REGISTERED-> A_REGISTERED : e124 (0.0008)
    |W_Nabellen_incomplete_dossiers -W_Wijzigen_contractgegevens-> W_Wijzigen_contractgegevens : e125 (0.0000)""".stripMargin,

    "commerce" ->
    """name ECommerce_Journey;
    |init Start;

    |Start -enter_view-> view : e1 (0.9995)
    |Start -enter_cart-> cart : e2 (0.0003)
    |Start -enter_purchase-> purchase : e3 (0.0001)
    |
    |cart -cart-> cart : e4 (0.1943)
    |cart -exit_site-> exit_site : e5 (0.1057)
    |cart -purchase-> purchase : e6 (0.3371)
    |cart -view-> view : e7 (0.3629)
    |purchase -cart-> cart : e8 (0.0031)
    |purchase -exit_site-> exit_site : e9 (0.3919)
    |purchase -purchase-> purchase : e10 (0.0012)
    |purchase -view-> view : e11 (0.6038)
    |view -cart-> cart : e12 (0.0182)
    |view -exit_site-> exit_site : e13 (0.2172)
    |view -purchase-> purchase : e14 (0.0105)
    |view -view-> view : e15 (0.7542)
    |
    |exit_site -loop-> exit_site : e16 (1.0000)""".stripMargin,

    "coin" ->
    """name coin
      |init s0
      |int d = 0;
      |// Lancamentos de moeda (cada linha um "passo")
      |
      |s0 -a-> s1: coin (0.5)
      |s1 -a-> s3: coin (0.5)
      |s2 -a-> s5: coin (0.5)
      |
      |s0 -b-> s2: coin (0.5)
      |s1 -b-> s4: coin (0.5)
      |s2 -b-> s6: coin (0.5)
      |
      |// Resultados finais ou novos lancamentos
      |
      |s3 -a-> s1: coin (0.5)
      |s4 -a-> s7: coin (0.5) d':= 2
      |s5 -a-> s7: coin (0.5) d':= 4
      |s6 -a-> s2: coin (0.5)
      |
      |s3 -b-> s7: coin (0.5) d':= 1
      |s4 -b-> s7: coin (0.5) d':= 3
      |s5 -b-> s7: coin (0.5) d':= 5
      |s6 -b-> s7: coin (0.5) d':= 6
      |
      |// Loop no estado final para o modelo nao travar
      |s7 ---> s7: loop""".stripMargin,

      "Moeda1" ->
      """name Moeda_Viciada_Tempo
        |calibration proportional
        |
        |int passos = 0
        |int lado = 0
        |init lancar
        |
        |
        |lancar ---> lancar: cara (0.5) if (passos < 5) then {
        |    passos' := passos + 1
        |    lado' := 0
        |}
        |
        |lancar ---> lancar: coroa (0.5) if (passos < 5) then {
        |    passos' := passos + 1
        |    lado' := 1
        |}
        |
        |cara ->> cara: viciar (1.0)""".stripMargin,
      "Moeda2" ->
      """name Moeda2
      |calibration proportional
      |init start
      |
      |
      |start -lancar-> coroa: lancar1
      |start -lancar-> cara: lancar2
      |
      |coroa -lancar-> cara: lancarCara1
      |coroa -lancar-> coroa: lancarCoroa
      |cara -lancar-> coroa: lancarCoroa
      |cara -lancar-> cara: lancarCara2
      |
      |
      |lancarCara1 ->> lancarCara1:c1
      |lancarCara2 ->>lancarCara2:c2
      |lancar2 ->>lancarCara1:c3
      |lancar2 ->> lancarCara2:c4""".stripMargin,


    )
    "{" + examples.map{ case (k,v) => s""""$k": ${js.JSON.stringify(v)}""" }.mkString(",") + "}"
  }

  

  @JSExport
  def getCurrentStateText(): String = currentGraph.map(_.toString).getOrElse("")

  @JSExport
  def getCurrentStateMermaid(): String = currentGraph.map(g => RxGraph.toMermaid(g)).getOrElse("")

  @JSExport
  def getCurrentStateMermaidSimple(): String = currentGraph.map(g => RxGraph.toMermaidPlain(g)).getOrElse("")


  private def stringToQName(str: String): QName = if (str.isEmpty) QName(Nil) else QName(str.split('/').toList)

  @JSExport
  def mergeModels(codeA: String, codeB: String, agg: String, opType: String): String = {
    try {
      val graphA = Parser2.parseProgram(codeA)
      val graphB = Parser2.parseProgram(codeB)
      
      val resultGraph = if (opType == "union") graphA.union(graphB, agg) 
                        else graphA.intersection(graphB, agg)
      
      resultGraph.toRta 
    } catch {
      case e: Exception => s"Error: ${e.getMessage}"
    }
  }


  @JSExport
  def trainMassiveRaw(rawText: String): String = {
    currentGraph match {
      case Some(startGraph) =>
        try {
          val lines = rawText.split('\n').iterator
          
          val finalGraph = TrainingEngine.trainFromLines(startGraph, lines)
          
          currentGraph = Some(finalGraph)
          history = finalGraph :: history
          generateSimulationJson(finalGraph, None)
        } catch {
          case e: Throwable => s"""{"error": "Erro no trainMassiveRaw: ${escapeJson(e.getMessage)}"}"""
        }
      case None => """{"error": "Sem modelo."}"""
    }
  }

  @js.native
  trait BestPathParams extends js.Object {
    val targetType: String
    val targetValue: String
    val targetInt: Int
  }


  @JSExport
  def findBestPath(params: js.Dynamic): String = {
    currentGraph match {
      case Some(root) =>
        val targetType = params.targetType.toString
        val targetValue = params.targetValue.toString
        val targetInt = try { params.targetInt.asInstanceOf[Int] } catch { case _: Exception => 0 }
        
        val isMax = params.criterion.toString == "max"

        val goal: RxGraph => Boolean = targetType match {
          case "state" => AnalyseLTS.goalState(targetValue)
          case "variable" => AnalyseLTS.goalVariable(targetValue, targetInt)
          case _ => _ => false
        }

        AnalyseLTS.findBestPath(root, goal, isMax) match {
          case Some(result) =>
            val pathStr = result.path.map(e => s"${e._4.show}").mkString(" -> ")
            val label = if (isMax) "Mais Provável" else "Menos Provável"
            s"Caminho $label encontrado!\nProbabilidade: ${f"${result.probability}%.4f"}\nCaminho: $pathStr"
          case None => 
            "Não foi possível encontrar um caminho para o objetivo."
        }
      case None => "Erro: Modelo não carregado."
    }
  }
  
  private def generateSimulationJson(graph: RxGraph, traversedEdge: Option[Edge]): String = {
     val graphElementsJson = CytoscapeConverter(graph)
     
     val eventTransitions = RxSemantics.nextEdge(graph).map(_._1)
     val eventTransitionsJson = eventTransitions.map { case (from, to, id, lbl) =>
       val edge = (from, to, id, lbl)
       val p = graph.weights.getOrElse(edge, 1.0)
       val pStr = f"$p%.3f".replace(",", ".")

       s"""{"from":"$from", "to":"$to", "tId":"$id", "label":"$lbl", "p": ${f"$p%.3f".replace(",", ".")}, "isDelay": false}"""
     }.mkString(",")

     val valEnvJson = graph.val_env.map { case (n, v) => s""""${n.show}": $v""" }.mkString(",")

     val traversedJson = traversedEdge match {
    case Some((from, to, id, lbl)) => 
      s"""{"from":"$from", "to":"$to", "tId":"$id", "label":"$lbl"}"""
    case None => "null"
  }

     s"""
       |{
       |  "graphElements": $graphElementsJson,
       |  "panelData": { 
       |     "enabled": [$eventTransitionsJson], 
       |     "variables": {$valEnvJson}, 
       |     "canUndo": ${history.size > 1} 
       |  },
       |  "lastTransition": $traversedJson
       |}
       |""".stripMargin
  }
}