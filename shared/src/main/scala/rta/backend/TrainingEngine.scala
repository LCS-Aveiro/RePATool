package rta.backend

import rta.syntax.Program2.{Edge, QName, RxGraph}
import rta.syntax.{Condition, Statement, Aggregation}
import scala.collection.mutable

object TrainingEngine {

  private val EPSILON = 0.00001
  private val INITIAL_SAMPLES = 50

  private def clamp(v: Double): Double =
    Math.round(Math.max(0.0, Math.min(1.0, v)) * 1000.0) / 1000.0

  private case class HyperEffect(
    targetEdgeIdx: Int,
    activate: Boolean,
    ruleWeight: Double,
    aggType: String,
    conditionOpt: Option[Condition]
  )

  private class Session(
    val rx: RxGraph,
    val edgeArr: Array[Edge],
    val weights: Array[Double],
    val hits: Array[Long],
    val totals: mutable.Map[QName, Long],
    val activeArr: Array[Boolean],
    val initActiveArr: Array[Boolean],
    val hyperTable: Map[QName, List[HyperEffect]],
    val labelToEdges: Map[QName, List[Int]],
    val stateToEdges: Map[QName, List[Int]],
    var varEnv: Map[QName, Int],
    var currentStates: Set[QName],
    val isAggregation: Boolean
  )

  private def compile(rx: RxGraph): Session = {
    val edgeList = rx.edg.flatMap { case (src, tgts) => tgts.map(t => (src, t._1, t._2, t._3)) }.toArray
    val weights = edgeList.map(e => rx.weights.getOrElse(e, 1.0))
    val totals = mutable.Map[QName, Long]().withDefaultValue(INITIAL_SAMPLES.toLong)
    val hits = new Array[Long](edgeList.length)
    
    for (i <- edgeList.indices) hits(i) = (weights(i) * INITIAL_SAMPLES).toLong

    val activeArr = edgeList.map(rx.act.contains)
    val initActiveArr = edgeList.map(rx.act.contains)
    val labelToEdges = edgeList.zipWithIndex.groupBy(_._1._4).map { case (lbl, pairs) => lbl -> pairs.map(_._2).toList }
    val stateToEdges = edgeList.zipWithIndex.groupBy(_._1._1).map { case (src, pairs) => src -> pairs.map(_._2).toList }

    val builder = mutable.Map[QName, mutable.ListBuffer[HyperEffect]]()
    def addEffects(triggerLabel: QName, ruleMap: Map[QName, Set[(QName, QName, QName)]], activate: Boolean): Unit = {
      ruleMap.get(triggerLabel).foreach { targets =>
        targets.foreach { case (targetLbl, ruleId, ruleLabel) =>
          val ruleEdge = (triggerLabel, targetLbl, ruleId, ruleLabel)
          val ruleWeight = rx.weights.getOrElse(ruleEdge, 0.1)
          val aggType = rx.edgeAggregations.getOrElse(ruleEdge, "arith")
          val condOpt = rx.edgeConditions.get(ruleEdge).flatten
          for (idx <- labelToEdges.getOrElse(targetLbl, Nil)) {
            val buf = builder.getOrElseUpdate(triggerLabel, mutable.ListBuffer())
            buf += HyperEffect(idx, activate, ruleWeight, aggType, condOpt)
          }
        }
      }
    }
    
    val allTriggers = rx.on.keySet ++ rx.off.keySet
    for (trigger <- allTriggers) {
      addEffects(trigger, rx.on, true)
      addEffects(trigger, rx.off, false)
    }

    val isAgg = rx.trainingMethod == "aggregation"
    new Session(rx, edgeList, weights, hits, totals, activeArr, initActiveArr, builder.map(kv => kv._1 -> kv._2.toList).toMap, labelToEdges, stateToEdges, rx.val_env, rx.inits, isAgg)
  }

