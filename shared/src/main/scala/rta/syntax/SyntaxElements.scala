package rta.syntax

import rta.syntax.Program2.{QName, RxGraph}

sealed trait Statement
case class UpdateStmt(update: CounterUpdate) extends Statement
case class IfThenStmt(condition: Condition, thenStmts: List[Statement]) extends Statement

sealed trait Condition {
  def toMermaidString: String = this match {
    case Condition.AtomicCond(left, op, Right(q)) => s"${left.show} $op ${q.show}"
    case Condition.AtomicCond(left, op, Left(i))  => s"${left.show} $op $i"
    case Condition.WeightCheck(lbl, metric, op, v) => s"$metric(${lbl.show}) $op $v"
    case Condition.And(c1, c2) => s"(${c1.toMermaidString} AND ${c2.toMermaidString})"
    case Condition.Or(c1, c2)  => s"(${c1.toMermaidString} OR ${c2.toMermaidString})"
  }
}

object Condition {
  case class AtomicCond(left: QName, op: String, right: Either[Double, QName]) extends Condition
  case class WeightCheck(label: QName, metric: String, op: String, value: Double) extends Condition
  case class And(left: Condition, right: Condition) extends Condition
  case class Or(left: Condition, right: Condition) extends Condition

  private val epsilon = 1e-7

  private def getValue(qname: QName, val_env: Map[QName, Int]): Double = {
    val_env.get(qname).map(_.toDouble).getOrElse {
      if (qname.n.size > 1) {
        val globalName = QName(List(qname.n.last))
        val_env.getOrElse(globalName, 0).toDouble
      } else 0.0
    }
  }

  def evaluate(condition: Condition, val_env: Map[QName, Int]): Boolean = condition match {
    case AtomicCond(left, op, right) =>
      val leftVal = getValue(left, val_env)
      val rightVal = right match {
        case Left(d) => d
        case Right(qname) => getValue(qname, val_env)
      }
      compare(leftVal, op, rightVal)
    case And(l, r) => evaluate(l, val_env) && evaluate(r, val_env)
    case Or(l, r)  => evaluate(l, val_env) || evaluate(r, val_env)
    case WeightCheck(_, _, _, _) => false 
  }

  def evaluate(condition: Condition, rx: RxGraph): Boolean = condition match {
    case WeightCheck(label, metric, op, threshold) =>
      val p = rx.act.find(_._4 == label).map(e => rx.weights.getOrElse(e, 1.0)).getOrElse(0.0)
      val calculated = metric.toLowerCase match {
        case "not_p" | "f" => 1.0 - p
        case _             => p
      }
      compare(calculated, op, threshold)
    case _ => evaluate(condition, rx.val_env) 
  }

  private def compare(leftVal: Double, op: String, rightVal: Double): Boolean = op match {
    case ">=" => leftVal >= rightVal - epsilon
    case "<=" => leftVal <= rightVal + epsilon
    case ">"  => leftVal > rightVal + epsilon
    case "<"  => leftVal < rightVal - epsilon
    case "==" | "=" => Math.abs(leftVal - rightVal) < epsilon
    case "!=" => Math.abs(leftVal - rightVal) >= epsilon
    case _    => false
  }
}

sealed trait UpdateExpr
object UpdateExpr {
  case class Lit(i: Int) extends UpdateExpr
  case class Var(q: QName) extends UpdateExpr
  case class Add(v: QName, e: Either[Int, QName]) extends UpdateExpr
  case class Sub(v: QName, e: Either[Int, QName]) extends UpdateExpr

  def show(expr: UpdateExpr): String = show(expr, _.show)

  def show(expr: UpdateExpr, s: QName => String): String = expr match {
    case Lit(i) => i.toString
    case Var(q) => s(q)
    case Add(v, Left(i))  => s"${s(v)} + $i"
    case Add(v, Right(q)) => s"${s(v)} + ${s(q)}"
    case Sub(v, Left(i))  => s"${s(v)} - $i"
    case Sub(v, Right(q)) => s"${s(v)} - ${s(q)}"
  }
}

case class CounterUpdate(variable: QName, expr: UpdateExpr) {
  override def toString: String = s"${variable.show}' := ${UpdateExpr.show(expr)}"
}