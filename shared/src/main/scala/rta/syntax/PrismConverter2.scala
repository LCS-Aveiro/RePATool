package rta.backend

import rta.syntax.Program2.{QName, RxGraph, Edge}
import rta.syntax.{Condition, UpdateExpr, Statement, AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt}
object PrismConverter2 {
  
  val SCALE = 10000

  private def sanitize(n: String) = n.replaceAll("[^a-zA-Z0-9_]", "_")

  private def conditionToPrism(c: Condition): String = c match {
    case Condition.AtomicCond(left, op, right) => s"${updateExprToString(left)}${if(op=="==")"=" else op}${updateExprToString(right)}"
    case Condition.WeightCheck(lbl, metric, op, v) => "false" 
    case Condition.And(c1, c2) => s"(${conditionToPrism(c1)} & ${conditionToPrism(c2)})"
    case Condition.Or(c1, c2)  => s"(${conditionToPrism(c1)} | ${conditionToPrism(c2)})"
  }

  private def updateExprToString(e: UpdateExpr): String = e match {
    case UpdateExpr.LitInt(i) => i.toString
    case UpdateExpr.LitFloat(f) => if(f == f.toLong) f.toLong.toString else f.toString
    case UpdateExpr.LitBool(b) => if (b) "true" else "false"
    case UpdateExpr.Var(q) => sanitize(q.show)
    case UpdateExpr.MathOp(l, op, r) => s"(${updateExprToString(l)} $op ${updateExprToString(r)})"
    case _ => "0" 
  }

  private def getVariableUpdates(stmts: List[Statement], ctxCond: Option[Condition] = None): List[(QName, String, Option[Condition])] = {
    stmts.flatMap {
      case AssignStmt(variable, expr) => List((variable, updateExprToString(expr), ctxCond))
      case IfThenStmt(c, ts) =>
        val newCond = ctxCond.map(ctx => Condition.And(ctx, c)).getOrElse(c)
        getVariableUpdates(ts, Some(newCond))
      case _ => Nil 
    }
  }

  def isSimpleEdge(e: Edge, rx: RxGraph): Boolean = {
    rx.edg.getOrElse(e._1, Set()).exists(t => t._1 == e._2 && t._2 == e._3 && t._3 == e._4)
  }

  def getPaths(startLbl: QName, endRule: Edge, allRules: Set[Edge], visited: Set[QName] = Set()): List[List[Edge]] = {
    if (visited.contains(startLbl)) return Nil
    val directRules = allRules.filter(_._1 == startLbl)
    var paths = List[List[Edge]]()
    for (r <- directRules) {
      if (r == endRule) paths = List(r) :: paths
      else if (r._4.n.nonEmpty) {
        val subPaths = getPaths(r._4, endRule, allRules, visited + startLbl)
        paths = paths ++ subPaths.map(p => r :: p)
      }
    }
    paths
  }

