package rta.backend

import rta.syntax.Program2.{Edge, Edges, QName, RxGraph}
import rta.syntax.{Condition, Statement, UpdateExpr, AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt,PrintStmt, RuntimeValue, Aggregation}
import scala.annotation.tailrec

object RxSemantics {

  private val EPSILON = 0.00001
  private val INITIAL_SAMPLES = 50

  private def clamp(v: Double): Double = 
    Math.round(Math.max(0.0, Math.min(1.0, v)) * 1000.0) / 1000.0


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
          case UpdateExpr.Var(lbl) =>
            val p = rx.act.find(_._4 == lbl).map(e => rx.weights.getOrElse(e, 1.0)).getOrElse(0.0)
            val calculated = fName.toLowerCase match {
              case "not_p" | "f" => 1.0 - p
              case _ => p
            }
            RuntimeValue.VFloat(calculated)
          case _ => RuntimeValue.VInt(0)
        }
      } else {
        rx.functions.get(funcName) match {
          case Some(funcDef) =>
            val evalArgs = args.map(a => evalExpr(a, env, rx))
            var localEnv = env
            funcDef.params.zip(evalArgs).foreach { case (param, v) => localEnv += (param -> v) }
            val finalEnv = applyUpdates(funcDef.body, rx.copy(val_env = localEnv))
            finalEnv.getOrElse(QName(List("__return")), RuntimeValue.VInt(0))
          case None => RuntimeValue.VInt(0)
        }
      }
  }

  def evalCondition(cond: Condition, rx: RxGraph): Boolean = cond match {
    case Condition.AtomicCond(l, op, r) =>
      Condition.compareValues(evalExpr(l, rx.val_env, rx), op, evalExpr(r, rx.val_env, rx))
    case Condition.WeightCheck(label, metric, op, threshold) =>
      val p = rx.act.find(_._4 == label).map(e => rx.weights.getOrElse(e, 1.0)).getOrElse(0.0)
      val calculated = metric.toLowerCase match {
        case "not_p" | "f" => 1.0 - p
        case _ => p
      }
      Condition.compareValues(RuntimeValue.VFloat(calculated), op, RuntimeValue.VFloat(threshold))
    case Condition.And(l, r) => evalCondition(l, rx) && evalCondition(r, rx)
    case Condition.Or(l, r) => evalCondition(l, rx) || evalCondition(r, rx)
  }

  def applyUpdates(stmts: List[Statement], rx: RxGraph): Map[QName, RuntimeValue] = {
    var currentEnv = rx.val_env
    val returnKey = QName(List("__return"))

    def assignWithBounds(q: QName, newVal: RuntimeValue): Unit = {
      val existing = currentEnv.get(q)
      existing match {
        case Some(RuntimeValue.VInt(_, minOpt, maxOpt)) =>
          val v = newVal match {
            case RuntimeValue.VInt(i, _, _) => i
            case RuntimeValue.VFloat(f, _, _) => f.toInt
            case _ => 0
          }
          val cappedMin = minOpt.map(m => Math.max(m, v)).getOrElse(v)
          val finalVal = maxOpt.map(m => Math.min(m, cappedMin)).getOrElse(cappedMin)
          currentEnv += (q -> RuntimeValue.VInt(finalVal, minOpt, maxOpt))
        case Some(RuntimeValue.VFloat(_, minOpt, maxOpt)) =>
          val v = newVal match {
            case RuntimeValue.VFloat(f, _, _) => f
            case RuntimeValue.VInt(i, _, _) => i.toDouble
            case _ => 0.0
          }
          val cappedMin = minOpt.map(m => Math.max(m, v)).getOrElse(v)
          val finalVal = maxOpt.map(m => Math.min(m, cappedMin)).getOrElse(cappedMin)
          currentEnv += (q -> RuntimeValue.VFloat(finalVal, minOpt, maxOpt))
        case _ => currentEnv += (q -> newVal)
      }
    }

    def process(ss: List[Statement]): Unit = {
      val it = ss.iterator
      while (it.hasNext && !currentEnv.contains(returnKey)) {
        it.next() match {
          case AssignStmt(v, expr) =>
            val evaluated = evalExpr(expr, currentEnv, rx)
            assignWithBounds(v, evaluated)

          case ArrayAssignStmt(arrName, idxExpr, valExpr) =>
            val idx = Condition.extractDouble(evalExpr(idxExpr, currentEnv, rx)).toInt
            val value = evalExpr(valExpr, currentEnv, rx)
            currentEnv.get(arrName) match {
              case Some(RuntimeValue.VArray(elems, isDyn, maxOpt)) =>
                if (idx >= 0 && idx < elems.length) {
                  currentEnv += (arrName -> RuntimeValue.VArray(elems.updated(idx, value), isDyn, maxOpt))
                } else if (isDyn && idx == elems.length) {
                  val newElems = elems :+ value
                  currentEnv += (arrName -> RuntimeValue.VArray(maxOpt.map(m => newElems.takeRight(m)).getOrElse(newElems), isDyn, maxOpt))
                }
              case _ =>
            }

          case IfThenStmt(cond, thens) =>
            if (evalCondition(cond, rx.copy(val_env = currentEnv))) process(thens)

          case ForeachStmt(iter, arr, body) =>
            currentEnv.get(arr) match {
              case Some(RuntimeValue.VArray(elems, _, _)) =>
                val eIt = elems.iterator
                while(eIt.hasNext && !currentEnv.contains(returnKey)) {
                  currentEnv += (iter -> eIt.next())
                  process(body)
                }
                currentEnv -= iter
              case _ =>
            }

          case ReturnStmt(expr) =>
            currentEnv += (returnKey -> evalExpr(expr, currentEnv, rx))

          case PrintStmt(expr) =>
            val evaluated = evalExpr(expr, currentEnv, rx)
            println(s"🖨️ RePa Print | ${UpdateExpr.show(expr)} = ${evaluated.value}")
        }
      }
    }

    process(stmts)
    currentEnv
  }

  
  private def distributeWeights(source: QName, oldWeights: Map[Edge, Double], newWeights: Map[Edge, Double], modifiedEdges: Set[Edge], activeEdges: Edges, mode: String, rx: RxGraph): Map[Edge, Double] = {
    if (rx.paradigm == "fuzzy") return newWeights
    val outgoingActive = rx.edg.getOrElse(source, Set.empty).map(t => (source, t._1, t._2, t._3)).filter(activeEdges.contains)
    if (outgoingActive.isEmpty) return newWeights

    var updatedWeights = newWeights
    val activeModified = modifiedEdges.filter(e => e._1 == source && activeEdges.contains(e))
    val unmodified = outgoingActive -- activeModified

    mode match {
      case "revise_equal" =>
        var remainingTarget = 1.0
        var remainingEdges = outgoingActive
        val currentW = outgoingActive.map(e => e -> (if (activeModified.contains(e)) updatedWeights.getOrElse(e, 0.0) else oldWeights.getOrElse(e, 0.0))).toMap
        
        var done = false
        while (!done && remainingEdges.nonEmpty) {
          val currentSum = remainingEdges.toList.map(currentW).sum
          val diff = remainingTarget - currentSum
          val share = diff / remainingEdges.size
          var clampedAny = false
          var nextRemaining = Set.empty[Edge]
          
          for (e <- remainingEdges) {
            val proposed = currentW(e) + share
            if (proposed < 0.0) { updatedWeights += (e -> 0.0); clampedAny = true }
            else if (proposed > 1.0) { updatedWeights += (e -> 1.0); remainingTarget -= 1.0; clampedAny = true }
            else { nextRemaining += e }
          }
          if (!clampedAny) {
            for (e <- nextRemaining) updatedWeights += (e -> clamp(currentW(e) + share))
            done = true
          } else {
            remainingEdges = nextRemaining
          }
        }
      case "equal" | "proportional" if unmodified.nonEmpty =>
        val sMod = activeModified.toList.map(e => updatedWeights.getOrElse(e, 0.0)).sum
        if (sMod >= 1.0 - EPSILON) {
          for (e <- unmodified) updatedWeights += (e -> 0.0)
          if (sMod > EPSILON) for (e <- activeModified) updatedWeights += (e -> clamp(updatedWeights.getOrElse(e, 0.0) / sMod))
        } else {
          val targetUnmod = 1.0 - sMod
          if (mode == "proportional") {
            val sUnmod = unmodified.toList.map(e => oldWeights.getOrElse(e, 0.0)).sum
            if (sUnmod > EPSILON) {
              val scale = targetUnmod / sUnmod
              for (e <- unmodified) updatedWeights += (e -> clamp(oldWeights.getOrElse(e, 0.0) * scale))
            } else {
              val share = targetUnmod / unmodified.size
              for (e <- unmodified) updatedWeights += (e -> clamp(share))
            }
          } 
          else {
            var remainingUnmod = unmodified
            var remainingTarget = targetUnmod
            var done = false
            while (!done && remainingUnmod.nonEmpty) {
              val sUnmod = remainingUnmod.toList.map(e => oldWeights.getOrElse(e, 0.0)).sum
              val diff = remainingTarget - sUnmod
              val share = diff / remainingUnmod.size
              var clampedAny = false
              var nextUnmod = Set.empty[Edge]
              for (e <- remainingUnmod) {
                val proposedW = oldWeights.getOrElse(e, 0.0) + share
                if (proposedW < 0.0) { updatedWeights += (e -> 0.0); clampedAny = true } 
                else if (proposedW > 1.0) { updatedWeights += (e -> 1.0); remainingTarget -= 1.0; clampedAny = true } 
                else { nextUnmod += e }
              }
              if (!clampedAny) {
                for (e <- nextUnmod) updatedWeights += (e -> clamp(oldWeights.getOrElse(e, 0.0) + share))
                done = true
              } else { remainingUnmod = nextUnmod }
            }
          }
        }
      case _ =>
        val totalSum = outgoingActive.toList.map(e => updatedWeights.getOrElse(e, 0.0)).sum
        if (totalSum < EPSILON) {
          val uniform = 1.0 / outgoingActive.size
          for (e <- outgoingActive) updatedWeights += (e -> clamp(uniform))
        } else {
          for (e <- outgoingActive) updatedWeights += (e -> clamp(updatedWeights.getOrElse(e, 0.0) / totalSum))
        }
    }
    updatedWeights
  }

  private def getHyperEdgeEffects(e: Edge, rx: RxGraph): (Edges, Edges, List[Statement], Map[Edge, Double]) = {
    val triggeredHyperEdges = from(e, rx)
    var toActivate = Set.empty[Edge]
    var toDeactivate = Set.empty[Edge]
    var updatesToApply = List.empty[Statement]
    var currentWeights = rx.weights
    var dirtyStates = Set.empty[QName]
    var modifiedEdges = Set.empty[Edge] 
    val wSource = rx.weights.getOrElse(e, 1.0)
    val aggType = rx.edgeAggregations.getOrElse(e, "arith")

    for (hyperEdge <- triggeredHyperEdges) {
      val (triggerLabel, targetLabel, ruleId, ruleLabel) = hyperEdge
      if (rx.act.contains(hyperEdge)) {
        val conditionHolds = rx.edgeConditions.getOrElse(hyperEdge, None).forall(c => evalCondition(c, rx))

        if (conditionHolds) {
          updatesToApply = updatesToApply ::: rx.edgeUpdates.getOrElse(hyperEdge, Nil)
          val wRule = rx.weights.getOrElse(hyperEdge, 0.1)
          val affectedEdges = rx.lbls.getOrElse(targetLabel, Set.empty)

          for (te <- affectedEdges) {
            val wTarget = currentWeights.getOrElse(te, 0.0)
            val newW = Aggregation.compute(aggType, wSource, wRule, wTarget)
            currentWeights += (te -> clamp(newW))
            dirtyStates += te._1
            modifiedEdges += te 
            
            if (rx.on.getOrElse(triggerLabel, Set.empty).contains((targetLabel, ruleId, ruleLabel))) {
              toActivate += te; dirtyStates += te._1; modifiedEdges += te 
            }
            if (rx.off.getOrElse(triggerLabel, Set.empty).contains((targetLabel, ruleId, ruleLabel))) {
              toDeactivate += te; dirtyStates += te._1
            }
          }
        }
      }
    }
    val nextActiveSet = (rx.act ++ toActivate) -- toDeactivate
    for (state <- dirtyStates) {
      currentWeights = distributeWeights(state, rx.weights, currentWeights, modifiedEdges, nextActiveSet, rx.distributionMode, rx)
    }
    (toActivate, toDeactivate, updatesToApply, currentWeights)
  }

  def from(e: Edge, rx: RxGraph): Set[Edge] = cascade(Set(e._4), Set())(using rx)

  @tailrec
  private def cascade(pending: Set[QName], done: Set[Edge])(using rx: RxGraph): Edges = {
    if (pending.isEmpty) done
    else {
      val curr = pending.head
      val rulesOn = rx.on.getOrElse(curr, Set.empty).map(t => (curr, t._1, t._2, t._3))
      val rulesOff = rx.off.getOrElse(curr, Set.empty).map(t => (curr, t._1, t._2, t._3))
      val newRules = (rulesOn ++ rulesOff).filter(rx.act.contains) -- done
      cascade(pending.tail ++ newRules.map(_._4).filter(_.n.nonEmpty), done ++ newRules)
    }
  }

  def toOnOff(e: Edge, rx: RxGraph): (Edges, Edges, Map[QName, RuntimeValue]) = {
    val (toA, toD, stmts, _) = getHyperEdgeEffects(e, rx)
    (toA, toD, applyUpdates(stmts, rx))
  }

  def nextEdge(rx: RxGraph): Set[(Edge, RxGraph)] = {
    val transitions = for {
      st <- rx.inits
      (st2, tid, lbl) <- rx.edg.getOrElse(st, Set.empty)
      edge = (st, st2, tid, lbl)
      if rx.act.contains(edge)
      if rx.edgeConditions.getOrElse(edge, None).forall(c => evalCondition(c, rx))
    } yield {
      val (toAct, toDeact, hStmts, weightsAfterRules) = getHyperEdgeEffects(edge, rx)
      val currentAct = (rx.act ++ toAct) -- toDeact
      
      var nextEnv = applyUpdates(rx.edgeUpdates.getOrElse(edge, Nil) ++ hStmts, rx)
      var finalWeights = weightsAfterRules

      var dirtyStatesForVars = Set.empty[QName]
      var modifiedEdgesForVars = Set.empty[Edge]

      for ((e, expr) <- rx.weightExprs) {
        val oldVal = Condition.extractDouble(evalExpr(expr, rx.val_env, rx))
        val newVal = Condition.extractDouble(evalExpr(expr, nextEnv, rx.copy(val_env = nextEnv)))
        if (Math.abs(oldVal - newVal) > EPSILON) {
          finalWeights += (e -> clamp(newVal))
          dirtyStatesForVars += e._1
          modifiedEdgesForVars += e
        }
      }

      for (state <- dirtyStatesForVars) {
        finalWeights = distributeWeights(state, weightsAfterRules, finalWeights, modifiedEdgesForVars, currentAct, rx.distributionMode, rx)
      }

      if (rx.trainingMode) {
        val sourceState = edge._1
        val sourceName = sourceState.show
        val preTrainW = weightsAfterRules.getOrElse(edge, rx.weights.getOrElse(edge, 1.0))

        if (rx.trainingMethod == "aggregation") {
          val updatedW = clamp(Aggregation.compute(rx.trainingAgg, preTrainW, rx.trainingLambda, preTrainW))
          finalWeights += (edge -> updatedW)
          finalWeights = distributeWeights(sourceState, weightsAfterRules, finalWeights, Set(edge), currentAct, rx.distributionMode, rx)
        } else {
          val totalVisitsVar = QName(List(s"__total_$sourceName"))
          val currentTotal = nextEnv.get(totalVisitsVar).map(Condition.extractDouble).getOrElse(INITIAL_SAMPLES.toDouble).toLong
          val newActualTotal = currentTotal + 1
          nextEnv += (totalVisitsVar -> RuntimeValue.VInt(newActualTotal.toInt))

          val oldHits = Math.round(preTrainW * currentTotal).toInt
          val updatedHits = oldHits + 1
          val forcedProb = clamp(updatedHits.toDouble / newActualTotal.toDouble)
          finalWeights += (edge -> forcedProb)

          val hitRatios = currentAct.map { e =>
             val wAfter = weightsAfterRules.getOrElse(e, 0.0)
             val h = Math.round(wAfter * currentTotal).toInt
             e -> clamp(h.toDouble / newActualTotal.toDouble)
          }.toMap

          finalWeights = distributeWeights(sourceState, hitRatios, finalWeights, Set(edge), currentAct, rx.distributionMode, rx)

          val outgoingEdges = rx.edg.getOrElse(sourceState, Set.empty).map(t => (sourceState, t._1, t._2, t._3))
          for (e <- outgoingEdges) {
            val lbl = e._4.show
            val hitsVar = QName(List(s"__hits_${sourceName}_$lbl"))
            val newProb = finalWeights.getOrElse(e, 0.0)
            val backCalculatedHits = Math.round(newProb * newActualTotal).toInt
            nextEnv += (hitsVar -> RuntimeValue.VInt(backCalculatedHits))
          }
        }
      }

      (edge, rx.copy(
        inits = (rx.inits - st) + st2,
        act = currentAct,
        val_env = nextEnv,
        weights = finalWeights
      ))
    }

    if (transitions.isEmpty && rx.inits.nonEmpty) {
      rx.inits.map { st =>
        val edge: Edge = (st, st, QName(List("tau")), QName(List("deadlock")))
        (edge, rx)
      }
    } else transitions
  }
}