package rta.syntax

import rta.syntax.Formula.*
import rta.syntax.PdlProgram.*
import rta.syntax.Program2.QName
import rta.syntax.Condition

object PdlParser {

  def parsePdlFormula(str: String): Formula = {
    val tokens = tokenize(str)
    if (tokens.isEmpty) throw new RuntimeException("Formula cannot be empty")
    val reader = new TokenReader(tokens)
    val formula = parseFormula(reader)
    if (reader.hasNext) throw new RuntimeException(s"Tokens inesperados no fim: ${reader.current}")
    formula
  }

  private def tokenize(input: String): List[String] = {
    // Adicionamos as chaves '{' e '}'
    val pattern = """(=\?|<->|->|=>|&&|\|\||&\|&|\[\]|<>|==|!=|<=|>=|≤|≥|≠|[!~\[\]\(\)\{\};\+\*<>:=\?]|[a-zA-Z_][\w\.]*(\/[a-zA-Z_][\w\.]*)*|-?\d+(\.\d+)?)""".r
    pattern.findAllIn(input).toList
  }

  private class TokenReader(val tokens: List[String]) {
    var pos = 0
    def current: String = if (pos < tokens.length) tokens(pos) else ""
    def hasNext: Boolean = pos < tokens.length
    def consume(): String = { val t = current; pos += 1; t }
    def eat(s: String): Boolean = if (current == s) { pos += 1; true } else false
    def expect(s: String): Unit = if (!eat(s)) throw new RuntimeException(s"Esperado '$s', encontrado '$current'")
    def peekNext: String = if (pos + 1 < tokens.length) tokens(pos + 1) else ""
  }

  private def parseFormula(reader: TokenReader): Formula = parseIff(reader)

  private def parseIff(reader: TokenReader): Formula = {
    var left = parseImpl(reader)
    while (reader.current == "<->") { reader.consume(); left = Iff(left, parseImpl(reader)) }
    left
  }

  private def parseImpl(reader: TokenReader): Formula = {
    var left = parseOr(reader)
    if (reader.current == "->" || reader.current == "=>") { reader.consume(); left = Impl(left, parseImpl(reader)) }
    left
  }

  private def parseOr(reader: TokenReader): Formula = {
    var left = parseAnd(reader)
    while (reader.current == "||" || reader.current == "OR") { reader.consume(); left = Or(left, parseAnd(reader)) }
    left
  }

  private def parseAnd(reader: TokenReader): Formula = {
    var left = parsePipeAnd(reader)
    while (reader.current == "&&" || reader.current == "AND") { reader.consume(); left = And(left, parsePipeAnd(reader)) }
    left
  }

  private def parsePipeAnd(reader: TokenReader): Formula = {
    var left = parseUnary(reader)
    while (reader.current == "&|&") { reader.consume(); left = PipeAnd(left, parseUnary(reader)) }
    left
  }

  private def parseUnary(reader: TokenReader): Formula = {
    val t = reader.current
    
    // 1. CHAVES {} (Exclusivas para PCTL/Probabilidades)
    if (t == "{") {
      reader.consume() // consome '{'
      reader.expect("P")
      
      if (reader.eat("=?") || (reader.current == "=" && reader.peekNext == "?")) {
        if (reader.current == "=") { reader.consume(); reader.consume() }
        reader.expect("[")
        val path = parsePathFormula(reader)
        reader.expect("]")
        reader.expect("}")
        PQuantitative(path)
      } else {
        val op = reader.consume()
        val limit = reader.consume().toDouble
        reader.expect("[")
        val path = parsePathFormula(reader)
        reader.expect("]")
        reader.expect("}")
        PQualitative(op, limit, path)
      }
    }
    // 2. Negação e Modais Vazios
    else if (t == "!" || t == "~" || t == "¬") { reader.consume(); Not(parseUnary(reader)) }
    else if (t == "[]") { reader.consume(); Box(parseUnary(reader)) }
    else if (t == "<>") { reader.consume(); Diamond(parseUnary(reader)) }
    
    // 3. BOX PDL (Exclusivo para programas PDL)
    else if (t == "[") {
      reader.consume()
      val prog = parseProgram(reader)
      reader.expect("]")
      BoxP(prog, parseUnary(reader))
    }
    
    // 4. DIAMOND PDL (Exclusivo para programas PDL)
    else if (t == "<") {
      reader.consume()
      val prog = parseProgram(reader)
      reader.expect(">")
      DiamondP(prog, parseUnary(reader))
    }
    
    // 5. Átomos (Estados, Booleanos ou Condições de Variável)
    else {
      parseAtom(reader)
    }
  }

  private def parsePathFormula(reader: TokenReader): PathFormula = {
    val t = reader.current
    if (t == "X") { reader.consume(); PathFormula.Next(parseFormula(reader)) }
    else if (t == "F") { reader.consume(); PathFormula.Future(parseFormula(reader)) }
    else if (t == "G") { reader.consume(); PathFormula.Globally(parseFormula(reader)) }
    else {
      val left = parseFormula(reader)
      reader.expect("U")
      PathFormula.Until(left, parseFormula(reader))
    }
  }

  private def parseAtom(reader: TokenReader): Formula = {
    if (reader.eat("(")) { val f = parseFormula(reader); reader.expect(")"); f }
    else if (reader.eat("true")) True
    else if (reader.eat("false")) False
    else {

      val savePos = reader.pos
      val name = parseQName(reader)
      val op = reader.current
      val comparisonOps = Set("==", "!=", "<=", ">=", "<", ">", "=")
      
      if (comparisonOps.contains(op)) {
        reader.pos = savePos 
        CondProp(parseCondition(reader))
      } else {
        StateProp(name)
      }
    }
  }

  private def parseProgram(reader: TokenReader): PdlProgram = {
    var left = parseSeq(reader)
    while (reader.eat("+")) { left = Choice(left, parseSeq(reader)) }
    left
  }

  private def parseSeq(reader: TokenReader): PdlProgram = {
    var left = parseStar(reader)
    while (reader.eat(";")) { left = Seq(left, parseStar(reader)) }
    left
  }

  private def parseStar(reader: TokenReader): PdlProgram = {
    var prog = parseProgAtom(reader)
    while (reader.eat("*")) { prog = Star(prog) }
    prog
  }

  private def parseProgAtom(reader: TokenReader): PdlProgram = {
    if (reader.eat("(")) { val p = parseProgram(reader); reader.expect(")"); p }
    else {
      val name = parseQName(reader)
      if (reader.eat(":")) Act(name / reader.consume()) else Act(name)
    }
  }

  private def parseQName(reader: TokenReader): QName = {
    val s = reader.consume()
    if (s.contains("/")) QName(s.split('/').toList) else QName(s.split('.').toList)
  }

  private def parseCondition(reader: TokenReader): Condition = {
    val lhs = parseQName(reader)
    val op = reader.consume()
    val validOps = Set("==", "!=", "<=", ">=", "<", ">", "=")
    if (!validOps.contains(op)) throw new RuntimeException(s"Operador inválido: $op")
    val rhsToken = reader.consume()
    val rhs = if (rhsToken.matches("-?\\d+(\\.\\d+)?")) Left(rhsToken.toDouble) else Right(parseQName(reader))
    Condition.AtomicCond(lhs, op, rhs)
  }
}