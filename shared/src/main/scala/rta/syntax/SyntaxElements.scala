package rta.syntax

import rta.syntax.Program2.{QName, RxGraph}

sealed trait Statement
case class AssignStmt(variable: QName, expr: UpdateExpr) extends Statement {
  override def toString: String = s"${variable.show}' := ${UpdateExpr.show(expr)}"
}

case class ArrayAssignStmt(arrName: QName, index: UpdateExpr, expr: UpdateExpr) extends Statement {
  override def toString: String = s"${arrName.show}[${UpdateExpr.show(index)}]' := ${UpdateExpr.show(expr)}"
}

case class IfThenStmt(condition: Condition, thenStmts: List[Statement]) extends Statement

case class ForeachStmt(iteratorVar: QName, arrayName: QName, body: List[Statement]) extends Statement

case class ReturnStmt(expr: UpdateExpr) extends Statement
case class PrintStmt(expr: UpdateExpr) extends Statement


case class FunctionDef(name: QName, params: List[QName], body: List[Statement])

sealed trait RuntimeValue {
  def value: Any
}

object RuntimeValue {
  case class VInt(value: Int, min: Option[Int] = None, max: Option[Int] = None) extends RuntimeValue
  case class VFloat(value: Double, min: Option[Double] = None, max: Option[Double] = None) extends RuntimeValue
  case class VBool(value: Boolean) extends RuntimeValue
  case class VArray(value: List[RuntimeValue], isDynamic: Boolean, maxSize: Option[Int] = None) extends RuntimeValue {
    override def toString: String = "[" + value.map(_.value).mkString(", ") + "]"
  }
}


sealed trait Condition {
  def toMermaidString: String = this match {
    case Condition.AtomicCond(left, op, right) => s"${UpdateExpr.show(left)} $op ${UpdateExpr.show(right)}"
    case Condition.WeightCheck(lbl, metric, op, v) => s"$metric(${lbl.show}) $op $v"
    case Condition.And(c1, c2) => s"(${c1.toMermaidString} AND ${c2.toMermaidString})"
    case Condition.Or(c1, c2)  => s"(${c1.toMermaidString} OR ${c2.toMermaidString})"
  }
}

object Condition {
  case class AtomicCond(left: UpdateExpr, op: String, right: UpdateExpr) extends Condition
  
  case class WeightCheck(label: QName, metric: String, op: String, value: Double) extends Condition
  case class And(left: Condition, right: Condition) extends Condition
  case class Or(left: Condition, right: Condition) extends Condition

  private val epsilon = 1e-7

  def extractDouble(v: RuntimeValue): Double = v match {
    case RuntimeValue.VInt(i, _, _) => i.toDouble
    case RuntimeValue.VFloat(f, _, _) => f
    case RuntimeValue.VBool(b) => if (b) 1.0 else 0.0
    case _ => 0.0
  }

  def compareValues(leftVal: RuntimeValue, op: String, rightVal: RuntimeValue): Boolean = {
    if (leftVal.isInstanceOf[RuntimeValue.VBool] && rightVal.isInstanceOf[RuntimeValue.VBool]) {
      val b1 = leftVal.asInstanceOf[RuntimeValue.VBool].value
      val b2 = rightVal.asInstanceOf[RuntimeValue.VBool].value
      op match {
        case "==" | "=" => b1 == b2
        case "!=" => b1 != b2
        case _ => false
      }
    } else {
      val l = extractDouble(leftVal)
      val r = extractDouble(rightVal)
      op match {
        case ">=" => l >= r - epsilon
        case "<=" => l <= r + epsilon
        case ">"  => l > r + epsilon
        case "<"  => l < r - epsilon
        case "==" | "=" => Math.abs(l - r) < epsilon
        case "!=" => Math.abs(l - r) >= epsilon
        case _    => false
      }
    }
  }


}

sealed trait UpdateExpr

object UpdateExpr {
  case class LitInt(i: Int) extends UpdateExpr
  case class LitFloat(f: Double) extends UpdateExpr
  case class LitBool(b: Boolean) extends UpdateExpr
  case class LitArray(elements: List[UpdateExpr]) extends UpdateExpr

  case class Var(q: QName) extends UpdateExpr
  case class ArrayAccess(arr: QName, index: UpdateExpr) extends UpdateExpr

  case class MathOp(left: UpdateExpr, op: String, right: UpdateExpr) extends UpdateExpr
  
  case class FuncCall(funcName: QName, args: List[UpdateExpr]) extends UpdateExpr

  def show(expr: UpdateExpr): String = show(expr, _.show)

  def show(expr: UpdateExpr, s: QName => String): String = expr match {
    case LitInt(i) => i.toString
    case LitFloat(f) => f.toString
    case LitBool(b) => b.toString
    case LitArray(elems) => elems.map(e => show(e, s)).mkString("[", ", ", "]")
    case Var(q) => s(q)
    case ArrayAccess(arr, idx) => s"${s(arr)}[${show(idx, s)}]"
    case MathOp(l, op, r) => s"${show(l, s)} $op ${show(r, s)}"
    case FuncCall(f, args) => s"${s(f)}(${args.map(a => show(a, s)).mkString(", ")})"
  }
}

