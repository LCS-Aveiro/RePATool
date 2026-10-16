package rta.backend

import rta.syntax.Program2.{Edge, Edges, QName, RxGraph, showEdge}
import rta.syntax.{Condition, Statement, UpdateExpr, AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt,PrintStmt, RuntimeValue, Aggregation}

object RxSemantics {

  private val EPSILON = 1e-9
  private val QUANTUM = 1e6
  private val returnKey = QName(List("__return"))
  private val edgeOrder: Ordering[Edge] = Ordering.by(showEdge)


  def clamp(v: Double): Double =
    Math.round(Math.max(0.0, Math.min(1.0, v)) * QUANTUM) / QUANTUM


  def clampExact(v: Double): Double = Math.max(0.0, Math.min(1.0, v))


  def labelWeight(lbl: QName, rx: RxGraph): Double = {
    val active = rx.lbls.getOrElse(lbl, Set.empty).filter(rx.act.contains)
    if (active.isEmpty) 0.0
    else {
      val local = active.filter(e => rx.inits.contains(e._1))
      val pick = (if (local.nonEmpty) local else active).min(using edgeOrder)
      rx.weights.getOrElse(pick, 1.0)
    }
  }

  private def metricValue(metric: String, p: Double): Double = metric.toLowerCase match {
    case "not_p" | "f" => 1.0 - p
    case _ => p
  }

  def evalExpr(expr: UpdateExpr, env: Map[QName, RuntimeValue], rx: RxGraph): RuntimeValue = expr match {
    case UpdateExpr.LitInt(i) => RuntimeValue.VInt(i)
    case UpdateExpr.LitFloat(f) => RuntimeValue.VFloat(f)
    case UpdateExpr.LitBool(b) => RuntimeValue.VBool(b)
    case UpdateExpr.LitArray(elems) => RuntimeValue.VArray(elems.map(e => evalExpr(e, env, rx)), isDynamic = true)

    case UpdateExpr.Var(q) => env.getOrElse(q, RuntimeValue.VInt(0))

    case UpdateExpr.ArrayAccess(arrName, idxExpr) =>
      val idx = Condition.extractDouble(evalExpr(idxExpr, env, rx)).toInt
      env.get(arrName) match {
        case Some(RuntimeValue.VArray(elems, _, _)) if idx >= 0 && idx < elems.size => elems(idx)
        case _ => RuntimeValue.VInt(0)
      }

    case UpdateExpr.MathOp(l, op, r) =>
      val leftVal = evalExpr(l, env, rx)
      val rightVal = evalExpr(r, env, rx)
      val isFloat = leftVal.isInstanceOf[RuntimeValue.VFloat] || rightVal.isInstanceOf[RuntimeValue.VFloat]
      val lD = Condition.extractDouble(leftVal)
      val rD = Condition.extractDouble(rightVal)

      op match {
        case "+" => if(isFloat) RuntimeValue.VFloat(lD + rD) else RuntimeValue.VInt((lD + rD).toInt)
        case "-" => if(isFloat) RuntimeValue.VFloat(lD - rD) else RuntimeValue.VInt((lD - rD).toInt)
        case "*" => if(isFloat) RuntimeValue.VFloat(lD * rD) else RuntimeValue.VInt((lD * rD).toInt)
        case "/" => if(rD != 0) { if(isFloat) RuntimeValue.VFloat(lD / rD) else RuntimeValue.VInt((lD / rD).toInt) } else RuntimeValue.VInt(0)
        case _ => RuntimeValue.VInt(0)
      }

    case UpdateExpr.FuncCall(funcName, args) =>
      val fName = funcName.show
      if (Set("P", "F", "not_p").contains(fName) && args.size == 1) {
        args.head match {
          case UpdateExpr.Var(lbl) => RuntimeValue.VFloat(metricValue(fName, labelWeight(lbl, rx)))
          case _ => RuntimeValue.VInt(0)
        }
      } else {
        rx.functions.get(funcName) match {
          case Some(funcDef) =>
            val evalArgs = args.map(a => evalExpr(a, env, rx))
            val localEnv = env ++ funcDef.params.zip(evalArgs)
            runSequential(funcDef.body, localEnv, rx).getOrElse(returnKey, RuntimeValue.VInt(0))
          case None => RuntimeValue.VInt(0)
        }
      }
  }

  def evalCondition(cond: Condition, rx: RxGraph): Boolean = evalCondition(cond, rx.val_env, rx)