  private def distributeWeights(sess: Session, srcState: QName, modifiedIdxs: Set[Int], activeIdxs: List[Int], mode: String): Unit = {
    if (activeIdxs.isEmpty) return
    val activeModified = modifiedIdxs.filter(activeIdxs.contains)
    val unmodified = activeIdxs.filterNot(activeModified.contains)

    mode match {
      case "equal" | "proportional" if unmodified.nonEmpty =>
        val sMod = activeModified.map(sess.weights).sum
        if (sMod >= 1.0 - EPSILON) {
          for (i <- unmodified) sess.weights(i) = 0.0
          if (sMod > EPSILON) for (i <- activeModified) sess.weights(i) = clamp(sess.weights(i) / sMod)
        } else {
          val targetUnmod = 1.0 - sMod
          if (mode == "proportional") {
            val sUnmod = unmodified.map(i => sess.hits(i).toDouble / math.max(sess.totals(sess.edgeArr(i)._1), 1L)).sum
            if (sUnmod > EPSILON) {
              val scale = targetUnmod / sUnmod
              for (i <- unmodified) sess.weights(i) = clamp((sess.hits(i).toDouble / math.max(sess.totals(sess.edgeArr(i)._1), 1L)) * scale)
            } else {
              val share = targetUnmod / unmodified.size
              for (i <- unmodified) sess.weights(i) = clamp(share)
            }
          } else {
            var remaining = unmodified
            var remainingTarget = targetUnmod
            var done = false
            while (!done && remaining.nonEmpty) {
              val sUnmod = remaining.map(i => sess.hits(i).toDouble / math.max(sess.totals(sess.edgeArr(i)._1), 1L)).sum
              val diff = remainingTarget - sUnmod
              val share = diff / remaining.size
              var clampedAny = false
              var next = List.empty[Int]
              for (i <- remaining) {
                val oldW = sess.hits(i).toDouble / math.max(sess.totals(sess.edgeArr(i)._1), 1L)
                val proposed = oldW + share
                if (proposed < 0.0) { sess.weights(i) = 0.0; clampedAny = true }
                else if (proposed > 1.0) { sess.weights(i) = 1.0; remainingTarget -= 1.0; clampedAny = true }
                else next = i :: next
              }
              if (!clampedAny) {
                for (i <- next) sess.weights(i) = clamp((sess.hits(i).toDouble / math.max(sess.totals(sess.edgeArr(i)._1), 1L)) + share)
                done = true
              } else remaining = next
            }
          }
        }
      case _ =>
        val totalSum = activeIdxs.map(sess.weights).sum
        if (totalSum < EPSILON) {
          val uniform = 1.0 / activeIdxs.size
          for (i <- activeIdxs) sess.weights(i) = clamp(uniform)
        } else {
          for (i <- activeIdxs) sess.weights(i) = clamp(sess.weights(i) / totalSum)
        }
    }
  }

  private def processEvent(sess: Session, eventLabel: String): Boolean = {
    val labelQN = QName(List(eventLabel))
    val firedIdxOpt = sess.currentStates.view.flatMap { st =>
      sess.stateToEdges.getOrElse(st, Nil).find { idx =>
        val e = sess.edgeArr(idx)
        e._4 == labelQN && sess.activeArr(idx) && sess.rx.edgeConditions.get(e).flatten.forall(c => Condition.evaluate(c, sess.varEnv))
      }
    }.headOption

    firedIdxOpt match {
      case None => false
      case Some(firedIdx) =>
        val firedEdge = sess.edgeArr(firedIdx)
        val srcState = firedEdge._1
        val dstState = firedEdge._2
        val firedLbl = firedEdge._4

        val activeBeforeIdxs = sess.stateToEdges.getOrElse(srcState, Nil).filter(sess.activeArr)

        val edgeStmts = sess.rx.edgeUpdates.getOrElse(firedEdge, Nil)
        if (edgeStmts.nonEmpty) sess.varEnv = RxSemantics.applyUpdates(edgeStmts, sess.rx.copy(val_env = sess.varEnv))

        val effects = sess.hyperTable.getOrElse(firedLbl, Nil)
        val dirtyStates = mutable.Set[QName]()
        val modifiedByHyper = mutable.Set[Int]()

        if (effects.nonEmpty) {
          val wSource = sess.weights(firedIdx)
          for (fx <- effects) {
            if (fx.conditionOpt.forall(c => Condition.evaluate(c, sess.varEnv))) {
              val newW = Aggregation.compute(fx.aggType, wSource, fx.ruleWeight, sess.weights(fx.targetEdgeIdx))
              sess.weights(fx.targetEdgeIdx) = clamp(newW)
              dirtyStates += sess.edgeArr(fx.targetEdgeIdx)._1
              modifiedByHyper += fx.targetEdgeIdx
              sess.activeArr(fx.targetEdgeIdx) = fx.activate
            }
          }
          for (st <- dirtyStates) {
            val stActiveIdxs = sess.stateToEdges.getOrElse(st, Nil).filter(sess.activeArr)
            distributeWeights(sess, st, modifiedByHyper.toSet, stActiveIdxs, sess.rx.distributionMode)
          }
        }

        if (sess.isAggregation) {
          val oldW = sess.weights(firedIdx)
          sess.weights(firedIdx) = clamp(Aggregation.compute(sess.rx.trainingAgg, oldW, sess.rx.trainingLambda, oldW))
          distributeWeights(sess, srcState, Set(firedIdx), activeBeforeIdxs, sess.rx.distributionMode)
        } else {
          val newTotal = sess.totals(srcState) + 1L
          sess.totals(srcState) = newTotal
          sess.hits(firedIdx) += 1L
          sess.weights(firedIdx) = clamp(sess.hits(firedIdx).toDouble / newTotal.toDouble)
          distributeWeights(sess, srcState, Set(firedIdx), activeBeforeIdxs, sess.rx.distributionMode)
          for (idx <- activeBeforeIdxs) {
            sess.hits(idx) = Math.round(sess.weights(idx) * newTotal.toDouble).toLong
          }
        }

        sess.currentStates = (sess.currentStates - srcState) + dstState
        true
    }
  }

