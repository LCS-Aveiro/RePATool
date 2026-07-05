package rta.syntax

import rta.syntax.Program2.{Edge, QName, RxGraph}
import rta.syntax.{Condition, Statement, AssignStmt, ArrayAssignStmt, IfThenStmt, ForeachStmt, ReturnStmt,PrintStmt, UpdateExpr}

object RTATranslator {

  private case class Effect(effectType: String, targetId: QName, ruleId: QName, ruleLabel: QName, originalTrigger: QName, ruleWeight: Double, aggType: String, wExpr: Option[UpdateExpr])

  private def isPhantomRule(trigger: QName, ruleLabel: QName): Boolean = {
    ruleLabel.n.nonEmpty && ruleLabel.n == trigger.n.init
  }

  private def generateWeightUpdate(effect: Effect, mutableLabels: Set[QName], stx: RxGraph): String = {
    val wTargetVar = s"w_${sanitizeForVar(effect.targetId)}"
    val triggerW = if (mutableLabels.contains(effect.originalTrigger)) {
      s"w_${sanitizeForVar(effect.originalTrigger)}"
    } else {
      val tEdge = stx.lbls.get(effect.originalTrigger).flatMap(_.headOption)
      val wExp = tEdge.flatMap(stx.weightExprs.get)
      wExp.map(UpdateExpr.show).getOrElse {
         val w = tEdge.map(e => stx.weights.getOrElse(e, 1.0)).getOrElse(1.0)
         f"$w%.3f".replace(",", ".")
      }
    }
    val ruleW = effect.wExpr.map(UpdateExpr.show).getOrElse(f"${effect.ruleWeight}%.3f".replace(",", "."))
    
    effect.aggType match {
       case "prod" => s"($triggerW * $ruleW * $wTargetVar)"
       case "max" => s"max_agg($triggerW, max_agg($ruleW, $wTargetVar))"
       case "min" => s"min_agg($triggerW, min_agg($ruleW, $wTargetVar))"
       case _ => s"($triggerW + $ruleW + $wTargetVar) / 3.0"
    }
  }

  def translate_syntax(stx: RxGraph, inputScript: String): String = {
    if (inputScript.linesIterator.exists(_.trim.startsWith("aut "))) {
      translateModular(stx, inputScript)
    } else {
      translateFlat(stx, inputScript)
    }
  }