  def evalCondition(cond: Condition, env: Map[QName, RuntimeValue], rx: RxGraph): Boolean = cond match {
    case Condition.AtomicCond(l, op, r) =>
      Condition.compareValues(evalExpr(l, env, rx), op, evalExpr(r, env, rx))
    case Condition.WeightCheck(label, metric, op, threshold) =>
      Condition.compareValues(RuntimeValue.VFloat(metricValue(metric, labelWeight(label, rx))), op, RuntimeValue.VFloat(threshold))
    case Condition.And(l, r) => evalCondition(l, env, rx) && evalCondition(r, env, rx)
    case Condition.Or(l, r) => evalCondition(l, env, rx) || evalCondition(r, env, rx)
  }

  private def guardHolds(e: Edge, rx: RxGraph): Boolean =
    rx.edgeConditions.getOrElse(e, None).forall(c => evalCondition(c, rx.val_env, rx))

  def isEnabled(e: Edge, rx: RxGraph): Boolean = rx.act.contains(e) && guardHolds(e, rx)


  private def assignWithBounds(env: Map[QName, RuntimeValue], q: QName, newVal: RuntimeValue): Map[QName, RuntimeValue] =
    env.get(q) match {
      case Some(RuntimeValue.VInt(_, minOpt, maxOpt)) =>
        val v = newVal match {
          case RuntimeValue.VInt(i, _, _) => i
          case RuntimeValue.VFloat(f, _, _) => f.toInt
          case _ => 0
        }
        val capped = maxOpt.map(Math.min(_, minOpt.map(Math.max(_, v)).getOrElse(v))).getOrElse(minOpt.map(Math.max(_, v)).getOrElse(v))
        env + (q -> RuntimeValue.VInt(capped, minOpt, maxOpt))
      case Some(RuntimeValue.VFloat(_, minOpt, maxOpt)) =>
        val v = newVal match {
          case RuntimeValue.VFloat(f, _, _) => f
          case RuntimeValue.VInt(i, _, _) => i.toDouble
          case _ => 0.0
        }
        val capped = maxOpt.map(Math.min(_, minOpt.map(Math.max(_, v)).getOrElse(v))).getOrElse(minOpt.map(Math.max(_, v)).getOrElse(v))
        env + (q -> RuntimeValue.VFloat(capped, minOpt, maxOpt))
      case _ => env + (q -> newVal)
    }


  private def assignElement(env: Map[QName, RuntimeValue], arrName: QName, idx: Int, value: RuntimeValue): Map[QName, RuntimeValue] =
    env.get(arrName) match {
      case Some(RuntimeValue.VArray(elems, isDyn, maxOpt)) =>
        if (idx >= 0 && idx < elems.length) env + (arrName -> RuntimeValue.VArray(elems.updated(idx, value), isDyn, maxOpt))
        else if (isDyn && idx == elems.length) {
          val newElems = elems :+ value
          env + (arrName -> RuntimeValue.VArray(maxOpt.map(m => newElems.takeRight(m)).getOrElse(newElems), isDyn, maxOpt))
        } else env
      case _ => env
    }


  def applyUpdates(stmts: List[Statement], rx: RxGraph): Map[QName, RuntimeValue] = runSequential(stmts, rx.val_env, rx)

  private def runSequential(stmts: List[Statement], env0: Map[QName, RuntimeValue], rx: RxGraph): Map[QName, RuntimeValue] = {
    var env = env0
    def process(ss: List[Statement]): Unit = {
      val it = ss.iterator
      while (it.hasNext && !env.contains(returnKey)) {
        it.next() match {
          case AssignStmt(v, expr) => env = assignWithBounds(env, v, evalExpr(expr, env, rx))
          case ArrayAssignStmt(arrName, idxExpr, valExpr) =>
            val idx = Condition.extractDouble(evalExpr(idxExpr, env, rx)).toInt
            env = assignElement(env, arrName, idx, evalExpr(valExpr, env, rx))
          case IfThenStmt(cond, thens) => if (evalCondition(cond, env, rx)) process(thens)
          case ForeachStmt(iter, arr, body) =>
            env.get(arr) match {
              case Some(RuntimeValue.VArray(elems, _, _)) =>
                val eIt = elems.iterator
                while (eIt.hasNext && !env.contains(returnKey)) {
                  env += (iter -> eIt.next())
                  process(body)
                }
                env -= iter
              case _ =>
            }
          case ReturnStmt(expr) => env += (returnKey -> evalExpr(expr, env, rx))
          case PrintStmt(expr) => println(s"🖨️ RePa Print | ${UpdateExpr.show(expr)} = ${evalExpr(expr, env, rx).value}")
        }
      }
    }
    process(stmts)
    env
  }

