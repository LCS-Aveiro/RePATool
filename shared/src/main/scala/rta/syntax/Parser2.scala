package rta.syntax

import rta.syntax.Program2.{RxGraph, QName, Edge}
import rta.syntax.{Condition, UpdateExpr, Statement, AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt,PrintStmt, FunctionDef, RuntimeValue}
import rta.syntax.Condition.*

object Parser2 {

  case class Token(value: String, line: Int, col: Int)

  class Lexer(input: String) {
    private var pos = 0
    private var line = 1
    private var col = 1

    private def peek: Char = if (pos < input.length) input(pos) else '\u0000'
    
    private def advance(): Char = {
      val c = peek
      pos += 1
      if (c == '\n') { line += 1; col = 1 } else { col += 1 }
      c
    }

    private def isSpace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
    private def isAlpha(c: Char) = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
    private def isDigit(c: Char) = c >= '0' && c <= '9'

    def scanAll(): List[Token] = {
      var tokens = List.empty[Token]
      while (pos < input.length) {
        val c = peek
        if (isSpace(c)) {
          advance()
        } else if (c == '/' && pos + 1 < input.length && input(pos + 1) == '/') {
          while (peek != '\n' && peek != '\u0000') advance()
        } else if (isAlpha(c)) {
          val startLine = line; val startCol = col
          var str = ""
          while (isAlpha(peek) || isDigit(peek) || (peek == '.' && pos + 1 < input.length && input(pos + 1) != '.')) {
            str += advance()
          }
          if (peek == '\'') str += advance()
          tokens = tokens :+ Token(str, startLine, startCol)
        } else if (isDigit(c) || (c == '-' && isDigit(if(pos+1 < input.length) input(pos+1) else '\u0000'))) {
          val startLine = line; val startCol = col
          var str = ""
          if (peek == '-') str += advance()
          while (isDigit(peek)) str += advance()
          if (peek == '.' && pos + 1 < input.length && isDigit(input(pos + 1))) {
            str += advance()
            while (isDigit(peek)) str += advance()
          }
          tokens = tokens :+ Token(str, startLine, startCol)
        } else {
          val startLine = line; val startCol = col
          var sym = advance().toString
          
          val longSymbols = List("--->", "->>", "--!", "--x", "->", "-->", "==", "!=", "<=", ">=", "&&", "||", ":=", "..")
          
          var matchedLong = false
          for (op <- longSymbols if !matchedLong) {
            val potential = sym + input.substring(pos, Math.min(input.length, pos + op.length - 1))
            if (potential == op) {
              sym = op
              for (_ <- 1 until op.length) advance()
              matchedLong = true
            }
          }
          tokens = tokens :+ Token(sym, startLine, startCol)
        }
      }
      tokens
    }
  }

  private class TokenReader(val tokens: List[Token]) {
    var pos = 0
    def current: String = if (pos < tokens.length) tokens(pos).value else ""
    def peekNext: String = if (pos + 1 < tokens.length) tokens(pos + 1).value else ""
    def hasNext: Boolean = pos < tokens.length
    def consume(): String = { val t = current; pos += 1; t }
    def eat(s: String): Boolean = { if (current == s) { pos += 1; true } else false }
    
    def expect(s: String): Unit = { 
      if (!eat(s)) {
        val t = if (pos < tokens.length) tokens(pos) else Token("FIM DO FICHEIRO", -1, -1)
        throw new RuntimeException(s"Erro na Linha ${t.line}, Coluna ${t.col}:\nEra esperado '$s', mas encontrou-se '${t.value}'.") 
      }
    }

    def parseQName(): QName = {
      val t = if (pos < tokens.length) tokens(pos) else Token("FIM DO FICHEIRO", -1, -1)
      val s = consume()
      if (s.isEmpty || s == ".") throw new RuntimeException(s"Erro na Linha ${t.line}: Identificador vazio.")
      val parts = s.split('.')
      for (part <- parts) {
        if (part == "_") throw new RuntimeException(s"Erro na Linha ${t.line}: '_' não é um nome válido para variável.")
        if (part.nonEmpty && part.head.isDigit) throw new RuntimeException(s"Erro na Linha ${t.line}: O nome '$part' não pode começar por um número.")
      }
      if (s.contains(".")) QName(s.split('.').toList) else QName(List(s))
    }
    
    def tryParseInt(): Int = consume().toInt
    def tryParseDouble(): Double = consume().toDouble
  }