  private def translateFlat(stx: RxGraph, inputScript: String): String = {
    val builder = new StringBuilder()
    val originalLines = inputScript.split('\n')

    val ruleToLineNumber = {
      val lineMap = collection.mutable.Map[(String, QName, QName, QName), Int]()
      val ruleRegex = """^\s*([\w./]+)\s*(->>|--!)\s*([\w./]+)(?:\s*:\s*([\w./]+))?.*""".r
      originalLines.zipWithIndex.foreach { case (line, lineNumber) =>
        ruleRegex.findFirstMatchIn(line.trim).foreach { m =>
          val trigger = QName(m.group(1).split('/').toList)
          val op = m.group(2)
          val target = QName(m.group(3).split('/').toList)
          val rlabelRaw = m.group(4)
          val ruleLabel = if (rlabelRaw != null) QName(rlabelRaw.split('/').toList) else target
          val effectType = if (op == "->>") "on" else "off"
          lineMap((effectType, trigger, target, ruleLabel)) = lineNumber
        }
      }
      lineMap.toMap
    }

    val allEffectsOverall = stx.lbls.keys.flatMap(l => findAllTriggeredEffects(l, stx)).toList
    val mutableLabels = allEffectsOverall.map(_.targetId).toSet

    builder.append("// --- Model Configurations ---\n")
    originalLines.foreach { line =>
      val trim = line.trim
      if (trim.startsWith("name ") || trim.startsWith("calibration ") || trim.startsWith("training") || trim.startsWith("paradigm ")) {
        builder.append(line).append("\n")
      }
    }

    val allEdgeIds = stx.lbls.keySet
    builder.append("\n// Control variables for group activation (IDs)\n")
    for (id <- allEdgeIds.toList.sortBy(_.toString) if id.n.nonEmpty && id.show != "-") {
      val isInitiallyActive = stx.lbls.get(id).exists(_.exists(stx.act.contains))
      builder.append(s"int ${sanitizeForVar(id)}_active = ${if (isInitiallyActive) 1 else 0}\n")
    }

    if (mutableLabels.nonEmpty) {
        builder.append("\n// Variables for dynamic weights\n")
        for (lbl <- mutableLabels.toList.sortBy(_.toString)) {
           val initW = stx.lbls.get(lbl).flatMap(_.headOption).map(e => stx.weights.getOrElse(e, 1.0)).getOrElse(1.0)
           builder.append(s"float w_${sanitizeForVar(lbl)} = ${f"$initW%.3f".replace(",", ".")}\n")
        }
        builder.append("\n// Helper functions for aggregations\n")
        builder.append("def max_agg(a, b) {\n    if (a >= b) then { return a }\n    return b\n}\n")
        builder.append("def min_agg(a, b) {\n    if (a <= b) then { return a }\n    return b\n}\n")
    }

    builder.append("\n// --- Declarations ---\n")
    originalLines.foreach { line =>
      val trim = line.trim
      if (trim.startsWith("int ") || trim.startsWith("float ") || trim.startsWith("bool ") || trim.startsWith("dyn ") || trim.startsWith("init ")) {
        if (!trim.contains("_active =") && !trim.startsWith("float w_")) builder.append(line).append("\n")
      }
    }

    builder.append("\n// --- Translated Edges ---\n")

    for ((source, targets) <- stx.edg; (target, transId, label) <- targets) {
      val simpleEdge: Edge = (source, target, transId, label)
      val bodyBuilder = new StringBuilder()

      stx.edgeUpdates.get(simpleEdge).foreach { updates =>
        updates.foreach(stmt => bodyBuilder.append(s"    ${statementToString(stmt)}\n"))
      }

      val allEffects = findAllTriggeredEffects(label, stx)
      val sortedEffects = allEffects.sortBy { effect =>
          val key = (effect.effectType, effect.originalTrigger, effect.targetId, effect.ruleLabel)
          ruleToLineNumber.getOrElse(key, Int.MaxValue)
      }
      
      if (sortedEffects.nonEmpty && bodyBuilder.nonEmpty) bodyBuilder.append("\n")

      for (effect <- sortedEffects) {
        val hyperEdge = (effect.originalTrigger, effect.targetId, effect.ruleId, effect.ruleLabel)
        val conditionOpt = stx.edgeConditions.get(hyperEdge).flatten

        val updateVar = s"${sanitizeForVar(effect.targetId)}_active"
        val updateStatement = if (effect.effectType == "on") s"$updateVar' := 1" else s"$updateVar' := 0"
        
        val rWStr = effect.wExpr.map(UpdateExpr.show).getOrElse(f"${effect.ruleWeight}%.3f".replace(",", "."))
        val rAggStr = if (effect.aggType != "arith" && effect.aggType.nonEmpty) s" ${effect.aggType}" else ""
        bodyBuilder.append(s"    // Rule from group ${effect.originalTrigger.show} ($rWStr)$rAggStr\n")

        val guardParts = collection.mutable.ListBuffer[String]()
        if (effect.ruleLabel.n.nonEmpty && effect.ruleLabel.show != "-") guardParts += s"${sanitizeForVar(effect.ruleLabel)}_active == 1"
        conditionOpt.foreach(cond => guardParts += s"(${conditionToString(cond)})")
        
        val weightUpd = s"w_${sanitizeForVar(effect.targetId)}' := ${generateWeightUpdate(effect, mutableLabels, stx)}"

        if (guardParts.isEmpty) {
          bodyBuilder.append(s"    $updateStatement\n")
          bodyBuilder.append(s"    $weightUpd\n")
        } else {
          bodyBuilder.append(s"    if (${guardParts.mkString(" AND ")}) then {\n        $updateStatement\n        $weightUpd\n    }\n")
        }
      }

      val w = stx.weights.getOrElse(simpleEdge, 1.0)
      val wExpr = stx.weightExprs.get(simpleEdge)
      val wStr = if (mutableLabels.contains(label)) s"w_${sanitizeForVar(label)}" else wExpr.map(UpdateExpr.show).getOrElse(f"$w%.3f".replace(",", "."))
      val agg = stx.edgeAggregations.getOrElse(simpleEdge, "arith")
      val aggStr = if (agg != "arith" && agg.nonEmpty) s" $agg" else ""
      
      val edgeDefinition = if (transId.n.isEmpty || transId.show == "-") s"${source.show} ---> ${target.show} : ${label.show} ($wStr)$aggStr"
                           else s"${source.show} - ${transId.show} -> ${target.show} : ${label.show} ($wStr)$aggStr"

      val mainGuard = if (transId.n.nonEmpty && transId.show != "-") s"if (${sanitizeForVar(transId)}_active == 1" else "if (true"
      val originalGuard = stx.edgeConditions.get(simpleEdge).flatten.map(c => " AND " + conditionToString(c)).getOrElse("")
      val fullGuardClause = mainGuard + originalGuard + ")"
      
      if (bodyBuilder.toString.trim.isEmpty) {
        builder.append(s"$edgeDefinition $fullGuardClause\n\n")
      } else {
        builder.append(s"$edgeDefinition $fullGuardClause then {\n")
        builder.append(bodyBuilder.toString().stripSuffix("\n"))
        builder.append("\n}\n\n")
      }
    }
    builder.toString()
  }