  def applyParallelUpdates(stmts: List[Statement], rx: RxGraph): Map[QName, RuntimeValue] = {
    if (stmts.isEmpty) return rx.val_env
    var out = rx.val_env
    def run(ss: List[Statement], read: Map[QName, RuntimeValue]): Unit = ss.foreach {
      case AssignStmt(v, expr) => out = assignWithBounds(out, v, evalExpr(expr, read, rx))
      case ArrayAssignStmt(arrName, idxExpr, valExpr) =>
        val idx = Condition.extractDouble(evalExpr(idxExpr, read, rx)).toInt
        out = assignElement(out, arrName, idx, evalExpr(valExpr, read, rx))
      case IfThenStmt(cond, thens) => if (evalCondition(cond, read, rx)) run(thens, read)
      case ForeachStmt(iter, arr, body) =>
        read.get(arr) match {
          case Some(RuntimeValue.VArray(elems, _, _)) => elems.foreach(el => run(body, read + (iter -> el)))
          case _ =>
        }
      case ReturnStmt(_) => ()
      case PrintStmt(expr) => println(s"🖨️ RePa Print | ${UpdateExpr.show(expr)} = ${evalExpr(expr, read, rx).value}")
    }
    run(stmts, rx.val_env)
    out
  }


  private def waterFill(edges: Seq[Edge], base: Edge => Double, target: Double, clamp: Double => Double): Map[Edge, Double] = {
    var out = Map.empty[Edge, Double]
    var remaining = edges
    var remainingTarget = target
    var done = false
    while (!done && remaining.nonEmpty) {
      val share = (remainingTarget - remaining.map(base).sum) / remaining.size
      var clampedAny = false
      val next = List.newBuilder[Edge]
      for (e <- remaining) {
        val proposed = base(e) + share
        if (proposed < 0.0) { out += (e -> 0.0); clampedAny = true }
        else if (proposed > 1.0) { out += (e -> 1.0); remainingTarget -= 1.0; clampedAny = true }
        else next += e
      }
      if (!clampedAny) {
        remaining.foreach(e => out += (e -> clamp(base(e) + share)))
        done = true
      } else remaining = next.result()
    }
    out
  }

  def revise(outgoing: Seq[Edge], modified: Edge => Boolean, old: Edge => Double, cur: Edge => Double, mode: String,
             clamp: Double => Double = clamp): Map[Edge, Double] = {
    if (outgoing.isEmpty) return Map.empty
    val activeModified = outgoing.filter(modified)
    val unmodified = outgoing.filterNot(modified)

    mode match {
      case "revise_equal" =>
        waterFill(outgoing, e => if (modified(e)) cur(e) else old(e), 1.0, clamp)

      case "equal" | "proportional" if unmodified.nonEmpty =>
        val sMod = activeModified.map(cur).sum
        if (sMod >= 1.0 - EPSILON) {
          unmodified.map(_ -> 0.0).toMap ++
            (if (sMod > EPSILON) activeModified.map(e => e -> clamp(cur(e) / sMod)) else activeModified.map(e => e -> cur(e)))
        } else {
          val targetUnmod = 1.0 - sMod
          val unmodW =
            if (mode == "proportional") {
              val sUnmod = unmodified.map(old).sum
              if (sUnmod > EPSILON) unmodified.map(e => e -> clamp(old(e) * targetUnmod / sUnmod)).toMap
              else unmodified.map(e => e -> clamp(targetUnmod / unmodified.size)).toMap
            } else waterFill(unmodified, old, targetUnmod, clamp)
          activeModified.map(e => e -> cur(e)).toMap ++ unmodW
        }

      case _ =>
        val total = outgoing.map(cur).sum
        if (total < EPSILON) outgoing.map(e => e -> clamp(1.0 / outgoing.size)).toMap
        else outgoing.map(e => e -> clamp(cur(e) / total)).toMap
    }
  }


  def activeOutgoing(state: QName, active: Edges, rx: RxGraph): List[Edge] =
    rx.edg.getOrElse(state, Set.empty).toList.map(t => (state, t._1, t._2, t._3)).filter(active.contains)

  private def reviseStates(states: Iterable[QName], old: Map[Edge, Double], cur: Map[Edge, Double], modified: Set[Edge],
                           active: Edges, rx: RxGraph): Map[Edge, Double] = {
    if (rx.paradigm == "fuzzy") return cur
    var w = cur
    for (state <- states) {
      val out = activeOutgoing(state, active, rx)
      w = w ++ revise(out, modified.contains, e => old.getOrElse(e, 0.0), e => w.getOrElse(e, 0.0), rx.distributionMode)
    }
    w
  }