  def trainFromLines(rx: RxGraph, lines: Iterator[String], persistent: Boolean = false, onProgress: (Long, Long) => Unit = (_, _) => ()): RxGraph = {
    val sess = compile(rx)
    val origInits = rx.inits
    var sessionCount = 0L; var eventCount = 0L
    for (line <- lines) {
      val trimmed = line.trim
      if (trimmed.nonEmpty) {
        val events = trimmed.split(',')
        if (!persistent) {
          sess.currentStates = origInits; sess.varEnv = rx.val_env
          Array.copy(sess.initActiveArr, 0, sess.activeArr, 0, sess.activeArr.length)
        } else sess.currentStates = origInits
        for (ev <- events) if (ev.trim.nonEmpty && processEvent(sess, ev.trim)) eventCount += 1L
        sessionCount += 1L
        if (sessionCount % 100_000L == 0L) onProgress(sessionCount, eventCount)
      }
    }
    onProgress(sessionCount, eventCount)
    val newWeights = sess.rx.weights ++ sess.edgeArr.zipWithIndex.map { case (e, i) => e -> sess.weights(i) }.toMap
    val newAct = sess.edgeArr.zipWithIndex.filter { case (_, i) => if (persistent) sess.activeArr(i) else sess.initActiveArr(i) }.map(_._1).toSet
    sess.rx.copy(weights = newWeights, act = newAct, val_env = sess.varEnv, inits = origInits)
  }

  def trainFromBatch(rx: RxGraph, sessions: Seq[Seq[String]], persistent: Boolean = false): RxGraph = {
    val sess = compile(rx)
    val origInits = rx.inits
    for (session <- sessions) {
      if (!persistent) {
        sess.currentStates = origInits; sess.varEnv = rx.val_env
        Array.copy(sess.initActiveArr, 0, sess.activeArr, 0, sess.activeArr.length)
      } else sess.currentStates = origInits
      for (ev <- session) if (ev.trim.nonEmpty) processEvent(sess, ev.trim)
    }
    val newWeights = sess.rx.weights ++ sess.edgeArr.zipWithIndex.map { case (e, i) => e -> sess.weights(i) }.toMap
    val newAct = sess.edgeArr.zipWithIndex.filter { case (_, i) => if (persistent) sess.activeArr(i) else sess.initActiveArr(i) }.map(_._1).toSet
    sess.rx.copy(weights = newWeights, act = newAct, val_env = sess.varEnv, inits = origInits)
  }

  def trainFromStream(rx: RxGraph, events: Seq[String]): RxGraph = {
    val sess = compile(rx)
    sess.currentStates = rx.inits
    sess.varEnv = rx.val_env
    for (ev <- events) if (ev.trim.nonEmpty) processEvent(sess, ev.trim)
    val newWeights = sess.rx.weights ++ sess.edgeArr.zipWithIndex.map { case (e, i) => e -> sess.weights(i) }.toMap
    val newAct = sess.edgeArr.zipWithIndex.filter { case (_, i) => sess.activeArr(i) }.map(_._1).toSet
    sess.rx.copy(weights = newWeights, act = newAct, val_env = sess.varEnv, inits = rx.inits)
  }
}