  private def translateModular(stx: RxGraph, inputScript: String): String = {
    val builder = new StringBuilder()
    val originalLines = inputScript.split('\n')
    val allSimpleEdges: List[Edge] = stx.edg.flatMap { case (src, tgts) => tgts.map(t => (src, t._1, t._2, t._3)) }.toList
    
    val allHyperEdges = (stx.on.values.flatten ++ stx.off.values.flatten).toSet
    val activeIds = (allSimpleEdges.map(_._3) ++ allHyperEdges.map(_._1) ++ allHyperEdges.map(_._3)).filter(id => id.n.nonEmpty && id.show != "-")

    val allEffectsOverall = stx.lbls.keys.flatMap(l => findAllTriggeredEffects(l, stx)).toList
    val mutableLabels = allEffectsOverall.map(_.targetId).toSet

    builder.append("// --- Model Configurations ---\n")
    originalLines.foreach { line =>
      val trim = line.trim
      if (trim.startsWith("name ") || trim.startsWith("calibration ") || trim.startsWith("training") || trim.startsWith("paradigm ")) {
        builder.append(line).append("\n")
      }
    }

    builder.append("\n// Global control variables\n")
    for (id <- activeIds.toList.sortBy(_.toString).distinct) {
      val isInitiallyActive = stx.lbls.get(id).exists(_.exists(stx.act.contains))
      builder.append(s"int ${sanitizeForVar(id)}_active = ${if (isInitiallyActive) 1 else 0}\n")
    }

    if (mutableLabels.nonEmpty) {
        builder.append("\n// Variables for dynamic weights\n")
        for (lbl <- mutableLabels.toList.sortBy(_.toString)) {
           val initW = stx.lbls.get(lbl).flatMap(_.headOption).map(e => stx.weights.getOrElse(e, 1.0)).getOrElse(1.0)
           builder.append(s"float w_${sanitizeForVar(lbl)} = ${f"$initW%.3f".replace(",", ".")}\n")
        }
        builder.append("\n// Helper functions for aggregations\n")
        builder.append("def max_agg(a, b) {\n    if (a >= b) then { return a }\n    return b\n}\n")
        builder.append("def min_agg(a, b) {\n    if (a <= b) then { return a }\n    return b\n}\n")
    }

    builder.append("\n// --- Global Declarations ---\n")
    originalLines.foreach { line =>
      val trim = line.trim
      if (trim.startsWith("int ") || trim.startsWith("float ") || trim.startsWith("bool ") || trim.startsWith("dyn ")) {
        if (!trim.contains("_active =") && !trim.startsWith("float w_")) {
           builder.append(line).append("\n")
        }
      }
    }

    val edgesByAut = allSimpleEdges.groupBy(e => getScope(e._1).getOrElse(""))

    for ((autName, edges) <- edgesByAut if autName.nonEmpty) {
      builder.append(s"\naut $autName {\n")
      stx.inits.find(_.n.headOption.contains(autName)).foreach { i =>
        builder.append(s"  init ${formatQName(unqualify(i))}\n\n")
      }
      for (edge <- edges.sortBy(e => (e._1.toString, e._2.toString, e._3.toString, e._4.toString))) {
        builder.append(generateTransitionCode(edge, stx, mutableLabels))
      }
      builder.append("}\n")
    }
    builder.toString()
  }

