package rta.backend

import rta.syntax.Program2.{Edge, RxGraph, QName}
import rta.syntax.{Condition, UpdateExpr, Statement, UpdateStmt, IfThenStmt}

object CytoscapeConverter {

  private def formatStatements(stmts: List[Statement], indent: String = ""): String = {
    stmts.map {
      case UpdateStmt(upd) =>
        s"${indent}${upd.variable.show}' := ${UpdateExpr.show(upd.expr)}"
      case IfThenStmt(condition, thenStmts) =>
        val conditionLine = s"${indent}if (${condition.toMermaidString}) then {"
        val thenBlock = formatStatements(thenStmts, indent + "  ")
        val closingBrace = s"${indent}}"
        Seq(conditionLine, thenBlock, closingBrace).filter(_.nonEmpty).mkString("\n")
    }.mkString("\n")
  }

  private def escapeJson(text: String): String = {
    text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
  }

  def apply(rx: RxGraph): String = {
    val allStates = rx.states ++ rx.inits
    val deadlockEdges = allStates.flatMap { st =>
      val outEdges = rx.edg.getOrElse(st, Set.empty)
      val validOut = outEdges.filter { t =>
        val edge = (st, t._1, t._2, t._3)
        rx.act.contains(edge) && rx.edgeConditions.getOrElse(edge, None).forall(c => Condition.evaluate(c, rx.val_env))
      }
      if (validOut.isEmpty) Some((st, st, QName(List("tau")), QName(List("deadlock")))) else None
    }.toSet
    val activeDeadlocks = deadlockEdges.filter(e => rx.inits.contains(e._1))

    val allSimpleEdges: Set[Edge] = rx.edg.flatMap { case (f, ts) => ts.map(t => (f, t._1, t._2, t._3)) }.toSet ++ deadlockEdges
    val allOnEdges: Set[Edge]     = rx.on.flatMap  { case (f, ts) => ts.map(t => (f, t._1, t._2, t._3)) }.toSet
    val allOffEdges: Set[Edge]    = rx.off.flatMap { case (f, ts) => ts.map(t => (f, t._1, t._2, t._3)) }.toSet

    val edgesThatCreateNodes = allSimpleEdges.filter(_._4.n.nonEmpty) ++ allOnEdges ++ allOffEdges

    val allQNames = rx.states ++ rx.inits ++ deadlockEdges.flatMap(e => Set(e._1, e._2)) ++ edgesThatCreateNodes.flatMap(e => Set(e._1, e._2, e._3, e._4))
    val parentQNames = allQNames.flatMap(q => (1 until q.n.length).map(i => QName(q.n.take(i)))).toSet

    val parentNodes = parentQNames.map { parentQName =>
      val parentId = if (parentQName.n.size > 1) Some(parentQName.scope.toString) else None
      val parentJson = parentId.map(p => s""", "parent": "$p"""").getOrElse("")
      s"""{ "data": { "id": "${parentQName.toString}", "label": "${parentQName.n.last}" ${parentJson} }, "classes": "compound-parent" }"""
    }

    val stateNodes = (rx.states ++ deadlockEdges.flatMap(e => Set(e._1, e._2)) ++ rx.inits).map { state =>
      val parentId = if (state.n.size > 1) Some(state.scope.toString) else None
      val parentJson = parentId.map(p => s""", "parent": "$p"""").getOrElse("")
      val classes = "state-node " + (if (rx.inits.contains(state)) "current-state" else "")
      s"""{ "data": { "id": "${state.toString}", "label": "${state.show}" ${parentJson} }, "classes": "$classes" }"""
    }

    val eventNodes = edgesThatCreateNodes.map { edge =>
      val (from: QName, to: QName, transId: QName, lbl: QName) = edge
      val id = s"event_${from}_${to}_${transId}_${lbl}"

      val visualLabel = transId.show
      val lblShow = lbl.show
      val labelText = if (transId == lbl || transId.n.isEmpty) lblShow else s"${lblShow}(${transId.show})"

      val p = if (deadlockEdges.contains(edge)) 1.0 else rx.weights.getOrElse(edge, 1.0)
      val isRule = allOnEdges.contains(edge) || allOffEdges.contains(edge)
      val weightStr = if (isRule) f"\\n(Δ=$p%.3f)" else f"\\n(P=$p%.3f)"
      val displayLabel = visualLabel + weightStr

      val parentId = List(lbl, from, to).find(_.n.size > 1).map(_.asInstanceOf[QName].scope.toString)
      val parentJson = parentId.map(p => s""", "parent": "$p"""").getOrElse("")

      val isEnabled = rx.act.contains(edge) || activeDeadlocks.contains(edge)
      val isAction = allSimpleEdges.contains(edge)
      val nodeTypeClass = if (isAction) "action-node" else "rule-node"
      val deadlockClass = if (deadlockEdges.contains(edge)) " deadlock-node" else ""
      
      val hoverLabelField = s""", "hover_label": "${escapeJson(labelText + weightStr.replace("\\n", " "))}" """

      val classes = s"event-node $nodeTypeClass " + (if (isEnabled) "enabled" else "disabled") + deadlockClass
      s"""{ "data": { 
          "id": "$id", 
          "transID":"$visualLabel",
          "lbl":"$lblShow",
          "label": "$displayLabel", 
          "action_name": "${escapeJson(labelText)}", 
          "hover_label": "${escapeJson(labelText + weightStr.replace("\\n", " "))}",
          "p": $p 
          ${parentJson} 
      }, "classes": "$classes" }"""
    }

    val simpleConnections = allSimpleEdges.filter(_._4.n.nonEmpty).flatMap { edge =>
      val (from: QName, to: QName, transId: QName, lbl: QName) = edge
      val actionNodeId = s"event_${from}_${to}_${transId}_${lbl}"
      val isDisabled = !(rx.act.contains(edge) || activeDeadlocks.contains(edge))
      val disabledClass = if (isDisabled) " disabled" else ""
      val deadlockClass = if (deadlockEdges.contains(edge)) " deadlock-edge" else ""
      
      val labelThreshold = 30 
      val fullConditionLabel = rx.edgeConditions.getOrElse(edge, None)
        .map(cond => s"[${cond.toMermaidString}]")
        .getOrElse("")
      
      var conditionDisplayLabel = fullConditionLabel
      var conditionExtraData = ""
      var conditionClasses = ""

      if (fullConditionLabel.length > labelThreshold) {
        conditionDisplayLabel = "[...]"
        conditionExtraData = s""", "full_label": "${escapeJson(fullConditionLabel)}", "short_label": "[...]" """
        conditionClasses = " has-details"
      }

      val updates = rx.edgeUpdates.getOrElse(edge, Nil)
      val fullUpdateLabel = formatStatements(updates).replace("\n", " ") 
      var updateDisplayLabel = fullUpdateLabel
      var updateExtraData = ""
      var updateClasses = ""

      if (fullUpdateLabel.length > labelThreshold) {
        updateDisplayLabel = "{...}"
        updateExtraData = s""", "full_label": "${escapeJson(fullUpdateLabel)}", "short_label": "{...}" """
        updateClasses = " has-details"
      }

      List(
        formatCyEdge(s"s_to_a_${from}_${actionNodeId}", from.toString, actionNodeId, conditionDisplayLabel, s"simple-conn$disabledClass$conditionClasses$deadlockClass", conditionExtraData),
        formatCyEdge(s"a_to_s_${actionNodeId}_${to}", actionNodeId, to.toString, updateDisplayLabel, s"simple-conn from-action-node$disabledClass$updateClasses$deadlockClass", updateExtraData)
      )
    }

    val hyperConnections = (allOnEdges ++ allOffEdges).flatMap { ruleEdge =>
      val (fromTrigger: QName, toTarget: QName, ruleId: QName, ruleLabel: QName) = ruleEdge
      val ruleNodeId = s"event_${fromTrigger}_${toTarget}_${ruleId}_${ruleLabel}"

      val fromEventNodes = edgesThatCreateNodes.filter(_._4 == fromTrigger)
      val toEventNodes = edgesThatCreateNodes.filter(_._4 == toTarget)

      val isRuleDisabled = !rx.act.contains(ruleEdge)
      val disabledClass = if (isRuleDisabled) " disabled" else ""
      val ruleClass = if (allOnEdges.contains(ruleEdge)) "enable-rule" else "disable-rule"

      for {
        fNode <- fromEventNodes
        tNode <- toEventNodes
      } yield {
        val fNodeId = s"event_${fNode._1}_${fNode._2}_${fNode._3}_${fNode._4}"
        val tNodeId = s"event_${tNode._1}_${tNode._2}_${tNode._3}_${tNode._4}"
        List(
            formatCyEdge(s"rule_from_${fNodeId}_${ruleNodeId}", fNodeId, ruleNodeId, "", s"rule-edge $ruleClass$disabledClass"),
            formatCyEdge(s"rule_to_${ruleNodeId}_${tNodeId}", ruleNodeId, tNodeId, "", s"rule-edge from-rule-node to-target $ruleClass$disabledClass")
        )
      }
    }.flatten

    val allNodes = (parentNodes ++ stateNodes ++ eventNodes).filter(_.nonEmpty).mkString(",\n")
    val allConnections = (simpleConnections ++ hyperConnections).filter(_.nonEmpty).mkString(",\n")

    s"""[ ${Seq(allNodes, allConnections).filter(_.nonEmpty).mkString(",\n")} ]"""
  }

  private def formatCyEdge(id: String, source: String, target: String, label: String, classes: String, extraData: String = ""): String =
    s"""{ "data": { "id": "$id", "source": "$source", "target": "$target", "label": "${escapeJson(label)}"$extraData }, "classes": "$classes" }"""
}