  private def isGroundEdge(e: Edge, rx: RxGraph): Boolean =
    rx.edg.getOrElse(e._1, Set.empty).contains((e._2, e._3, e._4))


  def triggeredRules(e: Edge, rx: RxGraph): List[Edge] = {
    if (!rx.on.contains(e._4) && !rx.off.contains(e._4)) return Nil
    val out = List.newBuilder[Edge]
    var done = Set.empty[Edge]
    val queue = scala.collection.mutable.Queue[QName](e._4)
    var seenLabels = Set(e._4)
    while (queue.nonEmpty) {
      val curr = queue.dequeue()
      val rules = (rx.on.getOrElse(curr, Set.empty) ++ rx.off.getOrElse(curr, Set.empty)).toList
        .map(t => (curr, t._1, t._2, t._3))
        .filter(r => !done.contains(r) && rx.act.contains(r) && guardHolds(r, rx))
        .sorted(using edgeOrder)
      for (r <- rules) {
        done += r
        out += r
        if (r._4.n.nonEmpty && !seenLabels.contains(r._4)) { seenLabels += r._4; queue.enqueue(r._4) }
      }
    }
    out.result()
  }

  def from(e: Edge, rx: RxGraph): Set[Edge] = triggeredRules(e, rx).toSet

  private case class Effects(toActivate: Edges, toDeactivate: Edges, updates: List[Statement], weights: Map[Edge, Double])


  private def hyperEdgeEffects(e: Edge, rx: RxGraph): Effects = {
    val rules = triggeredRules(e, rx)
    if (rules.isEmpty) return Effects(Set.empty, Set.empty, Nil, rx.weights)

    var toActivate = Set.empty[Edge]
    var toDeactivate = Set.empty[Edge]
    val updates = List.newBuilder[Statement]
    var weights = rx.weights
    var dirtyStates = Set.empty[QName]
    var modified = Set.empty[Edge]
    val wSource = rx.weights.getOrElse(e, 1.0)
    val aggType = rx.edgeAggregations.getOrElse(e, "arith")

    for (rule <- rules) {
      val (triggerLabel, targetLabel, ruleId, ruleLabel) = rule
      updates ++= rx.edgeUpdates.getOrElse(rule, Nil)
      val wRule = rx.weights.getOrElse(rule, 0.1)
      val activates = rx.on.getOrElse(triggerLabel, Set.empty).contains((targetLabel, ruleId, ruleLabel))
      val deactivates = rx.off.getOrElse(triggerLabel, Set.empty).contains((targetLabel, ruleId, ruleLabel))
      for (te <- rx.lbls.getOrElse(targetLabel, Set.empty).toList.sorted(using edgeOrder)) {
        weights += (te -> clamp(Aggregation.compute(aggType, wSource, wRule, weights.getOrElse(te, 0.0))))
        modified += te
        if (isGroundEdge(te, rx)) dirtyStates += te._1
        if (activates) toActivate += te
        if (deactivates) toDeactivate += te
      }
    }
    val nextActive = (rx.act ++ toActivate) -- toDeactivate
    Effects(toActivate, toDeactivate, updates.result(), reviseStates(dirtyStates, rx.weights, weights, modified, nextActive, rx))
  }

  def toOnOff(e: Edge, rx: RxGraph): (Edges, Edges, Map[QName, RuntimeValue]) = {
    val fx = hyperEdgeEffects(e, rx)
    (fx.toActivate, fx.toDeactivate, applyParallelUpdates(fx.updates, rx))
  }


  def enabledEdges(rx: RxGraph): List[Edge] =
    rx.inits.toList.flatMap(st => activeOutgoing(st, rx.act, rx)).filter(e => guardHolds(e, rx)).sorted(using edgeOrder)