  def parseProgram(str: String): RxGraph = {
    val lexer = new Lexer(str)
    val tokens = lexer.scanAll()
    val reader = new TokenReader(tokens)
    val rx = parseBlock(reader)
    autoInitializeWeights(rx)
  }

  private def autoInitializeWeights(rx: RxGraph): RxGraph = {
    val allEdges: Set[Edge] = (for { (src, targets) <- rx.edg.toSet; (trg, id, lbl) <- targets } yield (src, trg, id, lbl))
    val bySource = allEdges.groupBy(_._1)

    val transitionWeights: Map[Edge, Double] = bySource.flatMap { case (src, edges) =>
      val activeFromSource = edges.filter(rx.act.contains)
      val assigned: Map[Edge, Double] = edges.map { e => (e, rx.weights.getOrElse(e, 1.0)) }.toMap
      
      if (rx.paradigm == "fuzzy") {
        assigned.map { case (e, w) => e -> Math.max(0.0, Math.min(1.0, w)) }
      } else {
        val totalSum: Double = activeFromSource.toList.map(e => assigned(e)).sum
        if (totalSum > 0.0) assigned.map { (e, w) => if (rx.act.contains(e)) e -> (w / totalSum) else e -> w }
        else assigned
      }
    }.toMap

    val ruleEdges = (for { (s, ts) <- rx.on; (t, id, l) <- ts } yield (s, t, id, l)).toSet ++
                    (for { (s, ts) <- rx.off; (t, id, l) <- ts } yield (s, t, id, l)).toSet

    val ruleWeights = ruleEdges.map { e => if (rx.weights.contains(e)) e -> rx.weights(e) else e -> 0.1 }.toMap
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
             if (aggKeywords.contains(reader.current)) rx = rx.copy(trainingAgg = reader.consume())
             if (reader.eat("(")) { 
               val expr = parseExpr(reader)
               val evaluated = rta.backend.RxSemantics.evalExpr(expr, rx.val_env, rx)
               rx = rx.copy(trainingLambda = Condition.extractDouble(evaluated))
               reader.expect(")") 
             }
          }
        }
      }
      else if (reader.eat("calibration")) rx = rx.copy(distributionMode = reader.consume())
      else if (reader.eat("paradigm")) rx = rx.copy(paradigm = reader.consume())
      
      else if (Set("int", "float", "bool", "dyn").contains(reader.current)) {
        var isDyn = false
        if (reader.eat("dyn")) isDyn = true
        val typeStr = reader.consume() 
        var isArray = false
        if (reader.current == "[" && reader.peekNext == "]") {
            reader.consume(); reader.consume()
            isArray = true
        }
        val name = reader.parseQName()
        var minInt: Option[Int] = None; var maxInt: Option[Int] = None
        var minFloat: Option[Double] = None; var maxFloat: Option[Double] = None
        var maxSize: Option[Int] = None
        
        if (reader.eat("[")) {
            if (isArray) {
                reader.expect("max"); reader.expect("="); maxSize = Some(reader.tryParseInt())
            } else if (typeStr == "int") {
                minInt = Some(reader.tryParseInt()); reader.expect(".."); maxInt = Some(reader.tryParseInt())
            } else if (typeStr == "float") {
                minFloat = Some(reader.tryParseDouble()); reader.expect(".."); maxFloat = Some(reader.tryParseDouble())
            }
            reader.expect("]")
        }
        reader.expect("=")
        
        val rv: RuntimeValue = if (isArray) {
            reader.expect("[")
            var vals = List.empty[RuntimeValue]
            if (reader.current != "]") {
                def parseVal(): RuntimeValue = {
                    val expr = parseExpr(reader)
                    val evaluated = rta.backend.RxSemantics.evalExpr(expr, rx.val_env, rx)
                    if (typeStr == "int") RuntimeValue.VInt(Condition.extractDouble(evaluated).toInt)
                    else if (typeStr == "float") RuntimeValue.VFloat(Condition.extractDouble(evaluated))
                    else RuntimeValue.VBool(Condition.extractDouble(evaluated) != 0.0)
                }
                vals = vals :+ parseVal()
                while(reader.eat(",")) { vals = vals :+ parseVal() }
            }
            reader.expect("]")
            RuntimeValue.VArray(vals, isDyn, maxSize)
        } else {
            val expr = parseExpr(reader)
            val evaluated = rta.backend.RxSemantics.evalExpr(expr, rx.val_env, rx)
            if (typeStr == "int") {
                RuntimeValue.VInt(Condition.extractDouble(evaluated).toInt, minInt, maxInt)
            } else if (typeStr == "float") {
                RuntimeValue.VFloat(Condition.extractDouble(evaluated), minFloat, maxFloat)
            } else {
                RuntimeValue.VBool(Condition.extractDouble(evaluated) != 0.0)
            }
        }
        rx = rx.copy(val_env = rx.val_env + (name -> rv))
      }
      
      else if (reader.eat("def")) {
        val fName = reader.parseQName()
        reader.expect("(")
        var params = List.empty[QName]
        if (reader.current != ")") {
            params = params :+ reader.parseQName()
            while(reader.eat(",")) { params = params :+ reader.parseQName() }
        }
        reader.expect(")")
        reader.expect("{")
        val body = parseStatementsBlock(reader)
        reader.expect("}")
        rx = rx.copy(functions = rx.functions + (fName -> FunctionDef(fName, params, body)))
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

    if (reader.current == "-") { reader.consume(); transId = reader.parseQName(); arrow = reader.consume() } 
    else if (reader.current == "--->") { arrow = "->"; reader.consume() } 
    else { arrow = reader.consume() }

    val to = reader.parseQName()
    var label = QName(Nil)
    if (reader.eat(":")) label = reader.parseQName()
    if (label.n.isEmpty) label = QName(List(from.show + (if (transId.n.nonEmpty) transId.show else "tau") + to.show))
    if (transId.n.isEmpty) transId = label

    var parsedWeight: Option[Double] = None
    var parsedWeightExpr: Option[UpdateExpr] = None
    var aggType: String = ""
    
    if (reader.eat("(")) { 
      val expr = parseExpr(reader)
      parsedWeightExpr = Some(expr)
      val evaluated = rta.backend.RxSemantics.evalExpr(expr, rx.val_env, rx)
      parsedWeight = Some(Condition.extractDouble(evaluated))
      reader.expect(")") 
    }
    if (Set("arith", "prod", "max", "min", "geom").contains(reader.current)) aggType = reader.consume()

    var cond: Option[Condition] = None
    var updates: List[Statement] = Nil
    var disabled = false
    var parsingAttrs = true

    while (parsingAttrs && reader.hasNext) {
       val t = reader.current
       if (t == "disabled") { reader.consume(); disabled = true }
       else if (t == "if") {
         reader.consume(); cond = Some(parseCondition(reader))
         if (reader.eat("then")) { reader.expect("{"); updates = updates ::: parseStatementsBlock(reader); reader.expect("}") }
       } 
       else if (t == "then") {
         reader.consume()
         reader.expect("{")
         updates = updates ::: parseStatementsBlock(reader)
         reader.expect("}")
       }
       else if (t.endsWith("'") || reader.peekNext == ":=" || reader.peekNext == "[") {
         updates = updates :+ parseUpdate(reader)
       }
       else parsingAttrs = false
    }

    val wTrans = parsedWeight.getOrElse(1.0)
    val wRule  = parsedWeight.getOrElse(0.1)

    val newRx = arrow match {
      case "->" | "-->"  => rx.addEdge(from, to, transId, label, cond, updates, wTrans, aggType, parsedWeightExpr)
      case "->>"         => rx.addOn(from, to, transId, label, cond, updates, wRule, aggType, parsedWeightExpr)
      case "--!" | "--x" => rx.addOff(from, to, transId, label, cond, updates, wRule, aggType, parsedWeightExpr)
      case _ => throw new RuntimeException(s"Erro: Seta desconhecida '$arrow'.")
    }

    if (disabled) newRx.deactivate(from, to, transId, label) else newRx
  }

  private def parseExpr(reader: TokenReader): UpdateExpr = parseAddSub(reader)

  private def parseAddSub(reader: TokenReader): UpdateExpr = {
    var left = parseMulDiv(reader)
    while (reader.current == "+" || reader.current == "-") {
      val op = reader.consume()
      left = UpdateExpr.MathOp(left, op, parseMulDiv(reader))
    }
    left
  }

  private def parseMulDiv(reader: TokenReader): UpdateExpr = {
    var left = parsePrimary(reader)
    while (reader.current == "*" || reader.current == "/") {
      val op = reader.consume()
      left = UpdateExpr.MathOp(left, op, parsePrimary(reader))
    }
    left
  }

  private def parsePrimary(reader: TokenReader): UpdateExpr = {
    if (reader.eat("(")) {
      val e = parseExpr(reader); reader.expect(")"); e
    } else if (reader.eat("true")) { UpdateExpr.LitBool(true)
    } else if (reader.eat("false")) { UpdateExpr.LitBool(false)
    } else if (reader.eat("[")) {
      var elems = List.empty[UpdateExpr]
      if (reader.current != "]") { 
          elems = elems :+ parseExpr(reader)
          while(reader.eat(",")) { elems = elems :+ parseExpr(reader) } 
      }
      reader.expect("]")
      UpdateExpr.LitArray(elems)
    } else if (reader.current.matches("-?\\d+\\.\\d+")) { UpdateExpr.LitFloat(reader.tryParseDouble())
    } else if (reader.current.matches("-?\\d+")) { UpdateExpr.LitInt(reader.tryParseInt())
    } else {
      val q = reader.parseQName()
      if (reader.eat("(")) { 
         var args = List.empty[UpdateExpr]
         if (reader.current != ")") { 
             args = args :+ parseExpr(reader)
             while(reader.eat(",")) { args = args :+ parseExpr(reader) } 
         }
         reader.expect(")")
         UpdateExpr.FuncCall(q, args)
      } else if (reader.eat("[")) { 
         val idx = parseExpr(reader); reader.expect("]")
         UpdateExpr.ArrayAccess(q, idx)
      } else {
         UpdateExpr.Var(q) 
      }
    }
  }

  private def parseCondition(reader: TokenReader): Condition = {
    def parseAtom(): Condition = {
      if (reader.eat("(")) { val c = parseCondition(reader); reader.expect(")"); c }
      else {
        val lhs = parseExpr(reader)
        val op = reader.consume()
        if (Set("==", "!=", "<=", ">=", "<", ">", "=").contains(op)) {
           val rhs = parseExpr(reader)
           lhs match {
             case UpdateExpr.FuncCall(metric, List(UpdateExpr.Var(lbl))) if Set("P", "F", "not_p").contains(metric.show) =>
                 rhs match {
                   case UpdateExpr.LitFloat(d) => Condition.WeightCheck(lbl, metric.show, op, d)
                   case UpdateExpr.LitInt(i) => Condition.WeightCheck(lbl, metric.show, op, i.toDouble)
                   case _ => AtomicCond(lhs, op, rhs)
                 }
               case _ => AtomicCond(lhs, op, rhs)
             }
        } else throw new RuntimeException(s"Operador de comparação inválido '$op'.\n[ Perto de: ... ${reader.tokens.slice(Math.max(0, reader.pos-4), reader.pos+4).map(_.value).mkString(" ")} ... ]")
      }
    }

    var left = parseAtom()
    while (List("AND", "&&", "OR", "||").contains(reader.current)) {
      val op = reader.consume()
      val right = parseAtom()
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
        } else if (reader.eat("foreach")) {
            reader.expect("(")
            val iter = reader.parseQName()
            reader.expect("in")
            val arr = reader.parseQName()
            reader.expect(")")
            reader.expect("{")
            val body = parseStatementsBlock(reader)
            reader.expect("}")
            stmts = stmts :+ ForeachStmt(iter, arr, body)
        } else if (reader.eat("return")) {
            stmts = stmts :+ ReturnStmt(parseExpr(reader))
        } else if (reader.eat("print")) { 
            reader.expect("(")
            val expr = parseExpr(reader)
            reader.expect(")")
            stmts = stmts :+ PrintStmt(expr)
        } else {
            stmts = stmts :+ parseUpdate(reader)
        }
        reader.eat(";")
    }
    stmts
  }

  private def parseUpdate(reader: TokenReader): Statement = {
    val qRaw = reader.parseQName()
    
    val q = if (qRaw.n.last.endsWith("'")) QName(qRaw.n.init :+ qRaw.n.last.dropRight(1)) else qRaw
    
    if (reader.eat("[")) {
      val idx = parseExpr(reader)
      reader.expect("]")
      if (reader.current == "'") reader.consume() 
      reader.expect(":=")
      ArrayAssignStmt(q, idx, parseExpr(reader))
    } else {
      reader.expect(":=")
      AssignStmt(q, parseExpr(reader))
    }
  }

  def stringToQName(s: String): QName = if (s.contains(".")) QName(s.split('.').toList) else QName(List(s))
  def qname: Any = null
  def pp[A](parser: Any, str: String): Either[String, A] = try { Right(stringToQName(str).asInstanceOf[A]) } catch { case e: Throwable => Left(e.getMessage) }
}