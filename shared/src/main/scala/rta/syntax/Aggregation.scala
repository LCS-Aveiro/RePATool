package rta.syntax

object Aggregation {
  def compute(name: String, wSource: Double, wRule: Double, wTarget: Double): Double = {
    name match {
      case "prod" => wSource * wRule * wTarget
      case "max"  => Math.max(wSource, Math.max(wRule, wTarget))
      case "min"  => Math.min(wSource, Math.min(wRule, wTarget))
      case "geom" => Math.pow(wSource * wRule * wTarget, 1.0/3.0)
      case "arith" | _ => (wSource + wRule + wTarget) / 3.0
    }
  }
}