  def step(rx: RxGraph, edge: Edge): RxGraph = {
    val (st, st2, _, _) = edge
    if (!rx.trainingMode && rx.weightExprs.isEmpty && rx.edgeUpdates.getOrElse(edge, Nil).isEmpty &&
        !rx.on.contains(edge._4) && !rx.off.contains(edge._4))
      return rx.copy(inits = (rx.inits - st) + st2)
    val fx = hyperEdgeEffects(edge, rx)
    val currentAct = (rx.act ++ fx.toActivate) -- fx.toDeactivate
    val nextEnv = applyParallelUpdates(rx.edgeUpdates.getOrElse(edge, Nil) ++ fx.updates, rx)
    var finalWeights = fx.weights

    if (rx.weightExprs.nonEmpty && (nextEnv ne rx.val_env)) {
      val nextRx = rx.copy(val_env = nextEnv)
      var dirty = Set.empty[QName]
      var modified = Set.empty[Edge]
      for ((e, expr) <- rx.weightExprs) {
        val oldVal = Condition.extractDouble(evalExpr(expr, rx.val_env, rx))
        val newVal = Condition.extractDouble(evalExpr(expr, nextEnv, nextRx))
        if (Math.abs(oldVal - newVal) > EPSILON) {
          finalWeights += (e -> clamp(newVal))
          modified += e
          if (isGroundEdge(e, rx)) dirty += e._1
        }
      }
      finalWeights = reviseStates(dirty, fx.weights, finalWeights, modified, currentAct, rx)
    }

    var counts = rx.trainCounts
    if (rx.trainingMode) {
      val out = activeOutgoing(st, currentAct, rx)
      val (w, c) = Learning.update(rx, finalWeights, counts, out, edge)
      finalWeights = w; counts = c
    }

    rx.copy(
      inits = (rx.inits - st) + st2,
      act = currentAct,
      val_env = nextEnv,
      weights = finalWeights,
      trainCounts = counts
    )
  }

  def nextEdge(rx: RxGraph): Set[(Edge, RxGraph)] = {
    val enabled = enabledEdges(rx)
    if (enabled.isEmpty && rx.inits.nonEmpty && rx.paradigm != "fuzzy")
      rx.inits.map(st => ((st, st, QName(List("tau")), QName(List("deadlock"))), rx))
    else enabled.map(e => (e, step(rx, e))).toSet
  }


  def transitionProbabilities(rx: RxGraph, transitions: Iterable[(Edge, RxGraph)]): Map[Edge, Double] = {
    val edges = transitions.map(_._1).toList.distinct
    if (rx.paradigm == "fuzzy") return edges.map(e => e -> rx.weights.getOrElse(e, 1.0)).toMap
    edges.groupBy(_._1).flatMap { case (_, es) =>
      val ws = es.map(e => e -> rx.weights.getOrElse(e, 1.0))
      val total = ws.map(_._2).sum
      if (total > EPSILON) ws.map((e, w) => e -> w / total) else es.map(e => e -> 1.0 / es.size)
    }
  }
}

object Learning {

  val PRIOR_SAMPLES = 50.0


  def emaUpdate(gamma: Double, weight: Edge => Double, outgoing: Seq[Edge], fired: Edge): Map[Edge, Double] =
    outgoing.map(e => e -> RxSemantics.clampExact((1 - gamma) * weight(e) + (if (e == fired) gamma else 0.0))).toMap


  def aggregationUpdate(cfg: RxGraph, weight: Edge => Double, outgoing: Seq[Edge], fired: Edge): Map[Edge, Double] = {
    if (cfg.trainingAgg == "ema") return emaUpdate(cfg.trainingLambda, weight, outgoing, fired)
    val oldW = weight(fired)
    val newW = RxSemantics.clampExact(Aggregation.compute(cfg.trainingAgg, oldW, cfg.trainingLambda, oldW))
    if (cfg.paradigm == "fuzzy") Map(fired -> newW)
    else RxSemantics.revise(outgoing, _ == fired, weight, e => if (e == fired) newW else weight(e), cfg.distributionMode,
                            RxSemantics.clampExact)
  }


  def laplaceWeights(count: Edge => Double, support: Seq[Edge]): Map[Edge, Double] = {
    val total = support.map(count).sum
    if (total > 0) support.map(e => e -> RxSemantics.clampExact(count(e) / total)).toMap else Map.empty
  }

  def update(cfg: RxGraph, weights: Map[Edge, Double], counts: Map[Edge, Double], outgoing: Seq[Edge], fired: Edge)
      : (Map[Edge, Double], Map[Edge, Double]) = {
    if (cfg.trainingMethod == "aggregation" && cfg.trainingAgg != "mean") {
      if (!outgoing.contains(fired)) (weights, counts)
      else (weights ++ aggregationUpdate(cfg, e => weights.getOrElse(e, 0.0), outgoing, fired), counts)
    } else {
      val support = if (outgoing.contains(fired)) outgoing else outgoing :+ fired
      var c = counts
      for (e <- support if !c.contains(e)) c += (e -> weights.getOrElse(e, 0.0) * PRIOR_SAMPLES)
      c += (fired -> (c(fired) + 1.0))
      (weights ++ laplaceWeights(c, support), c)
    }
  }
}
