package rta.backend

import rta.syntax.Program2.{Edge, Edges, QName, RxGraph}
import rta.syntax.{Condition, Statement, UpdateExpr, UpdateStmt, IfThenStmt,Aggregation}
import scala.annotation.tailrec

object RxSemantics {

  private val EPSILON = 0.00001
  private val INITIAL_SAMPLES = 50

  private def clamp(v: Double): Double = 
    Math.round(Math.max(0.0, Math.min(1.0, v)) * 1000.0) / 1000.0
  
  private def distributeWeights(source: QName, 
                                oldWeights: Map[Edge, Double], 
                                newWeights: Map[Edge, Double], 
                                modifiedEdges: Set[Edge], 
                                activeEdges: Edges, 
                                mode: String,
                                rx: RxGraph): Map[Edge, Double] = {
                                  
    val outgoingActive = rx.edg.getOrElse(source, Set.empty)
      .map(t => (source, t._1, t._2, t._3))
      .filter(activeEdges.contains)
                         
    if (outgoingActive.isEmpty) return newWeights

    var updatedWeights = newWeights
    val activeModified = modifiedEdges.filter(e => e._1 == source && activeEdges.contains(e))
    val unmodified = outgoingActive -- activeModified

    mode match {
      case "equal" | "proportional" if unmodified.nonEmpty =>
        val sMod = activeModified.toList.map(e => updatedWeights.getOrElse(e, 0.0)).sum

        if (sMod >= 1.0 - EPSILON) {
          for (e <- unmodified) updatedWeights += (e -> 0.0)
          if (sMod > EPSILON) {
            for (e <- activeModified) {
              updatedWeights += (e -> clamp(updatedWeights.getOrElse(e, 0.0) / sMod))
            }
          }
        } else {
          val targetUnmod = 1.0 - sMod
          
          if (mode == "proportional") {
            val sUnmod = unmodified.toList.map(e => oldWeights.getOrElse(e, 0.0)).sum
            if (sUnmod > EPSILON) {
              val scale = targetUnmod / sUnmod
              for (e <- unmodified) {
                updatedWeights += (e -> clamp(oldWeights.getOrElse(e, 0.0) * scale))
              }
            } else {
              val share = targetUnmod / unmodified.size
              for (e <- unmodified) updatedWeights += (e -> clamp(share))
            }
          } 
          else { // "equal"
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
                if (proposedW < 0.0) {
                  updatedWeights += (e -> 0.0)
                  clampedAny = true
                } else if (proposedW > 1.0) {
                  updatedWeights += (e -> 1.0)
                  remainingTarget -= 1.0
                  clampedAny = true
                } else {
                  nextUnmod += e
                }
              }
              
              if (!clampedAny) {
                for (e <- nextUnmod) {
                  updatedWeights += (e -> clamp(oldWeights.getOrElse(e, 0.0) + share))
                }
                done = true
              } else {
                remainingUnmod = nextUnmod
              }
            }
          }
        }

      case _ => // "normalize"
        val totalSum = outgoingActive.toList.map(e => updatedWeights.getOrElse(e, 0.0)).sum
        if (totalSum < EPSILON) {
          val uniform = 1.0 / outgoingActive.size
          for (e <- outgoingActive) updatedWeights += (e -> clamp(uniform))
        } else {
          for (e <- outgoingActive) {
            updatedWeights += (e -> clamp(updatedWeights.getOrElse(e, 0.0) / totalSum))
          }
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
        val conditionHolds = rx.edgeConditions.getOrElse(hyperEdge, None) match {
          case Some(cond) => Condition.evaluate(cond, rx.val_env)
          case None => true
        }

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
              toActivate += te
              dirtyStates += te._1
              modifiedEdges += te 
            }
            
