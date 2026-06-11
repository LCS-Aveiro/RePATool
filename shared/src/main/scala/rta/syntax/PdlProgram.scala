package rta.syntax
import rta.syntax.Program2.QName

sealed trait PdlProgram
object PdlProgram {
  case class Act(name: QName) extends PdlProgram                  // a
  case class Seq(p: PdlProgram, q: PdlProgram) extends PdlProgram        // p ; q
  case class Choice(p: PdlProgram, q: PdlProgram) extends PdlProgram     // p + q
  case class Star(p: PdlProgram) extends PdlProgram                   // p*
}
