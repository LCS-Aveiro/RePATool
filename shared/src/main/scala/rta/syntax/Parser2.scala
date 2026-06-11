package rta.syntax

import rta.syntax.Program2.{RxGraph, QName}
import rta.syntax.{Condition, CounterUpdate, UpdateExpr, Statement, UpdateStmt, IfThenStmt}
import rta.syntax.Condition.*
import scala.util.matching.Regex

object Parser2 {

  private def tokenize(input: String): List[String] = {
    val pattern = """(//.*)|(->>|--!|--x|--->|---->|--#--|->)|(\b(?:AND|OR|if|then|else|disabled|training|init|aut|int|arith|prod|max|min|geom|calibration|equal|proportional|normalize)\b)|(:=|==|!=|<=|>=|&&|\|\||[{}();,:=\+\-\*\/<>])|([a-zA-Z_][\w\.]*'?)|(-?\d+(\.\d+)?)""".r

    pattern.findAllMatchIn(input).flatMap { m =>
      if (m.group(1) != null) None 
      else Some(m.matched)         
    }.toList
  }

  private class TokenReader(tokens: List[String]) {
    var pos = 0
    def current: String = if (pos < tokens.length) tokens(pos) else ""
    def hasNext: Boolean = pos < tokens.length
    def consume(): String = { val t = current; pos += 1; t }
    def eat(s: String): Boolean = { if (current == s) { pos += 1; true } else false }
    def expect(s: String): Unit = { if (!eat(s)) throw new RuntimeException(s"Syntax Error: Expected '$s', found '$current'") }

    def parseQName(): QName = {
      val s = consume()
      if (s.isEmpty || s == ".") throw new RuntimeException(s"Syntax Error: Empty identifier found.")

      val parts = s.split('.')
      for (part <- parts) {
        if (part == "_") throw new RuntimeException(s"Syntax Error: '_' is not a valid name for an identifier.")
        if (part.nonEmpty && part.head.isDigit) throw new RuntimeException(s"Syntax Error: Identifier '$part' cannot start with a digit.")
        val invalidPattern = """[^a-zA-Z0-9_']""".r
        if (invalidPattern.findFirstIn(part.replace("'", "")).isDefined) {
          throw new RuntimeException(s"Syntax Error: Identifier '$part' contains invalid characters.")
        }
      }
      if (s.contains(".")) QName(s.split('.').toList) else QName(List(s))
    }
    
    def tryParseInt(): Int = consume().toInt
    def tryParseDouble(): Double = consume().toDouble
  }

  def parseProgram(str: String): RxGraph = {
    val tokens = tokenize(str)
    val reader = new TokenReader(tokens)
    val rx = parseBlock(reader)
    autoInitializeWeights(rx)
  }

  private def autoInitializeWeights(rx: RxGraph): RxGraph = {
    val allEdges = (for {
      (src, targets) <- rx.edg
      (trg, id, lbl) <- targets
    } yield (src, trg, id, lbl)).toSet

    val bySource = allEdges.groupBy(_._1)
    val transitionWeights = bySource.flatMap { case (src, edges) =>
      val EPSILON = 0.01
      val explicitlyDefined = edges.filter(rx.weights.contains)
      val assigned = edges.map { e =>
        if (rx.weights.contains(e)) e -> rx.weights(e) 
        else if (explicitlyDefined.nonEmpty) e -> EPSILON 
        else e -> 1.0 
      }.toMap
      val totalSum = assigned.values.sum
      if (totalSum > 0) assigned.map { case (e, w) => e -> (w / totalSum) }
      else assigned
    }

    val ruleEdges = (for { (s, ts) <- rx.on; (t, id, l) <- ts } yield (s, t, id, l)).toSet ++
                    (for { (s, ts) <- rx.off; (t, id, l) <- ts } yield (s, t, id, l)).toSet

    val ruleWeights = ruleEdges.map { e =>
      if (rx.weights.contains(e)) e -> rx.weights(e) else e -> 0.1 
    }.toMap

    rx.copy(weights = transitionWeights ++ ruleWeights)
  }

