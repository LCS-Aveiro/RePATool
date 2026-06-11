package rta.syntax
import rta.syntax.PdlProgram
import rta.syntax.Program2.QName
import rta.syntax.Condition.*

sealed trait PathFormula
object PathFormula {
  case class Next(f: Formula) extends PathFormula                 // X p
  case class Future(f: Formula) extends PathFormula               // F p
  case class Globally(f: Formula) extends PathFormula             // G p
  case class Until(left: Formula, right: Formula) extends PathFormula // p U q
}


sealed trait Formula
object Formula {
  case object True extends Formula
  case object False extends Formula

  case class PQuantitative(path: PathFormula) extends Formula                       // P=? [ path ]
  case class PQualitative(op: String, limit: Double, path: PathFormula) extends Formula // P>=0.5 [ path ]

  case class ProbProp(prog: PdlProgram, op: String, threshold: Double, f: Formula) extends Formula // Alternativa (Ex: [action] >= 0.5 target)
  case class StateProp(name: QName) extends Formula      // Representa uma verificação de estado, ex: "s1"
  case class CondProp(cond: Condition) extends Formula // Representa uma verificação de condição, ex: "[c < 2]"
  case class Not(p: Formula) extends Formula
  case class And(p: Formula, q: Formula) extends Formula
  case class Or(p: Formula, q: Formula) extends Formula
  case class Impl(p: Formula, q: Formula) extends Formula
  case class Iff(p: Formula, q: Formula) extends Formula

  case class PipeAnd(p: Formula, q: Formula) extends Formula // Nosso novo operador '&|&'
  case class Box(p: Formula) extends Formula
  case class Diamond(p: Formula) extends Formula


  case class BoxP(act: PdlProgram, p: Formula) extends Formula      // [α]φ
  case class DiamondP(act: PdlProgram, p: Formula) extends Formula  // <α>φ

  



}
