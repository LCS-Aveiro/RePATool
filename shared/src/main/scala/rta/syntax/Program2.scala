package rta.syntax

import rta.backend.RxSemantics
import rta.syntax.Program2.EdgeMap
import rta.syntax.{Condition, UpdateExpr, Statement, AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt, RuntimeValue, FunctionDef}
import scala.annotation.tailrec
import scala.language.implicitConversions

object Program2:

  type Rel[A,B] = Map[A,Set[B]]
  def empty[A,B] = Map[A,Set[B]]().withDefaultValue(Set())
  def add[A,B](ab:(A,B), r:Rel[A,B]) = r + (ab._1 -> (r.getOrElse(ab._1, Set.empty[B]) + ab._2))
  def join[A,B](r1:Rel[A,B], r2:Rel[A,B]) = r1 ++ r2.map(ab => ab._1 -> (r1.getOrElse(ab._1, Set.empty[B]) ++ ab._2))

  def fmtWeight(w: Double): String = {
    val s = f"$w%.6f".replace(',', '.')
    val trimmed = s.reverse.dropWhile(_ == '0').reverse
    val decimals = trimmed.length - trimmed.indexOf('.') - 1
    if (decimals >= 3) trimmed else trimmed + "0" * (3 - decimals)
  }

  private def isGlobalControlVar(q: QName): Boolean = q.n.mkString.contains("_")

  case class QName(n:List[String]):
    @transient private lazy val cachedHash: Int = n.##
    override def hashCode(): Int = cachedHash
    override def equals(o: Any): Boolean = o match {
      case q: QName => (this eq q) || (cachedHash == q.hashCode && ((n eq q.n) || n == q.n))
      case _ => false
    }
    @transient private lazy val cachedString: String = n.mkString("/")
    override def toString = cachedString
    def show = if n.isEmpty then "-" else toString
    def /(other:QName) = if (other.n.isEmpty) other else if (n.isEmpty) other else QName(n ::: other.n)
    def /(other:String) = QName(n:::List(other))

    def /(e:EdgeMap):EdgeMap = e.map((src, targets) => (this / src) -> targets.map((to, id, lbl) => (this / to, this / id, this / lbl))).withDefaultValue(Set())
    def /(es:Edges): Edges = es.map((src, to, id, lbl) => (this / src, this / to, this / id, this / lbl))
    def /-(lblsMap:Map[QName,Edges]): Map[QName,Edges] = lblsMap.map((lbl, edges) => (this / lbl) -> (this / edges)).withDefaultValue(Set())
    def /-(ns:Set[QName]): Set[QName] = ns.map(n => this / n)
    def scope: QName = if n.isEmpty then this else QName(n.init)

    def /(rx: RxGraph): RxGraph =
      rx.copy( 
        edg = this / rx.edg,
        on = this / rx.on,
        off = this / rx.off,
        lbls = this /- rx.lbls,
        inits = this /- rx.inits,
        act = this / rx.act,
        val_env = rx.val_env.map { case (k, v) => (this / k) -> v },
        functions = rx.functions.map { case (k, f) => 
          (this / k) -> FunctionDef(this / f.name, f.params, f.body.map(s => applyPrefixToStatement(this, s))) 
        },
        edgeAggregations = rx.edgeAggregations.map { case (edge, agg) => 
          (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> agg 
        },
        edgeConditions = rx.edgeConditions.map { case (edge, condOpt) =>
          (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> condOpt.map(c => applyPrefixToCondition(this, c))
        },
        edgeUpdates = rx.edgeUpdates.map { case (edge, stmtList) =>
          (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> stmtList.map(stmt => applyPrefixToStatement(this, stmt))
        },
        weights = rx.weights.map { case (edge, w) => (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> w },
        weightExprs = rx.weightExprs.map { case (edge, expr) => (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> applyPrefixToExpr(this, expr) }
      )
  
  def applyPrefixToExpr(prefix: QName, expr: UpdateExpr): UpdateExpr = expr match {
    case UpdateExpr.LitInt(_) | UpdateExpr.LitFloat(_) | UpdateExpr.LitBool(_) => expr
    case UpdateExpr.LitArray(elems) => UpdateExpr.LitArray(elems.map(applyPrefixToExpr(prefix, _)))
    case UpdateExpr.Var(q) => UpdateExpr.Var(if(isGlobalControlVar(q)) q else prefix / q)
    case UpdateExpr.ArrayAccess(arr, idx) => UpdateExpr.ArrayAccess(if(isGlobalControlVar(arr)) arr else prefix / arr, applyPrefixToExpr(prefix, idx))
    case UpdateExpr.MathOp(l, op, r) => UpdateExpr.MathOp(applyPrefixToExpr(prefix, l), op, applyPrefixToExpr(prefix, r))
    case UpdateExpr.FuncCall(f, args) => UpdateExpr.FuncCall(if(isGlobalControlVar(f)) f else prefix / f, args.map(applyPrefixToExpr(prefix, _)))
  }

  def applyPrefixToCondition(prefix: QName, cond: Condition): Condition = {
    cond match {
      case Condition.AtomicCond(left, op, right) => Condition.AtomicCond(applyPrefixToExpr(prefix, left), op, applyPrefixToExpr(prefix, right))
      case Condition.WeightCheck(lbl, metric, op, value) => Condition.WeightCheck(if (isGlobalControlVar(lbl)) lbl else prefix / lbl, metric, op, value)
      case Condition.And(l, r) => Condition.And(applyPrefixToCondition(prefix, l), applyPrefixToCondition(prefix, r))
      case Condition.Or(l, r) => Condition.Or(applyPrefixToCondition(prefix, l), applyPrefixToCondition(prefix, r))
    }
  }

  def applyPrefixToStatement(prefix: QName, stmt: Statement): Statement = {
    stmt match {
      case AssignStmt(variable, expr) =>
        val newVar = if (isGlobalControlVar(variable)) variable else prefix / variable
        AssignStmt(newVar, applyPrefixToExpr(prefix, expr))
      case ArrayAssignStmt(arrName, index, expr) =>
        val newArr = if (isGlobalControlVar(arrName)) arrName else prefix / arrName
        ArrayAssignStmt(newArr, applyPrefixToExpr(prefix, index), applyPrefixToExpr(prefix, expr))
      case IfThenStmt(cond, thenStmts) =>
        IfThenStmt(applyPrefixToCondition(prefix, cond), thenStmts.map(s => applyPrefixToStatement(prefix, s)))
      case ForeachStmt(iter, arr, body) =>
        val newIter = if (isGlobalControlVar(iter)) iter else prefix / iter
        val newArr = if (isGlobalControlVar(arr)) arr else prefix / arr
        ForeachStmt(newIter, newArr, body.map(s => applyPrefixToStatement(prefix, s)))
      case ReturnStmt(expr) => ReturnStmt(applyPrefixToExpr(prefix, expr))
      case PrintStmt(expr) => PrintStmt(applyPrefixToExpr(prefix, expr))
    }
  }

  type Edge = (QName, QName, QName, QName)
  type Edges = Set[Edge]
  type EdgeMap = Rel[QName, (QName, QName, QName)]

  def showEdge(e: Edge): String = {
    val (from, to, transId, label) = e
    if (transId == label) s"${from.show} ---> ${to.show} : ${label.show}"
    else s"${from.show} -${transId.show}-> ${to.show} : ${label.show}"
  }
  
  def showEdges(abc:Edges): String = abc.map(showEdge).mkString(", ")

  private def showEdgeMap(abc:EdgeMap): String =
    val es = for (a, bcs) <- abc.toSet; (b, id, lbl) <- bcs yield (a, b, id, lbl)
    showEdges(es)

  case class RxGraph(edg:EdgeMap,
                     on:EdgeMap, off: EdgeMap,
                     lbls: Map[QName,Edges],
                     inits: Set[QName],
                     act: Edges,
                     val_env: Map[QName, RuntimeValue],
                     functions: Map[QName, FunctionDef],
                     edgeConditions: Map[Edge, Option[Condition]], 
                     edgeUpdates: Map[Edge, List[Statement]],
                     weights: Map[Edge, Double],
                     edgeAggregations: Map[Edge, String],
                     trainingMode: Boolean = false,
                     paradigm: String = "probabilistic",
                     distributionMode: String = "normalize",
                     trainingMethod: String = "laplace",
                     trainingAgg: String = "arith",
                     trainingLambda: Double = 1.0,
                     weightExprs: Map[Edge, UpdateExpr] = Map(),
                     trainCounts: Map[Edge, Double] = Map()
                    ):

    @transient private lazy val cachedHash: Int = (inits, act, val_env).##
    override def hashCode(): Int = cachedHash
    override def equals(o: Any): Boolean = o match {
      case r: RxGraph =>
        def same(a: AnyRef, b: AnyRef): Boolean = (a eq b) || a == b
        (this eq r) || (
          hashCode == r.hashCode &&
          same(inits, r.inits) && same(act, r.act) && same(val_env, r.val_env) &&
          same(weights, r.weights) && same(trainCounts, r.trainCounts) &&
          same(edg, r.edg) && same(on, r.on) && same(off, r.off) && same(lbls, r.lbls) &&
          same(functions, r.functions) && same(edgeConditions, r.edgeConditions) && same(edgeUpdates, r.edgeUpdates) &&
          same(edgeAggregations, r.edgeAggregations) && same(weightExprs, r.weightExprs) &&
          trainingMode == r.trainingMode && paradigm == r.paradigm && distributionMode == r.distributionMode &&
          trainingMethod == r.trainingMethod && trainingAgg == r.trainingAgg && trainingLambda == r.trainingLambda
        )
      case _ => false
    }

    def groundEdges: Edges = for (src, ts) <- edg.toSet; (t, id, l) <- ts yield (src, t, id, l)
    def onEdges: Edges = for (src, ts) <- on.toSet; (t, id, l) <- ts yield (src, t, id, l)
    def offEdges: Edges = for (src, ts) <- off.toSet; (t, id, l) <- ts yield (src, t, id, l)

    private def isGround(e: Edge): Boolean = edg.getOrElse(e._1, Set.empty).contains((e._2, e._3, e._4))
    private def isOn(e: Edge): Boolean = on.getOrElse(e._1, Set.empty).contains((e._2, e._3, e._4))
    private def isOff(e: Edge): Boolean = off.getOrElse(e._1, Set.empty).contains((e._2, e._3, e._4))

    private def withSettingsOf(other: RxGraph): RxGraph = {
      val d = RxGraph()
      def pick[T](a: T, b: T, default: T): T = if (a != default) a else b
      copy(
        trainingMode = trainingMode || other.trainingMode,
        paradigm = pick(paradigm, other.paradigm, d.paradigm),
        distributionMode = pick(distributionMode, other.distributionMode, d.distributionMode),
        trainingMethod = pick(trainingMethod, other.trainingMethod, d.trainingMethod),
        trainingAgg = pick(trainingAgg, other.trainingAgg, d.trainingAgg),
        trainingLambda = pick(trainingLambda, other.trainingLambda, d.trainingLambda)
      )
    }

    def union(other: RxGraph, aggName: String): RxGraph = {
      var newRx = RxGraph().withSettingsOf(this).withSettingsOf(other).copy(
        inits = this.inits ++ other.inits,
        val_env = this.val_env ++ other.val_env,
        functions = this.functions ++ other.functions
      )
      val allEdges = (this.lbls.valuesIterator ++ other.lbls.valuesIterator).flatten.toSet
      for (e <- allEdges) {
        val inM = this.lbls.getOrElse(e._4, Set.empty).contains(e)
        val inN = other.lbls.getOrElse(e._4, Set.empty).contains(e)
        val wM = this.weights.getOrElse(e, 0.0)
        val wN = other.weights.getOrElse(e, 0.0)
        val finalWeight = if (inM && inN) Aggregation.compute(aggName, wM, wN, (wM + wN) / 2) else if (inM) wM else wN
        val cond = this.edgeConditions.get(e).flatten.orElse(other.edgeConditions.get(e).flatten)
        val upd = this.edgeUpdates.getOrElse(e, other.edgeUpdates.getOrElse(e, Nil))
        val wExpr = this.weightExprs.get(e).orElse(other.weightExprs.get(e))
        val agg = this.edgeAggregations.get(e).orElse(other.edgeAggregations.get(e)).getOrElse("")
        if (this.isGround(e) || other.isGround(e)) newRx = newRx.addEdge(e._1, e._2, e._3, e._4, cond, upd, finalWeight, agg, wExpr)
        if (this.isOn(e) || other.isOn(e)) newRx = newRx.addOn(e._1, e._2, e._3, e._4, cond, upd, finalWeight, agg, wExpr)
        if (this.isOff(e) || other.isOff(e)) newRx = newRx.addOff(e._1, e._2, e._3, e._4, cond, upd, finalWeight, agg, wExpr)
        if (!(this.act.contains(e) || other.act.contains(e))) newRx = newRx.deactivate(e._1, e._2, e._3, e._4)
      }
      newRx.normalized
    }

    def intersection(other: RxGraph, aggName: String): RxGraph = {
      var newRx = RxGraph().withSettingsOf(this).withSettingsOf(other).copy(
        inits = this.inits.intersect(other.inits),
        val_env = this.val_env.filter { case (k, v) => other.val_env.get(k).contains(v) },
        functions = this.functions ++ other.functions
      )
      for (label <- this.lbls.keySet.intersect(other.lbls.keySet);
           e <- this.lbls.getOrElse(label, Set.empty).intersect(other.lbls.getOrElse(label, Set.empty))) {
        val wM = this.weights.getOrElse(e, 0.0)
        val wN = other.weights.getOrElse(e, 0.0)
        val finalWeight = Aggregation.compute(aggName, wM, wN, (wM + wN) / 2)
        val cond = this.edgeConditions.get(e).flatten
        val upd = this.edgeUpdates.getOrElse(e, Nil)
        val wExpr = this.weightExprs.get(e).orElse(other.weightExprs.get(e))
        val agg = this.edgeAggregations.getOrElse(e, "")
        if (this.isGround(e) && other.isGround(e)) newRx = newRx.addEdge(e._1, e._2, e._3, e._4, cond, upd, finalWeight, agg, wExpr)
        if (this.isOn(e) && other.isOn(e)) newRx = newRx.addOn(e._1, e._2, e._3, e._4, cond, upd, finalWeight, agg, wExpr)
        if (this.isOff(e) && other.isOff(e)) newRx = newRx.addOff(e._1, e._2, e._3, e._4, cond, upd, finalWeight, agg, wExpr)
        if (!(this.act.contains(e) && other.act.contains(e))) newRx = newRx.deactivate(e._1, e._2, e._3, e._4)
      }
      newRx.normalized
    }

    def normalized: RxGraph = {
      if (paradigm == "fuzzy") return this
      var w = weights
      for ((src, ts) <- edg) {
        val active = ts.toList.map(t => (src, t._1, t._2, t._3)).filter(act.contains)
        val total = active.map(e => w.getOrElse(e, 1.0)).sum
        if (total > 0) active.foreach(e => w += (e -> w.getOrElse(e, 1.0) / total))
      }
      copy(weights = w)
    }

    def applyDeltaCut(delta: Double): RxGraph = {
      val pruned: Edges =
        groundEdges.filter(e => weights.getOrElse(e, 1.0) < delta) ++
        (onEdges ++ offEdges).filter(e => weights.getOrElse(e, 0.1) < delta)
      if (pruned.isEmpty) return this
      def cut(m: EdgeMap): EdgeMap =
        m.map { case (src, ts) => src -> ts.filterNot(t => pruned.contains((src, t._1, t._2, t._3))) }
         .filter(_._2.nonEmpty).withDefaultValue(Set())
      this.copy(
        edg = cut(edg), on = cut(on), off = cut(off),
        lbls = lbls.map { case (l, es) => l -> (es -- pruned) }.filter(_._2.nonEmpty).withDefaultValue(Set()),
        act = act -- pruned,
        weights = weights -- pruned,
        edgeConditions = edgeConditions -- pruned,
        edgeUpdates = edgeUpdates -- pruned,
        edgeAggregations = edgeAggregations -- pruned,
        weightExprs = weightExprs -- pruned
      ).normalized
    }

    def toRta: String = {
      val sb = new StringBuilder
      val nm = ReLang.name

      if (paradigm != "probabilistic") sb.append(s"paradigm $paradigm\n")
      if (distributionMode != "normalize") sb.append(s"calibration $distributionMode\n")
      if (trainingMode) {
        if (trainingMethod == "aggregation") sb.append(s"training aggregation $trainingAgg(${UpdateExpr.num(trainingLambda)})\n")
        else sb.append("training laplace\n")
      }
      inits.toList.map(nm).sorted.foreach(i => sb.append(s"init $i\n"))
      sb.append("\n")

      val vars = val_env.toList.filterNot(_._1.show.startsWith("__")).sortBy(_._1.show)
      vars.foreach { case (k, v) =>
        v match {
          case RuntimeValue.VInt(value, Some(lo), Some(hi)) => sb.append(s"int ${nm(k)} [$lo..$hi] = $value\n")
          case RuntimeValue.VInt(value, _, _) => sb.append(s"int ${nm(k)} = $value\n")
          case RuntimeValue.VFloat(value, Some(lo), Some(hi)) =>
            sb.append(s"float ${nm(k)} [${UpdateExpr.num(lo)}..${UpdateExpr.num(hi)}] = ${UpdateExpr.num(value)}\n")
          case RuntimeValue.VFloat(value, _, _) => sb.append(s"float ${nm(k)} = ${UpdateExpr.num(value)}\n")
          case RuntimeValue.VBool(value) => sb.append(s"bool ${nm(k)} = $value\n")
          case arr @ RuntimeValue.VArray(values, isDyn, maxOpt) =>
            val elemType = values.headOption match {
              case Some(_: RuntimeValue.VFloat) => "float"
              case Some(_: RuntimeValue.VBool) => "bool"
              case _ => "int"
            }
            val bound = maxOpt.map(m => s" [max=$m]").getOrElse("")
            sb.append(s"${if (isDyn) "dyn " else ""}$elemType[] ${nm(k)}$bound = ${ReLang.value(arr)}\n")
        }
      }
      if (vars.nonEmpty) sb.append("\n")

      functions.values.toList.sortBy(_.name.show).foreach { f =>
        sb.append(s"def ${nm(f.name)}(${f.params.map(nm).mkString(", ")}) {\n")
        f.body.foreach(st => sb.append(s"  ${ReLang.statement(st)}\n"))
        sb.append("}\n\n")
      }

      def line(e: Edge, arrow: String, defaultW: Double): String = {
        val (s, t, id, l) = e
        val head = if (id == l) s"${nm(s)} ${if (arrow == "->") "--->" else arrow} ${nm(t)} : ${nm(l)}"
                   else s"${nm(s)} -${nm(id)}$arrow ${nm(t)} : ${nm(l)}"
        val w = weightExprs.get(e).map(ReLang.expr).getOrElse(fmtWeight(weights.getOrElse(e, defaultW)))
        val agg = edgeAggregations.get(e).filter(a => a.nonEmpty && a != "arith").map(a => s" $a").getOrElse("")
        val disabled = if (act.contains(e)) "" else " disabled"
        val cond = edgeConditions.getOrElse(e, None).map(c => s" if (${ReLang.condition(c)})").getOrElse("")
        val upd = edgeUpdates.getOrElse(e, Nil) match {
          case Nil => ""
          case ss => s" then { ${ss.map(ReLang.statement).mkString("; ")} }"
        }
        s"$head ($w)$agg$disabled$cond$upd\n"
      }
      val byText = Ordering.by[Edge, String](e => s"${nm(e._1)} ${nm(e._4)} ${nm(e._2)} ${nm(e._3)}")
      groundEdges.toList.sorted(using byText).foreach(e => sb.append(line(e, "->", 1.0)))
      onEdges.toList.sorted(using byText).foreach(e => sb.append(line(e, "->>", 0.1)))
      offEdges.toList.sorted(using byText).foreach(e => sb.append(line(e, "--x", 0.1)))
      sb.toString()
    }

    override def toString: String =
      s"""[init]  ${inits.mkString(",")}
         |[vars]  ${val_env.map(kv => s"${kv._1}=${kv._2.value}").mkString(", ")}
         |[act]   ${showEdges(act)}
         |[edges] ${showEdgeMap(edg)}
         |[on]    ${showEdgeMap(on)}
         |[off]   ${showEdgeMap(off)}
         |[weights] ${weights.map(kv => s"${showEdge(kv._1)} -> ${fmtWeight(kv._2)}").mkString(", ")}""".stripMargin

    def states = for (src, dests) <- edg.toSet; (d, _, _) <- dests; st <- Set(src, d) yield st

    private def withEdge(edge: Edge, cond: Option[Condition], upd: List[Statement], w: Double, agg: String, wExpr: Option[UpdateExpr]): RxGraph = {
      val dynExpr = wExpr.filterNot(UpdateExpr.isConstant)
      this.copy(
        lbls = add(edge._4 -> edge, lbls), act = act + edge,
        edgeConditions = edgeConditions + (edge -> cond), edgeUpdates = edgeUpdates + (edge -> upd),
        weights = weights + (edge -> w),
        edgeAggregations = if (agg.nonEmpty) edgeAggregations + (edge -> agg) else edgeAggregations,
        weightExprs = dynExpr.map(e => weightExprs + (edge -> e)).getOrElse(weightExprs - edge)
      )
    }

    def addEdge(s1:QName, s2:QName, transId:QName, label:QName, cond: Option[Condition] = None, upd: List[Statement] = Nil, w: Double = 1.0, agg: String = "", wExpr: Option[UpdateExpr] = None) =
      withEdge((s1, s2, transId, label), cond, upd, w, agg, wExpr).copy(edg = add(s1 -> (s2, transId, label), edg))

    def addOn(s1: QName, s2: QName, transId: QName, label: QName, cond: Option[Condition] = None, upd: List[Statement] = Nil, w: Double = 1.0, agg: String = "", wExpr: Option[UpdateExpr] = None) =
      withEdge((s1, s2, transId, label), cond, upd, w, agg, wExpr).copy(on = add(s1 -> (s2, transId, label), on))

    def addOff(s1: QName, s2: QName, transId: QName, label: QName, cond: Option[Condition] = None, upd: List[Statement] = Nil, w: Double = 1.0, agg: String = "", wExpr: Option[UpdateExpr] = None) =
      withEdge((s1, s2, transId, label), cond, upd, w, agg, wExpr).copy(off = add(s1 -> (s2, transId, label), off))

    def deactivate(s1:QName, s2:QName, tId:QName, l:QName) = this.copy(act = act - ((s1, s2, tId, l)))
    def addInit(s:QName) = this.copy(inits = inits + s)
    def addVariable(name: QName, value: RuntimeValue) = this.copy(val_env = val_env + (name -> value))

    def ++(r:RxGraph): RxGraph =
      this.withSettingsOf(r).copy(
        edg = join(edg, r.edg), on = join(on, r.on), off = join(off, r.off),
        lbls = join(lbls, r.lbls), inits = inits ++ r.inits, act = act ++ r.act,
        val_env = val_env ++ r.val_env, functions = functions ++ r.functions,
        edgeConditions = edgeConditions ++ r.edgeConditions, edgeUpdates = edgeUpdates ++ r.edgeUpdates,
        weights = weights ++ r.weights, edgeAggregations = edgeAggregations ++ r.edgeAggregations,
        weightExprs = weightExprs ++ r.weightExprs
      )

  object RxGraph:
    def apply(): RxGraph = RxGraph(
      edg = Map().withDefaultValue(Set()), on = Map().withDefaultValue(Set()), off = Map().withDefaultValue(Set()),
      lbls = Map().withDefaultValue(Set()), inits = Set(), act = Set(),
      val_env = Map(), functions = Map(),
      edgeConditions = Map().withDefaultValue(None), edgeUpdates = Map().withDefaultValue(Nil),
      weights = Map(), edgeAggregations = Map(),
      trainingMode = false, paradigm = "probabilistic", distributionMode = "normalize",
      weightExprs = Map()
    )

    def toMermaid(rx: RxGraph): String =
      var i = -1
      def fresh(): Int = {i += 1; i}
      s"flowchart LR\n${
        drawEdges(rx.edg, rx, fresh, ">", "stroke:black, stroke-width:2px",(x,y) => Set(x.toString), withConditions = true)}${
        drawEdges(rx.on, rx, fresh, ">", "stroke:blue, stroke-width:3px",getLabel, withConditions = true)}${
        drawEdges(rx.off,rx, fresh, "x", "stroke:red, stroke-width:3px",getLabel, withConditions = true)}${
        (for s<-rx.inits yield s"  style $s fill:#8f7,stroke:#363,stroke-width:4px\n").mkString
      }"

    def toMermaidPlain(rx: RxGraph): String =
      var i = -1
      def fresh(): Int = {i += 1; i}
      s"flowchart LR\n${drawEdges(rx.edg, rx, fresh, ">", "stroke:black, stroke-width:2px",(x,y) => Set(x.toString),simple=true, withConditions = false)}${(for s<-rx.inits yield s"  style $s fill:#8f7,stroke:#363,stroke-width:4px\n").mkString }"

    private def cleanId(a: Any, b: Any, id: Any, lbl: Any): String = s"$a$b$id$lbl".replaceAll("[^a-zA-Z0-9]", "")
    private def getLabel(n: QName, rx: RxGraph): Set[String] = for (edge <- rx.lbls.getOrElse(n, Set())) yield cleanId(edge._1, edge._2, edge._3, edge._4)

    private def drawEdges(es: EdgeMap, rx: RxGraph, fresh: () => Int, tip: String, style: String, getEnds: (QName, RxGraph) => Set[String], simple: Boolean = false, withConditions: Boolean = false): String =
      (for
        (a, bs) <- es.toList
        (b, transId, lbl) <- bs.toList
        a2 <- getEnds(a, rx).toList
        b2 <- getEnds(b, rx).toList
      yield
        val edge: Edge = (a, b, transId, lbl)
        val isGloballyActive = rx.act(edge)
        val isConditionSatisfied = rx.edgeConditions.getOrElse(edge, None).forall(c => RxSemantics.evalCondition(c, rx))
        val line = if (isGloballyActive && isConditionSatisfied) then "---" else "-.-"

        val qNameLabel = if transId == lbl then lbl.show else s"${lbl.show}(${transId.show})"
        val updText    = if withConditions then rx.edgeUpdates.getOrElse(edge, Nil).map(_.toString).mkString(" ") else ""
        val condText   = if withConditions then rx.edgeConditions.getOrElse(edge, None).map(_.toMermaidString).getOrElse("") else ""
        val p = rx.weights.getOrElse(edge, 1.0)
        val weightText = if (withConditions && p != 1.0) f"($p%.3f)".replace(",", ".") else ""
        
        val combined   = List(condText, qNameLabel, weightText, updText).filter(_.nonEmpty).mkString(" ")
        val edgeLabel = if combined.nonEmpty then s"|\"${combined}\"|" else ""

        if lbl.n.isEmpty && transId.n.isEmpty then s"  $a2 $line$tip $edgeLabel $b2\n  linkStyle ${fresh()} $style\n"
        else if simple then s"  $a2 $line$tip $edgeLabel $b2\n  linkStyle ${fresh()} $style\n"
        else
          val anchorId = cleanId(a, b, transId, lbl)
          s"  $a2 $line $anchorId( ) $line$tip $edgeLabel $b2\n  style $anchorId width: 0\n  linkStyle ${fresh()} $style\n  linkStyle ${fresh()} $style\n"
      ).mkString