  private def parseBlock(reader: TokenReader): RxGraph = {
    var rx = RxGraph()
    while (reader.hasNext && reader.current != "}") {
      if (reader.eat("init")) rx = rx.addInit(reader.parseQName())
      else if (reader.eat("training")) {
        rx = rx.copy(trainingMode = true)
        
        if (reader.current == "laplace" || reader.current == "aggregation") {
          val method = reader.consume()
          rx = rx.copy(trainingMethod = method)
          
          if (method == "aggregation") {
             val aggKeywords = Set("arith", "prod", "max", "min", "geom")
             if (aggKeywords.contains(reader.current)) {
               rx = rx.copy(trainingAgg = reader.consume())
             }
             if (reader.current == "(") {
               reader.consume()
               if (reader.current.matches("-?\\d+(\\.\\d+)?")) {
                 rx = rx.copy(trainingLambda = reader.tryParseDouble())
               }
               reader.expect(")")
             }
          }
        }
      }
      else if (reader.eat("calibration")) {
        val mode = reader.consume()
        if (Set("normalize", "equal", "proportional").contains(mode)) {
          rx = rx.copy(distributionMode = mode)
        } else {
          throw new RuntimeException(s"Syntax Error: Unknown calibration mode: $mode")
        }
      }
      else if (reader.eat("int")) {
        val name = reader.parseQName(); reader.expect("="); val value = reader.tryParseInt()
        rx = rx.addVariable(name, value)
      }
      else if (reader.eat("aut")) {
        val name = reader.parseQName(); reader.expect("{")
        val innerRx = parseBlock(reader); reader.expect("}")
        rx = rx ++ (name / innerRx)
      }
      else if (reader.current == ";") reader.consume()
      else rx = parseEdge(reader, rx)
      reader.eat(";") 
    }
    rx
  }

  private def parseEdge(reader: TokenReader, rx: RxGraph): RxGraph = {
    val from = reader.parseQName()
    var transId = QName(Nil)
    var arrow = ""

    if (reader.current == "-") {
      reader.consume(); transId = reader.parseQName(); arrow = reader.consume()
    } else if (reader.current == "--->") {
      arrow = "->"; reader.consume()
    } else {
      arrow = reader.consume()
    }

    val to = reader.parseQName()
    var label = QName(Nil)
    if (reader.eat(":")) label = reader.parseQName()

    if (label.n.isEmpty) {
      val mid = if (transId.n.nonEmpty) transId.show else "tau"
      label = QName(List(from.show + mid + to.show))
    }
    if (transId.n.isEmpty) transId = label

    var parsedWeight: Option[Double] = None
    var aggType: String = ""
    val aggKeywords = Set("arith", "prod", "max", "min", "geom")
    
    if (reader.current == "(") {
       reader.consume()
       if (reader.current.matches("-?\\d+(\\.\\d+)?")) {
           parsedWeight = Some(reader.tryParseDouble())
           reader.expect(")")
       }
    }

    if (aggKeywords.contains(reader.current)) {
       aggType = reader.consume()
    }

    var cond: Option[Condition] = None
    var updates: List[Statement] = Nil
    var disabled = false

    var parsingAttrs = true
    while (parsingAttrs && reader.hasNext) {
       val t = reader.current
       if (t == "disabled") { reader.consume(); disabled = true }
       else if (t == "if") {
         reader.consume(); cond = Some(parseCondition(reader))
         if (reader.eat("then")) { reader.expect("{"); updates = parseStatementsBlock(reader); reader.expect("}") }
       } 
       else if (t.endsWith("'")) updates = updates :+ parseUpdate(reader)
       else parsingAttrs = false
    }

    val wTrans = parsedWeight.getOrElse(1.0)
    val wRule  = parsedWeight.getOrElse(0.1)

    val newRx = arrow match {
      case "->" | "-->"  => rx.addEdge(from, to, transId, label, cond, updates, wTrans, aggType)
      case "->>"         => rx.addOn(from, to, transId, label, cond, updates, wRule, aggType)
      case "--!" | "--x" => rx.addOff(from, to, transId, label, cond, updates, wRule, aggType)
      case "---->"       => rx.addOn(from, to, transId, label, cond, updates, wRule, aggType).addOff(to, to, transId, label, cond, updates, wRule, aggType)
      case "--#--"       => rx.addOff(from, to, transId, label, cond, updates, wRule, aggType).addOff(to, from, transId, label, cond, updates, wRule, aggType)
      case _ => throw new RuntimeException(s"Unknown arrow: $arrow")
    }

    if (disabled) newRx.deactivate(from, to, transId, label) else newRx
  }