  private def generateTransitionCode(edge: Edge, stx: RxGraph, mutableLabels: Set[QName]): String = {
    val (source, target, transId, label) = edge
    val bodyBuilder = new StringBuilder()
    val baseIndent = "  "

    val mainGuardParts = collection.mutable.ListBuffer[String]()
    if (transId.n.nonEmpty && transId.show != "-") mainGuardParts += s"${sanitizeForVar(transId)}_active == 1"
    stx.edgeConditions.get(edge).flatten.foreach(og => mainGuardParts += s"(${conditionToString(og)})")

    val allEffects = findAllTriggeredEffects(label, stx)
    for (effect <- allEffects) {
      val updateStatement = if (effect.effectType == "on") s"${sanitizeForVar(effect.targetId)}_active' := 1" else s"${sanitizeForVar(effect.targetId)}_active' := 0"
      val rWStr = effect.wExpr.map(UpdateExpr.show).getOrElse(f"${effect.ruleWeight}%.3f".replace(",", "."))
      val rAggStr = if (effect.aggType != "arith" && effect.aggType.nonEmpty) s" ${effect.aggType}" else ""
      bodyBuilder.append(s"$baseIndent    // Effect from label ${label.show} ($rWStr)$rAggStr\n")
      val effectGuardParts = collection.mutable.ListBuffer[String]()
      
      val phantom = isPhantomRule(effect.originalTrigger, effect.ruleLabel)
      if (!phantom && effect.ruleLabel.n.nonEmpty && effect.ruleLabel.show != "-") 
        effectGuardParts += s"${sanitizeForVar(effect.ruleLabel)}_active == 1"
      
      stx.edgeConditions.get((effect.originalTrigger, effect.targetId, effect.ruleId, effect.ruleLabel)).flatten.foreach(cond => effectGuardParts += s"(${conditionToString(cond)})")

      val weightUpd = s"w_${sanitizeForVar(effect.targetId)}' := ${generateWeightUpdate(effect, mutableLabels, stx)}"

      if (effectGuardParts.isEmpty) {
         bodyBuilder.append(s"$baseIndent    $updateStatement\n")
         bodyBuilder.append(s"$baseIndent    $weightUpd\n")
      } else {
         bodyBuilder.append(s"$baseIndent    if (${effectGuardParts.mkString(" AND ")}) then {\n$baseIndent        $updateStatement\n$baseIndent        $weightUpd\n$baseIndent    }\n")
      }
    }
    
    val w = stx.weights.getOrElse(edge, 1.0)
    val wExpr = stx.weightExprs.get(edge)
    val wStr = if (mutableLabels.contains(label)) s"w_${sanitizeForVar(label)}" else wExpr.map(UpdateExpr.show).getOrElse(f"$w%.3f".replace(",", "."))
    val agg = stx.edgeAggregations.getOrElse(edge, "arith")
    val aggStr = if (agg != "arith" && agg.nonEmpty) s" $agg" else ""

    val uSrc = formatQName(unqualify(source)); val uDst = formatQName(unqualify(target))
    val uTid = formatQName(unqualify(transId)); val uLbl = formatQName(unqualify(label))
    
    val edgeDef = if (transId.n.isEmpty || transId.show == "-") s"$baseIndent$uSrc ---> $uDst : $uLbl ($wStr)$aggStr"
                  else s"$baseIndent$uSrc - $uTid -> $uDst : $uLbl ($wStr)$aggStr"

    val fullGuard = if (mainGuardParts.nonEmpty) s" if (${mainGuardParts.mkString(" AND ")})" else ""
    
    if (bodyBuilder.isEmpty) s"$edgeDef$fullGuard\n"
    else s"$edgeDef$fullGuard then {\n${bodyBuilder.toString().stripSuffix("\n")}\n$baseIndent}\n"
  }

