package rta.backend

import rta.syntax.Program2.{Edge, QName, RxGraph, showEdge}
import rta.syntax.{Condition, Statement, UpdateExpr}
import rta.syntax.{AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt, PrintStmt}

import scala.collection.mutable


object TrainingEngine {

  final case class Report(sessions: Long, events: Long, unmatched: Long, ambiguous: Long) {
    def summary: String = {
      val extra = List(
        if (unmatched > 0) Some(s"$unmatched events had no enabled edge with that label and were skipped") else None,
        if (ambiguous > 0) Some(s"$ambiguous events matched several enabled edges (the first was used)") else None
      ).flatten
      s"Processed $sessions sessions ($events events)" + (if (extra.isEmpty) "." else extra.mkString(". Warning: ", "; ", "."))
    }
  }

  var lastReport: Report = Report(0, 0, 0, 0)

  private val edgeOrder: Ordering[Edge] = Ordering.by(showEdge)

  private final class Engine(base: RxGraph, persistent: Boolean) {
    private val isAggregation = base.trainingMethod == "aggregation" && base.trainingAgg != "mean"
    private val runtimeBase = base.copy(trainingMode = false, trainCounts = Map())
    private val ground = base.groundEdges.toList

    private val byStateLabel: Map[(QName, QName), List[Edge]] =
      ground.groupBy(e => (e._1, e._4)).map((k, es) => k -> es.sorted(using edgeOrder))

    private val support: mutable.HashMap[QName, Vector[Edge]] =
      mutable.HashMap.from(ground.filter(base.act.contains).groupBy(_._1).map((s, es) => s -> es.sorted(using edgeOrder).toVector))
    private val learned: mutable.HashMap[Edge, Double] =
      mutable.HashMap.from(ground.map(e => e -> base.weights.getOrElse(e, 1.0)))
    private val counts = mutable.HashMap[Edge, Double]()
    private val countedStates = mutable.HashSet[QName]()

    private val weightSensitive: Boolean =
      base.on.exists(_._2.nonEmpty) || base.off.exists(_._2.nonEmpty) || base.weightExprs.nonEmpty ||
      base.edgeConditions.valuesIterator.flatten.exists(readsWeights) ||
      base.edgeUpdates.valuesIterator.flatten.exists(stmtReadsWeights)

    private val labelCache = mutable.HashMap[String, QName]()
    private var runtime: RxGraph = runtimeBase
    var sessions = 0L; var events = 0L; var unmatched = 0L; var ambiguous = 0L

    def startSession(): Unit = {
      runtime =
        if (persistent && sessions > 0) runtime.copy(inits = base.inits)
        else if (weightSensitive) runtimeBase.copy(weights = runtimeBase.weights ++ currentLearned)
        else runtimeBase
      sessions += 1
    }

    def event(name: String): Unit = {
      val lbl = labelCache.getOrElseUpdate(name, QName(name.split('.').toList))
      val candidates = runtime.inits.toList
        .flatMap(st => byStateLabel.getOrElse((st, lbl), Nil))
        .filter(e => RxSemantics.isEnabled(e, runtime))
      candidates match {
        case Nil => unmatched += 1
        case first :: rest =>
          val fired = if (rest.isEmpty) first else { ambiguous += 1; candidates.min(using edgeOrder) }
          events += 1
          runtime = RxSemantics.step(runtime, fired)
          learn(fired)
      }
    }

    private def learn(fired: Edge): Unit = {
      val s = fired._1
      var sup = support.getOrElse(s, Vector.empty)
      if (!sup.contains(fired)) { sup = sup :+ fired; support(s) = sup }
      if (isAggregation) {
        Learning.aggregationUpdate(base, e => learned.getOrElse(e, 0.0), sup, fired).foreach(learned.update)
      } else {
        if (countedStates.add(s)) sup.foreach(e => counts.getOrElseUpdate(e, learned.getOrElse(e, 0.0) * Learning.PRIOR_SAMPLES))
        else counts.getOrElseUpdate(fired, learned.getOrElse(fired, 0.0) * Learning.PRIOR_SAMPLES)
        counts(fired) += 1.0
        if (weightSensitive) Learning.laplaceWeights(e => counts.getOrElse(e, 0.0), sup).foreach(learned.update)
      }
    }

    private def currentLearned: Map[Edge, Double] = {
      if (!isAggregation && !weightSensitive)
        for (s <- countedStates) Learning.laplaceWeights(e => counts.getOrElse(e, 0.0), support(s)).foreach(learned.update)
      learned.toMap
    }

    def result(): RxGraph = {
      lastReport = Report(sessions, events, unmatched, ambiguous)
      base.copy(
        weights = base.weights ++ currentLearned,
        act = if (persistent) runtime.act else base.act,
        val_env = if (persistent) runtime.val_env else base.val_env,
        inits = base.inits
      )
    }
  }

  private def readsWeights(c: Condition): Boolean = c match {
    case _: Condition.WeightCheck => true
    case Condition.AtomicCond(l, _, r) => exprReadsWeights(l) || exprReadsWeights(r)
    case Condition.And(a, b) => readsWeights(a) || readsWeights(b)
    case Condition.Or(a, b) => readsWeights(a) || readsWeights(b)
  }

  private def exprReadsWeights(e: UpdateExpr): Boolean = e match {
    case UpdateExpr.FuncCall(_, _) => true
    case UpdateExpr.MathOp(l, _, r) => exprReadsWeights(l) || exprReadsWeights(r)
    case UpdateExpr.ArrayAccess(_, i) => exprReadsWeights(i)
    case UpdateExpr.LitArray(es) => es.exists(exprReadsWeights)
    case _ => false
  }

  private def stmtReadsWeights(s: Statement): Boolean = s match {
    case AssignStmt(_, e) => exprReadsWeights(e)
    case ArrayAssignStmt(_, i, e) => exprReadsWeights(i) || exprReadsWeights(e)
    case IfThenStmt(c, ts) => readsWeights(c) || ts.exists(stmtReadsWeights)
    case ForeachStmt(_, _, body) => body.exists(stmtReadsWeights)
    case ReturnStmt(e) => exprReadsWeights(e)
    case PrintStmt(e) => exprReadsWeights(e)
  }

  def trainFromLines(rx: RxGraph, lines: Iterator[String], persistent: Boolean = false, onProgress: (Long, Long) => Unit = (_, _) => ()): RxGraph = {
    val engine = new Engine(rx, persistent)
    for (line <- lines) {
      val trimmed = line.trim
      if (trimmed.nonEmpty) {
        engine.startSession()
        for (ev <- trimmed.split(',')) { val e = ev.trim; if (e.nonEmpty) engine.event(e) }
        if (engine.sessions % 100_000L == 0L) onProgress(engine.sessions, engine.events)
      }
    }
    onProgress(engine.sessions, engine.events)
    engine.result()
  }

  def trainFromBatch(rx: RxGraph, sessions: Seq[Seq[String]], persistent: Boolean = false): RxGraph = {
    val engine = new Engine(rx, persistent)
    for (session <- sessions) {
      engine.startSession()
      for (ev <- session) { val e = ev.trim; if (e.nonEmpty) engine.event(e) }
    }
    engine.result()
  }

  def trainFromStream(rx: RxGraph, events: Seq[String]): RxGraph = {
    val engine = new Engine(rx, persistent = true)
    engine.startSession()
    for (ev <- events) { val e = ev.trim; if (e.nonEmpty) engine.event(e) }
    engine.result()
  }
}