  def apply(rx: RxGraph, currentCode: String): String = {
    val sb = new StringBuilder
    val formulasSb = new StringBuilder
    
    sb.append("// =========================================================\n")
    sb.append("// DTMC - RePA\n")
    sb.append("// =========================================================\n\n")
    sb.append("dtmc\n\n")

    val allStates = rx.states.toList.sortBy(_.toString)
    val stateToId = allStates.zipWithIndex.toMap
    val maxState = Math.max(1, allStates.size - 1)

    val allRules: Set[Edge] = (
      rx.on.flatMap { case (src, tgts) => tgts.map(t => (src, t._1, t._2, t._3)) }.toSet ++
      rx.off.flatMap { case (src, tgts) => tgts.map(t => (src, t._1, t._2, t._3)) }.toSet
    )
    
    val actionLabels = rx.edg.values.flatten.map(_._3).toSet
    val toggledLabels = allRules.map(_._2).filter(_.n.nonEmpty).toSet

    val targetedLabels = allRules.map(_._2).filter(_.n.nonEmpty).toSet
    val mutableSourceStates = targetedLabels.flatMap(lbl => rx.lbls.getOrElse(lbl, Set())).map(_._1)
    val mutableLabels: Set[QName] = (mutableSourceStates.flatMap(s => rx.edg.getOrElse(s, Set())).map(_._3).filter(_.n.nonEmpty).filter(_.show != "-") ++ targetedLabels).toSet

    def actCheckExpr(lbl: QName): String = {
      if (lbl.n.isEmpty || lbl.show == "-") "true"
      else if (toggledLabels.contains(lbl)) s"${sanitize(lbl.show)}_act=1"
      else if (rx.act.exists(_._4 == lbl)) "true"
      else "false"
    }

    def fullActCheckExpr(e: Edge): String = {
      val baseCheck = actCheckExpr(e._4)
      val condOpt = rx.edgeConditions.get(e).flatten
      condOpt match {
        case Some(c) =>
          val cStr = conditionToPrism(c)
          if (baseCheck == "true") cStr
          else if (baseCheck == "false") "false"
          else s"($baseCheck & $cStr)"
        case None => baseCheck
      }
    }

    formulasSb.append("// --- Cascade Logic & Dynamic Weights per Trigger ---\n")

    val generatedUpdates = scala.collection.mutable.Map[(QName, QName), String]()
    val generatedNextActs = scala.collection.mutable.Map[(QName, QName), String]()

    for (tLbl <- actionLabels.toList.sortBy(_.toString)) {
      val sT = sanitize(tLbl.show)
      val reachableRules = allRules.filter(r => getPaths(tLbl, r, allRules).nonEmpty)

      if (reachableRules.nonEmpty) {
        formulasSb.append(s"\n// Cascades when action '$sT' is fired:\n")

        for (r <- reachableRules.toList.sortBy(_._4.toString)) {
          val paths = getPaths(tLbl, r, allRules)
          val conds = paths.map { p =>
            val pConds = p.map(e => fullActCheckExpr(e)).filter(_ != "true")
            if (pConds.isEmpty) "true" else if (pConds.contains("false")) "false" else pConds.mkString(" & ")
          }.filter(_ != "false")
          
          val firesExpr = if (conds.isEmpty) "false" else if (conds.contains("true")) "true" else conds.map(c => s"($c)").distinct.mkString(" | ")
          val ruleIdStr = sanitize(r._4.show) + "_" + sanitize(r._1.show) + "_" + sanitize(r._2.show)
          formulasSb.append(s"formula fires_${sT}_${ruleIdStr} = $firesExpr;\n")
        }

        for (lbl <- toggledLabels.toList.sortBy(_.toString)) {
          val sLbl = sanitize(lbl.show)
          val onRules = reachableRules.filter(r => r._2 == lbl && rx.on.getOrElse(r._1, Set()).contains((r._2, r._3, r._4)))
          val offRules = reachableRules.filter(r => r._2 == lbl && rx.off.getOrElse(r._1, Set()).contains((r._2, r._3, r._4)))

          val onExpr = if (onRules.isEmpty) "false" else onRules.map(r => s"fires_${sT}_${sanitize(r._4.show)}_${sanitize(r._1.show)}_${sanitize(r._2.show)}").mkString(" | ")
          val offExpr = if (offRules.isEmpty) "false" else offRules.map(r => s"fires_${sT}_${sanitize(r._4.show)}_${sanitize(r._1.show)}_${sanitize(r._2.show)}").mkString(" | ")

          val currAct = actCheckExpr(lbl)
          val nextActFormula = s"next_act_${sT}_${sLbl}"
          formulasSb.append(s"formula $nextActFormula = ($offExpr) ? false : (($onExpr) ? true : ($currAct));\n")
          generatedNextActs((tLbl, lbl)) = nextActFormula
        }

        val targetedEdges = reachableRules.flatMap(r => rx.lbls.getOrElse(r._2, Set()))
        
        val uniqueTargetedLabels = targetedEdges.map(_._4).toSet

        for (tgtLbl <- uniqueTargetedLabels.toList.sortBy(_.toString)) {
          val sEdge = sanitize(tgtLbl.show)
          val rulesTargetingE = reachableRules.filter(r => r._2 == tgtLbl)
          
          val repEdgeOpt = rx.lbls.getOrElse(tgtLbl, Set()).headOption
          var currWeight = if (mutableLabels.contains(tgtLbl)) s"w_int_$sEdge" 
                           else s"${(repEdgeOpt.map(rx.weights.getOrElse(_, 1.0)).getOrElse(1.0) * SCALE).toInt}"

          for ((r, i) <- rulesTargetingE.toList.sortBy(_._4.toString).zipWithIndex) {
            val rIdStr = sanitize(r._4.show) + "_" + sanitize(r._1.show) + "_" + sanitize(r._2.show)
            
            val triggerEdgeOpt = rx.lbls.get(tLbl).flatMap(_.headOption)
            val triggerW = if (mutableLabels.contains(tLbl)) s"w_int_${sanitize(tLbl.show)}" else s"${(triggerEdgeOpt.map(rx.weights.getOrElse(_, 1.0)).getOrElse(1.0) * SCALE).toInt}"
            
            val rW = if (mutableLabels.contains(r._4)) s"w_int_${sanitize(r._4.show)}" else s"${(rx.weights.getOrElse(r, 0.1) * SCALE).toInt}"
            
            val agg = rx.edgeAggregations.getOrElse(r, "arith")

            val newW = agg match {
              case "prod" => s"floor((floor(($triggerW * $rW) / 10000) * $currWeight) / 10000)"
              case "max" => s"max($triggerW, max($rW, $currWeight))"
              case "min" => s"min($triggerW, min($rW, $currWeight))"
              case _ => s"floor(($triggerW + $rW + $currWeight) / 3)"
            }
            
            val stepName = s"upd_${sT}_${sEdge}_step$i"
            formulasSb.append(s"formula $stepName = fires_${sT}_${rIdStr} ? ($newW) : ($currWeight);\n")
            currWeight = stepName
          }
          formulasSb.append(s"formula base_upd_${sT}_${sEdge} = min(10000, max(0, $currWeight));\n")
        }

        val simpleTargeted = targetedEdges.filter(e => isSimpleEdge(e, rx))
        val ruleTargeted = targetedEdges -- simpleTargeted
        
        val ruleTargetedLabels = ruleTargeted.map(_._4).toSet

        for (tgtLbl <- ruleTargetedLabels.toList.sortBy(_.toString)) {
          val sE = sanitize(tgtLbl.show)
          val fName = s"final_upd_${sT}_$sE"
          formulasSb.append(s"formula $fName = base_upd_${sT}_$sE;\n")
          generatedUpdates((tLbl, tgtLbl)) = fName
        }

        val dirtyStates = simpleTargeted.map(_._1).toSet
        for (st <- dirtyStates.toList.sortBy(_.toString)) {
          val outEdges = rx.edg.getOrElse(st, Set()).map(t => (st, t._1, t._2, t._3)).toList
          val modifiedOut = outEdges.filter(e => simpleTargeted.contains(e))
          val unmodifiedOut = outEdges.filterNot(e => simpleTargeted.contains(e))

          def activeMathCheck(e: Edge, baseExpr: String): String = {
             val nextAct = generatedNextActs.getOrElse((tLbl, e._4), actCheckExpr(e._4))
             val condOpt = rx.edgeConditions.get(e).flatten
             
             val combinedCond = condOpt match {
               case Some(c) =>
                 val cStr = conditionToPrism(c)
                 if (nextAct == "false") "false"
                 else if (nextAct == "true") cStr
                 else s"($nextAct & $cStr)"
               case None => nextAct
             }

             if (combinedCond == "false") "0"
             else if (combinedCond == "true") baseExpr
             else s"($combinedCond ? $baseExpr : 0)"
          }

          val sumModExp = if (modifiedOut.isEmpty) "0" else modifiedOut.map(e => activeMathCheck(e, s"base_upd_${sT}_${sanitize(e._4.show)}")).mkString(" + ")
          val sumUnmodBase = if (unmodifiedOut.isEmpty) "0" else unmodifiedOut.map(e => activeMathCheck(e, s"w_int_${sanitize(e._4.show)}")).mkString(" + ")

          rx.distributionMode match {
            case "revise_equal" =>
              val nActiveParts = outEdges.map { e =>
                val nextAct = generatedNextActs.getOrElse((tLbl, e._4), actCheckExpr(e._4))
                val condOpt = rx.edgeConditions.get(e).flatten
                
                val combinedCond = condOpt match {
                  case Some(c) =>
                    val cStr = conditionToPrism(c)
                    if (nextAct == "false") "false"
                    else if (nextAct == "true") cStr
                    else s"($nextAct & $cStr)"
                  case None => nextAct
                }
                
                if (combinedCond == "false") "0" else if (combinedCond == "true") "1" else s"($combinedCond ? 1 : 0)"
              }.filter(_ != "0")
              val nActiveExpr = if (nActiveParts.isEmpty) "0" else nActiveParts.mkString(" + ")

              for (e <- outEdges.sortBy(_._4.toString)) {
                if (!generatedUpdates.contains((tLbl, e._4))) {
                  val sE = sanitize(e._4.show)
                  val base = if (modifiedOut.contains(e)) s"base_upd_${sT}_$sE" else s"w_int_$sE"
                  val newExpr = s"floor($base + (10000 - ($sumModExp) - ($sumUnmodBase)) / max(1, $nActiveExpr))"
                  val fName = s"final_upd_${sT}_$sE"
                  formulasSb.append(s"formula $fName = min(10000, max(0, $newExpr));\n")
                  generatedUpdates((tLbl, e._4)) = fName
                }
              }
            case "normalize" =>
              val totalSumExp = s"max(1, $sumModExp + $sumUnmodBase)"
              for (e <- outEdges.sortBy(_._4.toString)) {
                if (!generatedUpdates.contains((tLbl, e._4))) {
                  val sE = sanitize(e._4.show)
                  val base = if (modifiedOut.contains(e)) s"base_upd_${sT}_$sE" else s"w_int_$sE"
                  val fName = s"final_upd_${sT}_$sE"
                  formulasSb.append(s"formula $fName = min(10000, max(0, floor(($base * 10000) / $totalSumExp)));\n")
                  generatedUpdates((tLbl, e._4)) = fName
                }
              }

            case "proportional" =>
              for (e <- modifiedOut.sortBy(_._4.toString)) {
                if (!generatedUpdates.contains((tLbl, e._4))) {
                  val sE = sanitize(e._4.show)
                  val fName = s"final_upd_${sT}_$sE"
                  formulasSb.append(s"formula $fName = base_upd_${sT}_$sE;\n")
                  generatedUpdates((tLbl, e._4)) = fName
                }
              }
              for (e <- unmodifiedOut.sortBy(_._4.toString)) {
                if (!generatedUpdates.contains((tLbl, e._4))) {
                  val sE = sanitize(e._4.show)
                  val newExpr = s"floor(w_int_$sE * (10000 - ($sumModExp)) / max(1, $sumUnmodBase))"
                  val fName = s"final_upd_${sT}_$sE"
                  formulasSb.append(s"formula $fName = min(10000, max(0, $newExpr));\n")
                  generatedUpdates((tLbl, e._4)) = fName
                }
              }

            case "equal" =>
              val nUnmodParts = unmodifiedOut.map { e =>
                val nextAct = generatedNextActs.getOrElse((tLbl, e._4), actCheckExpr(e._4))
                val condOpt = rx.edgeConditions.get(e).flatten
                
                val combinedCond = condOpt match {
                  case Some(c) =>
                    val cStr = conditionToPrism(c)
                    if (nextAct == "false") "false"
                    else if (nextAct == "true") cStr
                    else s"($nextAct & $cStr)"
                  case None => nextAct
                }
                
                if (combinedCond == "false") "0" else if (combinedCond == "true") "1" else s"($combinedCond ? 1 : 0)"
              }.filter(_ != "0")
              val nUnmodExpr = if (nUnmodParts.isEmpty) "0" else nUnmodParts.mkString(" + ")

              for (e <- modifiedOut.sortBy(_._4.toString)) {
                if (!generatedUpdates.contains((tLbl, e._4))) {
                  val sE = sanitize(e._4.show)
                  val fName = s"final_upd_${sT}_$sE"
                  formulasSb.append(s"formula $fName = base_upd_${sT}_$sE;\n")
                  generatedUpdates((tLbl, e._4)) = fName
                }
              }
              for (e <- unmodifiedOut.sortBy(_._4.toString)) {
                if (!generatedUpdates.contains((tLbl, e._4))) {
                  val sE = sanitize(e._4.show)
                  val newExpr = s"floor(w_int_$sE + (10000 - ($sumModExp) - ($sumUnmodBase)) / max(1, $nUnmodExpr))"
                  val fName = s"final_upd_${sT}_$sE"
                  formulasSb.append(s"formula $fName = min(10000, max(0, $newExpr));\n")
                  generatedUpdates((tLbl, e._4)) = fName
                }
              }
          }
        }
      }
    }

    sb.append(formulasSb.toString())

    val allLabels: List[QName] = (rx.lbls.keySet ++ allRules.map(_._2) ++ allRules.map(_._4)).filter(_.n.nonEmpty).toList.distinct.sortBy(_.toString)
    sb.append("\n// --- Static Weights (Constants) ---\n")
    for (lbl <- allLabels if !mutableLabels.contains(lbl)) {
      val sId = sanitize(lbl.show)
      val w = rx.lbls.get(lbl).flatMap(_.headOption).map(rx.weights.getOrElse(_, 1.0)).getOrElse(1.0)
      sb.append(s"formula w_int_$sId = ${(w * SCALE).toInt};\n")
    }

    sb.append("\n// --- State Transitions ---\n")
    val commands = List.newBuilder[String]

    for ((source, srcIdx) <- stateToId) {
      val outgoingEdges = rx.edg.getOrElse(source, Set()).toList
      
      if (outgoingEdges.nonEmpty) {
        val sumParts = outgoingEdges.map { case (target, id, label) => 
          val e = (source, target, id, label)
          val actCheck = fullActCheckExpr(e)
          val wVar = s"w_int_${sanitize(label.show)}"
          if (actCheck == "true") wVar else if (actCheck == "false") "0" else s"($actCheck ? $wVar : 0)"
        }
        val sumName = s"sum_s$srcIdx"
        sb.append(s"formula $sumName = ${sumParts.mkString(" + ")};\n")

        val branches = outgoingEdges.map { case (target, id, label) =>
          val sLbl = sanitize(label.show)
          val edge = (source, target, id, label)
          val actCheck = fullActCheckExpr(edge)
          
          val probFormula = if (actCheck == "true") s"(w_int_$sLbl / $sumName)" else if (actCheck == "false") "0" else s"($actCheck ? (w_int_$sLbl / $sumName) : 0)"
          
          val effectParts = collection.mutable.ListBuffer[String]()
          effectParts += s"(s'=${stateToId(target)})"

          for (tgtLbl <- toggledLabels.toList.sortBy(_.toString)) {
            generatedNextActs.get((label, tgtLbl)) match {
              case Some(nextFormula) => effectParts += s"(${sanitize(tgtLbl.show)}_act' = ($nextFormula ? 1 : 0))"
              case None => 
            }
          }

          for (tgtLbl <- mutableLabels.toList.sortBy(_.toString)) {
            generatedUpdates.get((label, tgtLbl)) match {
              case Some(updFormula) => effectParts += s"(w_int_${sanitize(tgtLbl.show)}' = $updFormula)"
              case None => 
            }
          }

          val varUpdates = getVariableUpdates(rx.edgeUpdates.getOrElse(edge, Nil))
          val grouped = varUpdates.groupBy(_._1)

          for ((v, upds) <- grouped) {
            val vName = sanitize(v.show)
            var currentExpr = vName
            for ((_, expr, condOpt) <- upds) {
              condOpt match {
                case Some(c) => currentExpr = s"(${conditionToPrism(c)} ? $expr : $currentExpr)"
                case None => currentExpr = expr
              }
            }
            effectParts += s"($vName'=$currentExpr)"
          }

          s"$probFormula : ${effectParts.distinct.mkString(" & ")}"
        }
        
        commands += s"  // State: ${source.show}\n  [$source] s=$srcIdx & $sumName > 0 -> \n    ${branches.mkString("\n    + ")};"
        commands += s"  [] s=$srcIdx & $sumName = 0 -> 1.0 : (s'=$srcIdx);"
      } else {
        commands += s"  // Deadlock \n  [] s=$srcIdx -> 1.0 : (s'=$srcIdx);"
      }
    }

    sb.append("\nmodule RTA_Model\n\n")
    val initId = rx.inits.headOption.map(stateToId.getOrElse(_, 0)).getOrElse(0)
    sb.append(s"  s : [0..$maxState] init $initId;\n\n")

    rx.val_env.toList.sortBy(_._1.toString).foreach { case (n, v) => 
      sb.append(s"  ${sanitize(n.show)} : [0..100] init $v;\n") 
    }

    sb.append("\n  // --- Mutable Weights (State Variables) ---\n")
    for (lbl <- mutableLabels.toList.sortBy(_.toString)) {
      val sId = sanitize(lbl.show)
      val w = rx.lbls.get(lbl).flatMap(_.headOption).map(rx.weights.getOrElse(_, 1.0)).getOrElse(1.0)
      sb.append(s"  w_int_$sId : [0..10000] init ${(w * SCALE).toInt};\n")
    }

    sb.append("\n  // --- Toggle Switches (Active/Inactive) ---\n")
    for (lbl <- toggledLabels.toList.sortBy(_.toString)) {
      val sId = sanitize(lbl.show)
      val initiallyActive = rx.act.map(_._4).contains(lbl)
      sb.append(s"  ${sId}_act : [0..1] init ${if (initiallyActive) 1 else 0};\n")
    }
    
    sb.append("\n")
    commands.result().foreach(c => sb.append(c + "\n\n"))
    sb.append("endmodule\n")
    sb.toString()
  }
}