  private def getScope(q: QName): Option[String] = q.n.headOption
  private def formatQName(q: QName): String = q.n.mkString(".")
  private def sanitizeForVar(q: QName): String = q.n.mkString("_")
  private def unqualify(q: QName): QName = if (q.n.length > 1) QName(q.n.tail) else q

  private def findAllTriggeredEffects(triggerLabel: QName, stx: RxGraph): List[Effect] = {
    val effects = collection.mutable.ListBuffer[Effect]()
    val queue = collection.mutable.Queue[QName](triggerLabel)
    val visited = collection.mutable.Set[QName]()
    while (queue.nonEmpty) {
      val curr = queue.dequeue()
      if (!visited.contains(curr)) {
        visited.add(curr)
        stx.on.getOrElse(curr, Set.empty).foreach { case (trgId, rid, rlbl) =>
          val edge = (curr, trgId, rid, rlbl)
          val w = stx.weights.getOrElse(edge, 0.1)
          val agg = stx.edgeAggregations.getOrElse(edge, "arith")
          val expr = stx.weightExprs.get(edge)
          effects += Effect("on", trgId, rid, rlbl, curr, w, agg, expr)
          if (rlbl.n.nonEmpty) queue.enqueue(rlbl)
        }
        stx.off.getOrElse(curr, Set.empty).foreach { case (trgId, rid, rlbl) =>
          val edge = (curr, trgId, rid, rlbl)
          val w = stx.weights.getOrElse(edge, 0.1)
          val agg = stx.edgeAggregations.getOrElse(edge, "arith")
          val expr = stx.weightExprs.get(edge)
          effects += Effect("off", trgId, rid, rlbl, curr, w, agg, expr)
          if (rlbl.n.nonEmpty) queue.enqueue(rlbl)
        }
      }
    }
    effects.toList
  }

  private def conditionToString(cond: Condition): String = cond.toMermaidString

  private def statementToString(stmt: Statement): String = stmt match {
    case AssignStmt(variable, expr) => s"${variable.show}' := ${UpdateExpr.show(expr)}"
    case ArrayAssignStmt(arrName, index, expr) => s"${arrName.show}[${UpdateExpr.show(index)}]' := ${UpdateExpr.show(expr)}"
    case IfThenStmt(c, ts) => 
      val thenContent = ts.map(statementToString).mkString("; ")
      s"if (${conditionToString(c)}) then { $thenContent }"
    case ForeachStmt(iter, arr, body) => 
      s"foreach (${iter.show} in ${arr.show}) { ${body.map(statementToString).mkString("; ")} }"
    case ReturnStmt(expr) => s"return ${UpdateExpr.show(expr)}"
    case PrintStmt(expr)  => s"print(${UpdateExpr.show(expr)})"
  }
}