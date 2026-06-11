// src/test/scala/TestParser.scala

import munit.FunSuite
import rta.syntax.Program2.*
import rta.syntax.Parser2.program

class TestParser extends FunSuite {

  
    val input = "init s0\ns0 --> s1: a"
    val obtained = program.parseAll(input)
  

}