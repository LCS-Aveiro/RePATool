package rta.backend

import rta.syntax.Program2.{Edges, RxGraph, QName, Edge, showEdges}
import scala.collection.mutable
import rta.syntax.Condition

object AnalyseLTS:

  case class PathResult(path: List[Edge], probability: Double, finalState: RxGraph)

  def randomWalk(rx:RxGraph, max:Int=5000): (Set[RxGraph],Int,Edges,List[String]) =
    val states = for (a, bs) <- rx.edg.toSet; (b, id, lbl) <- bs; s <- Set(a, b) yield s
    
    def aux(next:Set[RxGraph], done:Set[RxGraph],
            nEdges:Int, fired:Edges, probs:List[String],
            limit:Int): (Set[RxGraph],Int,Edges,List[String]) =
      if limit <=0 then
        return (done,nEdges,fired, s"Reached limit - traversed +$max edges."::probs)
      
      next.headOption match
        case None =>
          val missingStates: Set[QName] =
            (rx.inits ++ fired.map(_._2)).intersect(states) -- done.flatMap(_.inits)
          
          val allPossibleEdges: Edges =
            (for (a, dests) <- rx.edg.toSet; (b, id, lbl) <- dests yield (a, b, id, lbl)) ++
            (for (a, dests) <- rx.on.toSet;  (b, id, lbl) <- dests yield (a, b, id, lbl)) ++
            (for (a, dests) <- rx.off.toSet; (b, id, lbl) <- dests yield (a, b, id, lbl))
          
          val missingEdges: Edges = allPossibleEdges -- fired
          
          if missingStates.isEmpty && missingEdges.isEmpty then
            (done, nEdges, fired, probs)
          else
            val stateErrs = if missingStates.nonEmpty 
                            then List(s"Unreachable state(s): ${missingStates.mkString(",")}") else Nil
            val edgeErrs = if missingEdges.nonEmpty  
                           then List(s"Unreachable edge(s): ${showEdges(missingEdges)}") else Nil
            (done, nEdges, fired, stateErrs ::: edgeErrs ::: probs)

        case Some(st) if done contains st =>
          aux(next-st, done, nEdges, fired, probs, limit)

        case Some(st) => 
          val more = RxSemantics.nextEdge(st)
          val nEdges2 = more.size
          val newEdges = more.map(_._1)
          var incons = Set[String]()
          var moreEdges: Edges = Set()

          for e <- newEdges do
            val (toAct, toDeact, _) = RxSemantics.toOnOff(e, st)
            val fromE = RxSemantics.from(e, st)
            moreEdges = moreEdges ++ fromE
            
            val shared = toAct.intersect(toDeact)
            if shared.nonEmpty then
              val triggers = fromE -- shared
              incons = incons + s"activating and deactivating `${showEdges(shared)}` by `${showEdges(triggers)}`"

          var newProbs = probs
          if more.isEmpty then newProbs = s"Deadlock found at: ${st.inits.mkString(",")}" :: newProbs
          if incons.nonEmpty then newProbs = s"Found inconsistency: ${incons.mkString(", ")}" :: newProbs
          
          aux((next - st) ++ more.map(_._2), 
              done + st, 
              nEdges + nEdges2, 
              fired ++ newEdges ++ moreEdges, 
              newProbs, 
              limit - nEdges2)

    aux(Set(rx), Set(), 0, Set(), Nil, max)

  def findBestPath(start: RxGraph, goal: RxGraph => Boolean, isMax: Boolean = true, maxNodes: Int = 2000): Option[PathResult] = {
    
    val ordering = if (isMax) Ordering.by[(Double, RxGraph, List[Edge]), Double](_._1)
                   else Ordering.by[(Double, RxGraph, List[Edge]), Double](_._1).reverse

    val pq = mutable.PriorityQueue[(Double, RxGraph, List[Edge])]()(ordering)
    
    val bestSeen = mutable.Map[RxGraph, Double]()

    pq.enqueue((1.0, start, Nil))
    bestSeen(start) = 1.0

    var nodesVisited = 0

    while (pq.nonEmpty && nodesVisited < maxNodes) {
      val (prob, current, path) = pq.dequeue()
      nodesVisited += 1

      if (goal(current)) {
        return Some(PathResult(path.reverse, prob, current))
      }

      for ((edge, nextRx) <- RxSemantics.nextEdge(current)) {
        val edgeProb = current.weights.getOrElse(edge, 1.0)
        val newProb = prob * edgeProb

        val shouldExplore = if (isMax) {
          newProb > bestSeen.getOrElse(nextRx, -1.0)
        } else {
          !bestSeen.contains(nextRx) || newProb < bestSeen(nextRx)
        }

        if (shouldExplore) {
          bestSeen(nextRx) = newProb
          pq.enqueue((newProb, nextRx, edge :: path))
        }
      }
    }
    None
  }

  def goalState(name: String): RxGraph => Boolean = 
    rx => rx.inits.exists(_.show == name)

  def goalVariable(varName: String, value: Int): RxGraph => Boolean = 
    rx => rx.val_env.get(QName(List(varName))).contains(value)

  def goalCondition(cond: Condition): RxGraph => Boolean =
    rx => Condition.evaluate(cond, rx)