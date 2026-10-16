package rta.backend

import rta.syntax.Program2.{QName, RxGraph, fmtWeight}

object SourceUpdater {

  private val transRegex =
    """^(\s*)([\w./]+)\s*(-\s*[\w./]+\s*(?:->>|->|--x|--!)|--->|-->|->>|--!|--x|->)\s*([\w./]+)\s*:\s*([\w./]+)(\s*\(\s*[\d.]+\s*\))?(.*)$""".r
  private val idRegex = """^-\s*([\w./]+)\s*(?:->>|->|--x|--!)$""".r

  def updateWeights(graph: RxGraph, source: String): String = {
    def norm(str: String): String = str.trim.replace('.', '/')
    def sameName(q: QName, written: String): Boolean = q.show == written || q.show.endsWith("/" + written)
    source.split("\r?\n", -1).map {
      case line @ transRegex(indent, fromStr, arrow, toStr, lblStr, _, rest) if !rest.trim.startsWith("(") =>
        val (from, to, lbl) = (norm(fromStr), norm(toStr), norm(lblStr))
        val idOpt = arrow match { case idRegex(id) => Some(norm(id)); case _ => None }
        val candidates = graph.weights.toList.filter { case (e, _) =>
          sameName(e._1, from) && sameName(e._2, to) && sameName(e._4, lbl) && idOpt.forall(id => sameName(e._3, id))
        }
        val exact = candidates.filter { case (e, _) => e._1.show == from && e._2.show == to && e._4.show == lbl }
        (if (exact.nonEmpty) exact else candidates) match {
          case List((_, w)) => s"$indent${fromStr.trim} $arrow ${toStr.trim} : ${lblStr.trim} (${fmtWeight(w)})$rest"
          case _ => line
        }
      case other => other
    }.mkString("\n")
  }
}