  private def parseCondition(reader: TokenReader): Condition = {
    def parseAtom(): Condition = {
      if (reader.eat("(")) { val c = parseCondition(reader); reader.expect(")"); c }
      else {
        val lhs = reader.parseQName()
        if (reader.eat("(")) {
            val label = reader.parseQName(); reader.expect(")")
            val op = reader.consume(); val v = reader.tryParseDouble()
            Condition.WeightCheck(label, lhs.show, op, v)
        } else {
            val op = reader.consume()
            val rhs = if (reader.current.matches("-?\\d+(\\.\\d+)?")) Left(reader.tryParseDouble()) else Right(reader.parseQName())
            AtomicCond(lhs, op, rhs)
        }
      }
    }

    var left = parseAtom()
    while (List("AND", "&&", "OR", "||").contains(reader.current)) {
      val op = reader.consume(); val right = parseAtom()
      if (op == "OR" || op == "||") left = Or(left, right) else left = And(left, right)
    }
    left
  }

  private def parseStatementsBlock(reader: TokenReader): List[Statement] = {
    var stmts = List.empty[Statement]
    while (reader.hasNext && reader.current != "}") {
        if (reader.eat("if")) {
            val c = parseCondition(reader); reader.expect("then"); reader.expect("{")
            val inner = parseStatementsBlock(reader); reader.expect("}")
            stmts = stmts :+ IfThenStmt(c, inner)
        } else stmts = stmts :+ parseUpdate(reader)
        reader.eat(";")
    }
    stmts
  }

  private def parseUpdate(reader: TokenReader): UpdateStmt = {
    val vRaw = reader.parseQName()
    if (!vRaw.n.last.endsWith("'")) throw new RuntimeException(s"Expected var', found ${vRaw.show}")
    val cleanName = QName(vRaw.n.init :+ vRaw.n.last.dropRight(1))
    reader.expect(":=")
    val first = reader.consume()
    val expr = if (first.matches("-?\\d+")) UpdateExpr.Lit(first.toInt) else UpdateExpr.Var(stringToQName(first))
    
    if (reader.current == "+" || reader.current == "-") {
      val op = reader.consume(); val second = reader.consume()
      val rhs = if (second.matches("-?\\d+")) Left(second.toInt) else Right(stringToQName(second))
      val vFromExpr = expr match {
        case UpdateExpr.Var(q) => q
        case _ => throw new RuntimeException("Complex expression not supported")
      }
      if (op == "+") UpdateStmt(CounterUpdate(cleanName, UpdateExpr.Add(vFromExpr, rhs)))
      else UpdateStmt(CounterUpdate(cleanName, UpdateExpr.Sub(vFromExpr, rhs)))
    } else UpdateStmt(CounterUpdate(cleanName, expr))
  }

  private def stringToQName(s: String): QName = if (s.contains(".")) QName(s.split('.').toList) else QName(List(s))

  def qname: Any = null

  def pp[A](parser: Any, str: String): Either[String, A] = {
    try {
      Right(stringToQName(str).asInstanceOf[A])
    } catch {
      case e: Throwable => Left(e.getMessage)
    }
  }
}