            if (rx.off.getOrElse(triggerLabel, Set.empty).contains((targetLabel, ruleId, ruleLabel))) {
              toDeactivate += te
              dirtyStates += te._1
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

  def from(e: Edge, rx: RxGraph): Set[Edge] =
    cascade(Set(e._4), Set())(using rx)

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

  def toOnOff(e: Edge, rx: RxGraph): (Edges, Edges, Map[QName, Int]) = {
    val (toA, toD, stmts, _) = getHyperEdgeEffects(e, rx)
    (toA, toD, applyUpdates(stmts, rx))
  }

  def applyUpdates(stmts: List[Statement], rx: RxGraph): Map[QName, Int] = {

    val originalEnv = rx.val_env
    
    var nextUpdates = Map.empty[QName, Int]

    def eval(expr: UpdateExpr, lookupEnv: Map[QName, Int]): Int = expr match {
      case UpdateExpr.Lit(i)    => i
      case UpdateExpr.Var(q)    => lookupEnv.getOrElse(q, 0)
      case UpdateExpr.Add(v, e) => 
        lookupEnv.getOrElse(v, 0) + e.fold(identity, lookupEnv.getOrElse(_, 0))
      case UpdateExpr.Sub(v, e) => 
        lookupEnv.getOrElse(v, 0) - e.fold(identity, lookupEnv.getOrElse(_, 0))
    }

    def process(ss: List[Statement]): Unit = {
      for (s <- ss) s match {
        case UpdateStmt(u) => 
          val newValue = eval(u.expr, originalEnv)
          nextUpdates += (u.variable -> newValue)
          
        case IfThenStmt(c, t) => 
          if (Condition.evaluate(c, originalEnv)) process(t)
      }
    }

    process(stmts)
    
    originalEnv ++ nextUpdates
  }

  def nextEdge(rx: RxGraph): Set[(Edge, RxGraph)] = {
    val transitions = for {
      st <- rx.inits
      (st2, tid, lbl) <- rx.edg.getOrElse(st, Set.empty)
      edge = (st, st2, tid, lbl)
      if rx.act.contains(edge)
      if rx.edgeConditions.getOrElse(edge, None).forall(c => Condition.evaluate(c, rx.val_env))
    } yield {
      val (toAct, toDeact, hStmts, weightsAfterRules) = getHyperEdgeEffects(edge, rx)
      val currentAct = (rx.act ++ toAct) -- toDeact
      
      var nextEnv = applyUpdates(rx.edgeUpdates.getOrElse(edge, Nil) ++ hStmts, rx)
      var finalWeights = weightsAfterRules

      if (rx.trainingMode) {
        val sourceState = edge._1
        val sourceName = sourceState.show
        val oldW = rx.weights.getOrElse(edge, 1.0)

        if (rx.trainingMethod == "aggregation") {
          val updatedW = clamp(Aggregation.compute(rx.trainingAgg, oldW, rx.trainingLambda, oldW))
          finalWeights += (edge -> updatedW)
          
          finalWeights = distributeWeights(
            source = sourceState,
            oldWeights = rx.weights,
            newWeights = finalWeights,
            modifiedEdges = Set(edge), 
            activeEdges = currentAct,
            mode = rx.distributionMode,
            rx = rx
          )
        } else {
          val totalVisitsVar = QName(List(s"__total_$sourceName"))
          val currentTotal = rx.val_env.getOrElse(totalVisitsVar, INITIAL_SAMPLES)
          val newActualTotal = currentTotal + 1
          nextEnv += (totalVisitsVar -> newActualTotal)

          val eLabel = edge._4.show
          val eHitsVar = QName(List(s"__hits_${sourceName}_$eLabel"))
          val oldHits = rx.val_env.getOrElse(eHitsVar, (oldW * currentTotal).toInt)
          val updatedHits = oldHits + 1

          val forcedProb = clamp(updatedHits.toDouble / newActualTotal.toDouble)
          finalWeights += (edge -> forcedProb)

          finalWeights = distributeWeights(
            source = sourceState,
            oldWeights = rx.weights,
            newWeights = finalWeights,
            modifiedEdges = Set(edge),
            activeEdges = currentAct,
            mode = rx.distributionMode,
            rx = rx
          )

          val outgoingEdges = rx.edg.getOrElse(sourceState, Set.empty).map(t => (sourceState, t._1, t._2, t._3))
          for (e <- outgoingEdges) {
            val lbl = e._4.show
            val hitsVar = QName(List(s"__hits_${sourceName}_$lbl"))
            val newProb = finalWeights.getOrElse(e, 0.0)
            val backCalculatedHits = Math.round(newProb * newActualTotal).toInt
            nextEnv += (hitsVar -> backCalculatedHits)
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
    } else {
      transitions
    }
  }

  def next[Name >: QName](rx: RxGraph): Set[(Name, RxGraph)] =
    nextEdge(rx).map(e => e._1._4 -> e._2)
}