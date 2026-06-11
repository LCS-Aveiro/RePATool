package rta.syntax

import rta.backend.RxSemantics
import rta.syntax.Program2.EdgeMap
import rta.syntax.{Condition, CounterUpdate, UpdateExpr, Statement, UpdateStmt, IfThenStmt}
import scala.annotation.tailrec
import scala.language.implicitConversions

object Program2:

  type Rel[A,B] = Map[A,Set[B]]
  def empty[A,B] = Map[A,Set[B]]().withDefaultValue(Set())
  def add[A,B](ab:(A,B), r:Rel[A,B]) = r + (ab._1 -> (r(ab._1)+(ab._2)))
  def join[A,B](r1:Rel[A,B], r2:Rel[A,B]) = r1 ++ (r2.map(ab => ab._1 -> (r1(ab._1)++(ab._2))))

  private def isGlobalControlVar(q: QName): Boolean = q.n.mkString.contains("_")

  case class QName(n:List[String]):
    override def toString = n.mkString("/")
    def show = if n.isEmpty then "-" else toString
    def /(other:QName) = if (other.n.isEmpty) other else if (n.isEmpty) other else QName(n ::: other.n)
    def /(other:String) = QName(n:::List(other))

    def /(e:EdgeMap):EdgeMap =
      e.map((src, targets) => (this / src) -> targets.map((to, id, lbl) => (this / to, this / id, this / lbl)))

    def /(es:Edges): Edges =
      es.map((src, to, id, lbl) => (this / src, this / to, this / id, this / lbl))

    def /-(lblsMap:Map[QName,Edges]): Map[QName,Edges] =
      lblsMap.map((lbl, edges) => (this / lbl) -> (this / edges))

    def /-(ns:Set[QName]): Set[QName] =
      ns.map(n => this / n)
    
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
        edgeAggregations = rx.edgeAggregations.map { case (edge, agg) => 
          (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> agg 
        },
        edgeConditions = rx.edgeConditions.map { case (edge, condOpt) =>
          (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> condOpt.map(c => applyPrefixToCondition(this, c))
        },
        edgeUpdates = rx.edgeUpdates.map { case (edge, stmtList) =>
          (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> stmtList.map(stmt => applyPrefixToStatement(this, stmt))
        },
        weights = rx.weights.map { case (edge, w) => (this / edge._1, this / edge._2, this / edge._3, this / edge._4) -> w }
      )
  
  def applyPrefixToCondition(prefix: QName, cond: Condition): Condition = {
    cond match {
      case Condition.AtomicCond(left, op, right) =>
        val newLeft = if (isGlobalControlVar(left)) left else prefix / left
        val newRight = right match {
          case Left(i) => Left(i)
          case Right(q) => if (isGlobalControlVar(q)) Right(q) else Right(prefix / q)
        }
        Condition.AtomicCond(newLeft, op, newRight)
      case Condition.WeightCheck(lbl, metric, op, value) =>
        Condition.WeightCheck(if (isGlobalControlVar(lbl)) lbl else prefix / lbl, metric, op, value)
      case Condition.And(l, r) => Condition.And(applyPrefixToCondition(prefix, l), applyPrefixToCondition(prefix, r))
      case Condition.Or(l, r) => Condition.Or(applyPrefixToCondition(prefix, l), applyPrefixToCondition(prefix, r))
    }
  }

  def applyPrefixToStatement(prefix: QName, stmt: Statement): Statement = {
    stmt match {
      case UpdateStmt(upd) =>
        val newVar = if (isGlobalControlVar(upd.variable)) upd.variable else prefix / upd.variable
        val newExpr = upd.expr match {
            case UpdateExpr.Add(v, e) => UpdateExpr.Add(if(isGlobalControlVar(v)) v else prefix/v, e match {
                case Right(q) if !isGlobalControlVar(q) => Right(prefix/q)
                case other => other
            })
            case UpdateExpr.Sub(v, e) => UpdateExpr.Sub(if(isGlobalControlVar(v)) v else prefix/v, e match {
                case Right(q) if !isGlobalControlVar(q) => Right(prefix/q)
                case other => other
            })
            case UpdateExpr.Var(q) if !isGlobalControlVar(q) => UpdateExpr.Var(prefix/q)
            case other => other
        }
        UpdateStmt(upd.copy(variable = newVar, expr = newExpr))
      case IfThenStmt(cond, thenStmts) =>
        IfThenStmt(
          applyPrefixToCondition(prefix, cond),
          thenStmts.map(s => applyPrefixToStatement(prefix, s))
        )
    }
  }

  type Edge = (QName, QName, QName, QName)
  type Edges = Set[Edge]
  type EdgeMap = Rel[QName, (QName, QName, QName)]

  def showEdge(e: Edge): String = {
    val (from, to, transId, label) = e
    if (transId == label) {
      s"${from.show} ---> ${to.show} : ${label.show}"
    } else {
      s"${from.show} -${transId.show}-> ${to.show} : ${label.show}"
    }
  }
  
  def showEdges(abc:Edges): String =
    abc.map(showEdge).mkString(", ")

  private def showEdgeMap(abc:EdgeMap): String =
    val es = for (a, bcs) <- abc.toSet; (b, id, lbl) <- bcs yield (a, b, id, lbl)
    showEdges(es)


  case class RxGraph(edg:EdgeMap,
                     on:EdgeMap, off: EdgeMap,
                     lbls: Map[QName,Edges],
                     inits: Set[QName],
                     act: Edges,
                     val_env: Map[QName, Int], 
                     edgeConditions: Map[Edge, Option[Condition]], 
                     edgeUpdates: Map[Edge, List[Statement]],
                     weights: Map[Edge, Double],
                     edgeAggregations: Map[Edge, String],
                     trainingMode: Boolean = false,
                     distributionMode: String = "normalize",
                     trainingMethod: String = "laplace",
                     trainingAgg: String = "arith",
                     trainingLambda: Double = 1.0
                    ):
    def union(other: RxGraph, aggName: String): RxGraph = {
      val allEdgeKeys = this.lbls.keySet ++ other.lbls.keySet
      var newRx = RxGraph().copy(trainingMode = this.trainingMode || other.trainingMode)

      newRx = newRx.copy(
        inits = this.inits ++ other.inits,
        val_env = this.val_env ++ other.val_env
      )

      for (label <- allEdgeKeys) {
        val edgesM = this.lbls.getOrElse(label, Set())
        val edgesN = other.lbls.getOrElse(label, Set())
        val allEdgesForLabel = edgesM ++ edgesN

        for (e <- allEdgesForLabel) {
          val inM = edgesM.contains(e)
          val inN = edgesN.contains(e)

          val wM = this.weights.getOrElse(e, 0.0)
          val wN = other.weights.getOrElse(e, 0.0)
          val finalWeight = if (inM && inN) {
            Aggregation.compute(aggName, wM, wN, (wM + wN) / 2) 
          } else if (inM) wM else wN

          val isActM = this.act.contains(e)
          val isActN = other.act.contains(e)
          val shouldBeActive = isActM || isActN

          
          val isStandard = this.edg.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e) || 
                          other.edg.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e)
          
          val isOnRule = this.on.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e) || 
                        other.on.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e)

          val isOffRule = this.off.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e) || 
                          other.off.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e)

          val cond = this.edgeConditions.get(e).flatten.orElse(other.edgeConditions.get(e).flatten)
          val upd = this.edgeUpdates.getOrElse(e, other.edgeUpdates.getOrElse(e, Nil))

          if (isStandard) {
            newRx = newRx.addEdge(e._1, e._2, e._3, e._4, cond, upd, finalWeight)
          } 
          if (isOnRule) {
            newRx = newRx.addOn(e._1, e._2, e._3, e._4, cond, upd, finalWeight)
          }
          if (isOffRule) {
            newRx = newRx.addOff(e._1, e._2, e._3, e._4, cond, upd, finalWeight)
          }
          
          if (!shouldBeActive) newRx = newRx.deactivate(e._1, e._2, e._3, e._4)
        }
      }
      newRx
    }

    def applyDeltaCut(delta: Double): RxGraph = {
      val newEdg = edg.map { case (src, targets) =>
        src -> targets.filter(t => weights.getOrElse((src, t._1, t._2, t._3), 1.0) >= delta)
      }.filter(_._2.nonEmpty)

      val newOn = on.map { case (src, targets) =>
        src -> targets.filter(t => weights.getOrElse((src, t._1, t._2, t._3), 0.1) >= delta)
      }.filter(_._2.nonEmpty)

      val newOff = off.map { case (src, targets) =>
        src -> targets.filter(t => weights.getOrElse((src, t._1, t._2, t._3), 0.1) >= delta)
      }.filter(_._2.nonEmpty)

      this.copy(
        edg = newEdg.toMap.withDefaultValue(Set()), 
        on = newOn.toMap.withDefaultValue(Set()), 
        off = newOff.toMap.withDefaultValue(Set())
      )
    }


    def intersection(other: RxGraph, aggName: String): RxGraph = {
      val commonLabels = this.lbls.keySet.intersect(other.lbls.keySet)
      val commonInits = this.inits.intersect(other.inits)
      
      var newRx = RxGraph().copy(
        inits = commonInits,
        val_env = this.val_env.filter { case (k, v) => other.val_env.get(k).contains(v) }
      )

      for (label <- commonLabels) {
        val commonEdges = this.lbls(label).intersect(other.lbls(label))
        
        for (e <- commonEdges) {
          val wM = this.weights.getOrElse(e, 0.0)
          val wN = other.weights.getOrElse(e, 0.0)
          val finalWeight = Aggregation.compute(aggName, wM, wN, (wM + wN) / 2)

          val isActM = this.act.contains(e)
          val isActN = other.act.contains(e)
          val shouldBeActive = isActM && isActN

          val isStandard = this.edg.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e) &&
                          other.edg.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e)
          
          val isOnRule = this.on.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e) &&
                        other.on.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e)

          val isOffRule = this.off.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e) &&
                          other.off.getOrElse(e._1, Set()).exists(t => (e._1, t._1, t._2, t._3) == e)

          val cond = this.edgeConditions.get(e).flatten 
          val upd = this.edgeUpdates.getOrElse(e, Nil)

          if (isStandard) {
            newRx = newRx.addEdge(e._1, e._2, e._3, e._4, cond, upd, finalWeight)
          }
          if (isOnRule) {
            newRx = newRx.addOn(e._1, e._2, e._3, e._4, cond, upd, finalWeight)
          }
          if (isOffRule) {
            newRx = newRx.addOff(e._1, e._2, e._3, e._4, cond, upd, finalWeight)
          }

          if (!shouldBeActive) {
            newRx = newRx.deactivate(e._1, e._2, e._3, e._4)
          }
        }
      }
      newRx
    }


    def toRta: String = {
      val sb = new StringBuilder
      
      inits.foreach(i => sb.append(s"init ${i.show}\n"))
      sb.append("\n")

      val_env.foreach { case (k, v) => 
        if (!k.show.startsWith("__")) sb.append(s"int ${k.show} = $v\n")
      }
      if (val_env.nonEmpty) sb.append("\n")

      val allSimple = for {
        (src, targets) <- edg.toList
        (trg, id, lbl) <- targets
      } yield (src, trg, id, lbl)

      allSimple.sortBy(_._1.toString).foreach { case (s, t, id, l) =>
        val edge = (s, t, id, l)
        val w = weights.getOrElse(edge, 1.0)
        val wFormatted = f"$w%.3f"
        val isDisabled = !act.contains(edge)

        val agg = edgeAggregations.getOrElse(edge, "arith")
        val aggStr = if (agg != "arith") s" $agg" else ""
        
        val line = if (id == l) s"${s.show} ---> ${t.show}: ${l.show}"
                  else s"${s.show} -${id.show}-> ${t.show}: ${l.show}"
        
        sb.append(s"$line ($wFormatted)$aggStr${if (isDisabled) " disabled" else ""}\n")
      }

      val allOn = for {
        (src, targets) <- on.toList
        (trg, id, lbl) <- targets
      } yield (src, trg, id, lbl)

      allOn.foreach { case (s, t, id, l) =>
        val edge = (s, t, id, l)
        val w = weights.getOrElse(edge, 0.1)
        val wFormatted = f"$w%.3f"
        sb.append(s"${s.show} ->> ${t.show}: ${l.show} ($wFormatted)\n")
      }

      val allOff = for {
        (src, targets) <- off.toList
        (trg, id, lbl) <- targets
      } yield (src, trg, id, lbl)

      allOff.foreach { case (s, t, id, l) =>
        val edge = (s, t, id, l)
        val w = weights.getOrElse(edge, 0.1)
        val wFormatted = f"$w%.3f"
        sb.append(s"${s.show} --x ${t.show}: ${l.show} ($wFormatted)\n")
      }

      sb.toString()
    }

    def showSimple: String =
      s"[at] ${inits.mkString(",")}" +
      s"${if val_env.nonEmpty then s" [vars] ${val_env.map(kv => s"${kv._1}=${kv._2}").mkString(", ")}" else ""}" +
      s" [active] ${showEdges(act)}"

    override def toString: String =
      s"""[init]  ${inits.mkString(",")}
         |[vars]  ${val_env.map(kv => s"${kv._1}=${kv._2}").mkString(", ")}
         |[act]   ${showEdges(act)}
         |[edges] ${showEdgeMap(edg)}
         |[on]    ${showEdgeMap(on)}
         |[off]   ${showEdgeMap(off)}
         |[weights] ${weights.map(kv => s"${showEdge(kv._1)} -> ${f"${kv._2}%.3f"}").mkString(", ")}
         |[conds] ${edgeConditions.filter(_._2.isDefined).map(kv => s"${showEdge(kv._1)} -> ${kv._2.get.toMermaidString}").mkString(", ")}
         |[upd]   ${edgeUpdates.filter(_._2.nonEmpty).map(kv => s"${showEdge(kv._1)} -> ${kv._2.map(_.toString).mkString("; ")}").mkString(", ")}"""
    
    def states =
      for (src, dests) <- edg.toSet; (d, _, _) <- dests; st <- Set(src, d) yield st

    def addEdge(s1:QName, s2:QName, transId:QName, label:QName, cond: Option[Condition] = None, upd: List[Statement] = Nil, w: Double = 1.0, agg: String = "") = {
      val edge: Edge = (s1, s2, transId, label)
      val newAggs = if (agg.nonEmpty) edgeAggregations + (edge -> agg) else edgeAggregations
      
      this.copy(
        edg = add(s1 -> (s2, transId, label), edg), 
        lbls = add(label -> edge, lbls),           
        act = act + edge,
        edgeConditions = edgeConditions + (edge -> cond),
        edgeUpdates = edgeUpdates + (edge -> upd),
        weights = weights + (edge -> w),
        edgeAggregations = newAggs
      )
    }

    def addOn(s1: QName, s2: QName, transId: QName, label: QName, cond: Option[Condition] = None, upd: List[Statement] = Nil, w: Double = 1.0, agg: String = "") = {
      val edge: Edge = (s1, s2, transId, label)
      val newAggs = if (agg.nonEmpty) edgeAggregations + (edge -> agg) else edgeAggregations
      
      this.copy(
        on = add(s1 -> (s2, transId, label), on),
        lbls = add(label -> edge, lbls),
        act = act + edge,
        edgeConditions = edgeConditions + (edge -> cond),
        edgeUpdates = edgeUpdates + (edge -> upd),
        weights = weights + (edge -> w),
        edgeAggregations = newAggs)
    }

    def addOff(s1: QName, s2: QName, transId: QName, label: QName, cond: Option[Condition] = None, upd: List[Statement] = Nil, w: Double = 1.0, agg: String = "") = {
      val edge: Edge = (s1, s2, transId, label)
      val newAggs = if (agg.nonEmpty) edgeAggregations + (edge -> agg) else edgeAggregations
      
      this.copy(
        off = add(s1 -> (s2, transId, label), off),
        lbls = add(label -> edge, lbls),
        act = act + edge,
        edgeConditions = edgeConditions + (edge -> cond),
        edgeUpdates = edgeUpdates + (edge -> upd),
        weights = weights + (edge -> w),
        edgeAggregations = newAggs)
    }

    def deactivate(s1:QName, s2:QName, tId:QName, l:QName) =
      this.copy(act = act - ((s1, s2, tId, l)))

    def addInit(s:QName) =
      this.copy(inits = inits + s)

    def addVariable(name: QName, value: Int) =
      this.copy(val_env = val_env + (name -> value))

    def ++(r:RxGraph) =
      RxGraph(
        join(edg,r.edg),join(on,r.on),join(off,r.off),
        join(lbls,r.lbls),inits++r.inits,act++r.act,
        val_env ++ r.val_env, 
        edgeConditions ++ r.edgeConditions, 
        edgeUpdates ++ r.edgeUpdates,
        weights ++ r.weights,
        edgeAggregations ++ r.edgeAggregations,
        trainingMode = this.trainingMode || r.trainingMode,
        distributionMode = this.distributionMode
      )


  object RxGraph: 
    def apply(): RxGraph = RxGraph(
      edg = Map().withDefaultValue(Set()),
      on = Map().withDefaultValue(Set()),
      off = Map().withDefaultValue(Set()),
      lbls = Map().withDefaultValue(Set()),
      inits = Set(),
      act = Set(),
      val_env = Map(), 
      edgeConditions = Map().withDefaultValue(None), 
      edgeUpdates = Map().withDefaultValue(Nil), 
      weights = Map(),
      edgeAggregations = Map(), 
      trainingMode = false,
      distributionMode = "normalize"
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
      s"flowchart LR\n${
        drawEdges(rx.edg, rx, fresh, ">", "stroke:black, stroke-width:2px",(x,y) => Set(x.toString),simple=true, withConditions = false)}${
        (for s<-rx.inits yield s"  style $s fill:#8f7,stroke:#363,stroke-width:4px\n").mkString 
      }"

    private def cleanId(a: Any, b: Any, id: Any, lbl: Any): String =
      s"$a$b$id$lbl".replaceAll("[^a-zA-Z0-9]", "")

    private def getLabel(n: QName, rx: RxGraph): Set[String] =
      for (edge <- rx.lbls.getOrElse(n, Set())) 
        yield cleanId(edge._1, edge._2, edge._3, edge._4)

    private def drawEdges(
      es: EdgeMap,
      rx: RxGraph,
      fresh: () => Int,
      tip: String,
      style: String,
      getEnds: (QName, RxGraph) => Set[String],
      simple: Boolean = false,
      withConditions: Boolean = false
    ): String =
      (for
        (a, bs) <- es.toList
        (b, transId, lbl) <- bs.toList
        a2 <- getEnds(a, rx).toList
        b2 <- getEnds(b, rx).toList
      yield
        val edge: Edge = (a, b, transId, lbl)
        val isGloballyActive = rx.act(edge)
        val isConditionSatisfied = rx.edgeConditions.getOrElse(edge, None).forall(Condition.evaluate(_, rx))
        val line = if (isGloballyActive && isConditionSatisfied) then "---" else "-.-"

        val qNameLabel = if transId == lbl then lbl.show else s"${lbl.show}(${transId.show})"
        val updText    = if withConditions then rx.edgeUpdates.getOrElse(edge, Nil).map(_.toString).mkString(" ") else ""
        val condText   = if withConditions then rx.edgeConditions.getOrElse(edge, None).map(_.toMermaidString).getOrElse("") else ""
        val p = rx.weights.getOrElse(edge, 1.0)
        val weightText = if (withConditions && p != 1.0) f"($p%.3f)" else ""
        
        val combined   = List(condText, qNameLabel, weightText, updText).filter(_.nonEmpty).mkString(" ")
        val edgeLabel = if combined.nonEmpty then s"|\"${combined}\"|" else ""

        if lbl.n.isEmpty && transId.n.isEmpty then
          s"  $a2 $line$tip $edgeLabel $b2\n" +
          s"  linkStyle ${fresh()} $style\n"
        else if simple then
          s"  $a2 $line$tip $edgeLabel $b2\n" +
          s"  linkStyle ${fresh()} $style\n"
        else
          val anchorId = cleanId(a, b, transId, lbl)
          s"  $a2 $line $anchorId( ) $line$tip $edgeLabel $b2\n" +
          s"  style $anchorId width: 0\n" +
          s"  linkStyle ${fresh()} $style\n" +
          s"  linkStyle ${fresh()} $style\n"
      ).mkString

  object Examples:
    implicit def s2n(str:String): QName = QName(List(str))
    val a = s2n("a")
    val t1 = s2n("t1")
    val s1 = s2n("s1")
    val s2 = s2n("s2")

    val g1 = RxGraph()
      .addInit(s1)
      .addEdge(s1, s2, t1, a)
      .addOff(a, a, s2n("rule1"), s2n("off-a"))