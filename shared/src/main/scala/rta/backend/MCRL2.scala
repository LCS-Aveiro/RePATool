package rta.backend

//import rta.frontend.Examples
import rta.syntax.Parser2
import rta.syntax.Program2.{QName, RxGraph}

object MCRL2:
  def apply(rx: RxGraph): String =
    implicit val mod = findModLbl(using rx)
    implicit val rx2 = rx
    val onoff = mkONOFF(mod)
    val procs = rx.inits.flatMap(mkProc(_))
    //
    val syncs = findActions
    val disabled = findDisLbl
    val allow = rx.edg.flatMap(e => e._2.map(_._2))
    val lbl = findAllLbl ++ syncs.values.toSet.flatten
    val procNames =
      rx.inits.map(s=>s"P_${mkMAction(s)}") ++
      (mod/*++disabled*/).map(n => if disabled(n)
                   then s"OFF_${mkMAction(n)}"
                   else s"ON_${mkMAction(n)}")
//    println(s"--- ON OFFs ${mod.mkString(",")} ---")
//    println(onoff.mkString("\n"))
//    println(s"--- inits ---")
//    println(procs.mkString("\n"))
//    println("--- syncs ---")
//    println(syncs.map(kv => s" - ${mkMAction(kv._1)} -> ${kv._2.mkString(",")}").mkString("\n"))

    s"""act
       |  ${lbl.mkString(",")};
       |proc
       |${procs.map("  "+_).mkString("\n")}
       |
       |${onoff.map("  "+_).mkString("\n")}
       |init
       |  % Experiments (proc names)
       |  ${procNames.mkString(", ")}
       |  % Experiments (sync groups)
       |${syncs.map(kv => s"  - ${mkMAction(kv._1)} <- ${kv._2.mkString(",")}").mkString("\n")}
       |  % Experiments (allow)
       |  ${allow.mkString(", ")}
       |
       |% Possible problems with empty names and repeated names
       |""".stripMargin
//    s"--- ON OFFs ${mod.mkString(",")} ---\n" +
//    onoff.mkString("\n") + "\n" +
//    s"--- inits ---\n" +
//    procs.mkString("\n") + "\n" +
//    "--- syncs ---\n" +
//    syncs.map(kv => s" - ${mkMAction(kv._1)} -> ${kv._2.mkString(",")}").mkString("\n")


  def findModLbl(using rx: RxGraph): Set[QName] =
    (for (e <- rx.on .toSet; trg <- e._2) yield trg._1) ++
    (for (e <- rx.off.toSet; trg <- e._2) yield trg._1)

  def findAllLbl(using rx:RxGraph): Set[QName] =
    (for (e<-rx.edg.toSet; trg<-e._2) yield trg._2) ++
    (for (e<-rx.on .toSet; trg<-e._2) yield trg._2) ++
    (for (e<-rx.off.toSet; trg<-e._2) yield trg._2)

  def findDisLbl(using rx: RxGraph): Set[QName] =
    findAllLbl -- rx.act.map(_._3) - QName(Nil)

  def mkONOFF(n:QName): List[String] =
    val lbl = mkMAction(n)
    List(s"ON_$lbl = (${lbl}_2+act_$lbl).ON_$lbl + deact_$lbl.OFF_$lbl",
         s"OFF_$lbl = act_$lbl.ON_$lbl + deact_$lbl.OFF_$lbl")

  def mkONOFF(mod: Set[QName]): List[String] =
    mod.toList.flatMap(mkONOFF)

  private def mkMAction(q:QName): String =
    if q.n.isEmpty then "_tau" else q.n.mkString("-")
  private def hasToSync(q:QName)(using rx: RxGraph, mod: Set[QName]): Boolean =
    mod(q) || rx.on.contains(q) || rx.off.contains(q)

  def mkProc(s:QName, done:Set[QName]=Set())(using rx: RxGraph, mod: Set[QName]): List[String] =
    if done(s) then Nil else
      val options = for next <- rx.edg(s)  yield
        val lbl = if hasToSync(next._2) then s"${mkMAction(next._2)}_1" else mkMAction(next._2)
        s"$lbl.P_${mkMAction(next._1)}"
      val more = rx.edg(s).toList
        .map(next => mkProc(next._1,(done++(rx.edg(s).map(_._1)-next._1))+s)) // done other destinations and s
      if options.nonEmpty
      then s"P_${mkMAction(s)} = ${options.mkString(" + ")}" :: more.flatten
      else s"P_${mkMAction(s)} = delta" :: more.flatten

  def findActions(using rx: RxGraph, mod: Set[QName]): Map[QName,Set[String]] =
    // for each modified label l, sync l1 -> l_1,l_2
    var acts = (for e<-mod yield
      e -> Set(s"${mkMAction(e)}_1",s"${mkMAction(e)}_2"))
      .toMap
    // for each activated label l1->>l2:l3, sync l1 -> l1_1,act_l2
    for e<-rx.on; trg<-e._2 do
      acts = acts + (e._1 -> (acts.getOrElse(e._1,Set(mkMAction(e._1)))
        +s"act_${mkMAction(trg._1)}" ))
    // for each deactivated label l1--x l2:l3, sync l1 -> l1_1,act_l2
    for e<-rx.off; trg<-e._2 do
      acts = acts + (e._1 -> (acts.getOrElse(e._1,Set(mkMAction(e._1)))
        +s"deact_${mkMAction(trg._1)}" ))
    acts



