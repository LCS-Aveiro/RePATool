package rta.syntax

import rta.syntax.Program2.{Edge, QName, RxGraph}
import rta.syntax.{Condition, Statement, UpdateStmt, IfThenStmt, UpdateExpr}

object RTATranslator {

  private case class Effect(effectType: String, targetId: QName, ruleId: QName, ruleLabel: QName, originalTrigger: QName)

  private def isPhantomRule(trigger: QName, ruleLabel: QName): Boolean = {
    ruleLabel.n.nonEmpty && ruleLabel.n == trigger.n.init
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

    val allEdgeIds = stx.lbls.keySet
    builder.append("// Control variables for group activation (IDs)\n")
    for (id <- allEdgeIds.toList.sortBy(_.toString) if id.n.nonEmpty && id.show != "-") {
      val isInitiallyActive = stx.lbls.get(id).exists(_.exists(stx.act.contains))
      builder.append(s"int ${sanitizeForVar(id)}_active = ${if (isInitiallyActive) 1 else 0}\n")
    }

    builder.append("\n// Declarations\n")
    originalLines.foreach { line =>
      val trim = line.trim
      if (trim.startsWith("int ") || trim.startsWith("init ")) {
        if (!trim.contains("_active =")) builder.append(line).append("\n")
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
        
        bodyBuilder.append(s"    // Rule from group ${effect.originalTrigger.show}\n")

        val guardParts = collection.mutable.ListBuffer[String]()
        if (effect.ruleLabel.n.nonEmpty && effect.ruleLabel.show != "-") guardParts += s"${sanitizeForVar(effect.ruleLabel)}_active == 1"
        conditionOpt.foreach(cond => guardParts += s"(${conditionToString(cond)})")
        
        if (guardParts.isEmpty) {
          bodyBuilder.append(s"    $updateStatement\n")
        } else {
          bodyBuilder.append(s"    if (${guardParts.mkString(" AND ")}) then {\n        $updateStatement\n    }\n")
        }
      }

      val edgeDefinition = s"${source.show} - ${transId.show} -> ${target.show} : ${label.show}"
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
    val allSimpleEdges: List[Edge] = stx.edg.flatMap { case (src, tgts) => tgts.map(t => (src, t._1, t._2, t._3)) }.toList
    
    val allHyperEdges = (stx.on.values.flatten ++ stx.off.values.flatten).toSet
    val activeIds = (allSimpleEdges.map(_._3) ++ allHyperEdges.map(_._1) ++ allHyperEdges.map(_._3)).filter(id => id.n.nonEmpty && id.show != "-")

    builder.append("// Global control variables\n")
    for (id <- activeIds.toList.sortBy(_.toString).distinct) {
      val isInitiallyActive = stx.lbls.get(id).exists(_.exists(stx.act.contains))
      builder.append(s"int ${sanitizeForVar(id)}_active = ${if (isInitiallyActive) 1 else 0}\n")
    }

    val edgesByAut = allSimpleEdges.groupBy(e => getScope(e._1).getOrElse(""))

    for ((autName, edges) <- edgesByAut if autName.nonEmpty) {
      builder.append(s"\naut $autName {\n")
      stx.inits.find(_.n.headOption.contains(autName)).foreach { i =>
        builder.append(s"  init ${formatQName(unqualify(i))}\n\n")
      }
      for (edge <- edges.sortBy(e => (e._1.toString, e._2.toString, e._3.toString, e._4.toString))) {
        builder.append(generateTransitionCode(edge, stx))
      }
      builder.append("}\n")
    }
    builder.toString()
  }

  private def generateTransitionCode(edge: Edge, stx: RxGraph): String = {
    val (source, target, transId, label) = edge
    val bodyBuilder = new StringBuilder()
    val baseIndent = "  "

    val mainGuardParts = collection.mutable.ListBuffer[String]()
    if (transId.n.nonEmpty && transId.show != "-") mainGuardParts += s"${sanitizeForVar(transId)}_active == 1"
    stx.edgeConditions.get(edge).flatten.foreach(og => mainGuardParts += s"(${conditionToString(og)})")

    val allEffects = findAllTriggeredEffects(label, stx)
    for (effect <- allEffects) {
      val updateStatement = if (effect.effectType == "on") s"${sanitizeForVar(effect.targetId)}_active' := 1" else s"${sanitizeForVar(effect.targetId)}_active' := 0"
      bodyBuilder.append(s"$baseIndent    // Effect from label ${label.show}\n")
      val effectGuardParts = collection.mutable.ListBuffer[String]()
      
      val phantom = isPhantomRule(effect.originalTrigger, effect.ruleLabel)
      if (!phantom && effect.ruleLabel.n.nonEmpty && effect.ruleLabel.show != "-") 
        effectGuardParts += s"${sanitizeForVar(effect.ruleLabel)}_active == 1"
      
      stx.edgeConditions.get((effect.originalTrigger, effect.targetId, effect.ruleId, effect.ruleLabel)).flatten.foreach(cond => effectGuardParts += s"(${conditionToString(cond)})")

      if (effectGuardParts.isEmpty) bodyBuilder.append(s"$baseIndent    $updateStatement\n")
      else bodyBuilder.append(s"$baseIndent    if (${effectGuardParts.mkString(" AND ")}) then {\n$baseIndent        $updateStatement\n$baseIndent    }\n")
    }
    
    val uSrc = formatQName(unqualify(source)); val uDst = formatQName(unqualify(target))
    val uTid = formatQName(unqualify(transId)); val uLbl = formatQName(unqualify(label))
    val edgeDef = s"$baseIndent$uSrc - $uTid -> $uDst : $uLbl"
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
          effects += Effect("on", trgId, rid, rlbl, curr)
          if (rlbl.n.nonEmpty) queue.enqueue(rlbl)
        }
        stx.off.getOrElse(curr, Set.empty).foreach { case (trgId, rid, rlbl) =>
          effects += Effect("off", trgId, rid, rlbl, curr)
          if (rlbl.n.nonEmpty) queue.enqueue(rlbl)
        }
      }
    }
    effects.toList
  }

  private def conditionToString(cond: Condition): String = cond.toMermaidString

  private def statementToString(stmt: Statement): String = stmt match {
    case UpdateStmt(upd) => s"${upd.variable.show}' := ${UpdateExpr.show(upd.expr)}"
    case IfThenStmt(c, ts) => 
      val thenContent = ts.map(statementToString).mkString("; ")
      s"if (${conditionToString(c)}) then { $thenContent }"
  }
}