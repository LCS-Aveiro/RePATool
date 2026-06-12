package rta.backend

import rta.syntax.Program2.{QName, RxGraph, Edge}
import rta.syntax.Condition
import rta.syntax.Formula as PdlFormula
import rta.syntax.Formula.*
import rta.syntax.PdlProgram
import rta.syntax.PathFormula
import rta.syntax.PdlProgram.*

import scala.annotation.tailrec
import scala.collection.mutable

object PdlEvaluator {
  private val epsilon = 0.00001
  private val maxIterations = 1000 

  case class StateSignature(
    inits: Set[QName], 
    vars: Map[QName, Int], 
    activeEdges: Set[Edge], 
    weights: Map[Edge, Double]
  )

  class EvaluatorSession() {
    private val transCache = mutable.Map[RxGraph, Set[(Edge, RxGraph)]]()
    private val formulaCache = mutable.Map[(RxGraph, PdlFormula), Boolean]()
    private val programCache = mutable.Map[(RxGraph, PdlProgram), Set[RxGraph]]()

    private val canonicalStates = mutable.Map[StateSignature, RxGraph]()

    def getSignature(rx: RxGraph): StateSignature = {
      StateSignature(rx.inits, rx.val_env, rx.act, rx.weights)
    }

    def getCanonical(rx: RxGraph): RxGraph = {
      val sig = getSignature(rx)
      canonicalStates.getOrElseUpdate(sig, rx)
    }

    def getTransitions(rx: RxGraph): Set[(Edge, RxGraph)] = {
      transCache.getOrElseUpdate(rx, {
        RxSemantics.nextEdge(rx).map { case (edge, nextRx) =>
          (edge, getCanonical(nextRx))
        }
      })
    }

    def evaluateRoot(config: RxGraph, formula: PdlFormula): String = {
      val canonicalStart = getCanonical(config)

      formula match {
        case PQuantitative(path) => 
          val prob = calculatePathProbability(canonicalStart, path)
          f"Result: $prob%.5f"
        case _ => 
          val res = evaluateFormula(canonicalStart, formula)
          s"Result: $res"
      }
    }

    def evaluateProgram(initialConfigs: Set[RxGraph], program: PdlProgram): Set[RxGraph] = {
      program match {
        case Act(nameFromFormula) =>
          initialConfigs.flatMap { config =>
            getTransitions(config)
              .filter { case ((from, _, id, label), _) =>
                val currentScope = from.scope
                nameFromFormula == label || nameFromFormula == id ||
                nameFromFormula == (currentScope / label) || nameFromFormula == (currentScope / id)
              }
              .map(_._2) 
          }

        case Seq(p, q) =>
          evaluateProgram(evaluateProgram(initialConfigs, p), q)

        case Choice(p, q) =>
          evaluateProgram(initialConfigs, p) ++ evaluateProgram(initialConfigs, q)

        case Star(p) =>
          val visited = mutable.Set[RxGraph]()
          val queue = mutable.Queue[RxGraph]()
          
          initialConfigs.foreach { c =>
            visited.add(c)
            queue.enqueue(c)
          }

          while (queue.nonEmpty) {
            val current = queue.dequeue()
            val nextConfigs = evaluateProgram(Set(current), p)
            for (next <- nextConfigs) {
              if (visited.add(next)) {
                queue.enqueue(next)
              }
            }
          }
          visited.toSet
      }
    }

    def evaluateFormula(config: RxGraph, formula: PdlFormula): Boolean = {
      formulaCache.getOrElseUpdate((config, formula), {
        if (config.inits.isEmpty) false
        else formula match {
          case True => true
          case False => false
          case StateProp(name) => config.inits.contains(name)
          case CondProp(cond)  => Condition.evaluate(cond, config)

          case Not(p)    => !evaluateFormula(config, p)
          case And(p, q) => evaluateFormula(config, p) && evaluateFormula(config, q)
          case Or(p, q)  => evaluateFormula(config, p) || evaluateFormula(config, q)
          case Impl(p, q) => !evaluateFormula(config, p) || evaluateFormula(config, q)
          case Iff(p, q)  => evaluateFormula(config, p) == evaluateFormula(config, q)

          case PQualitative(op, threshold, path) =>
            val prob = calculatePathProbability(config, path)
            compare(prob, op, threshold)
            
          case PQuantitative(_) => 
            throw new RuntimeException("P=? só pode ser usado na raiz da fórmula, não aninhado.")

          case PipeAnd(p, q) => getFinalConfigs(config, p).exists(inter => evaluateFormula(inter, q))
          case Diamond(p) => getTransitions(config).exists { case (_, nextRx) => evaluateFormula(nextRx, p) }
          case Box(p) => getTransitions(config).forall { case (_, nextRx) => evaluateFormula(nextRx, p) }
          case DiamondP(prog, p) => evaluateProgram(Set(config), prog).exists(finalConf => evaluateFormula(finalConf, p))
          case BoxP(prog, p) => 
            val finalConfigs = evaluateProgram(Set(config), prog)
            finalConfigs.nonEmpty && finalConfigs.forall(finalConf => evaluateFormula(finalConf, p))
          case ProbProp(prog, op, threshold, f) =>
            val transitions = getTransitions(config)
            val totalProb = transitions
              .filter { case (edge, nextRx) => isMatching(edge, prog) && evaluateFormula(nextRx, f) }
              .map { case (edge, _) => config.weights.getOrElse(edge, 0.0) }
              .sum
            compare(totalProb, op, threshold)
        }
      })
    }

    private def calculatePathProbability(startConfig: RxGraph, pathFormula: PathFormula): Double = {
      
      val reachableStates = mutable.Set[RxGraph]()
      val queue = mutable.Queue[RxGraph](startConfig)
      
      while (queue.nonEmpty) {
        val curr = queue.dequeue()
        if (reachableStates.add(curr)) {
          getTransitions(curr).foreach { case (_, next) => queue.enqueue(next) }
        }
      }
      val allStates = reachableStates.toList

      pathFormula match {
        
        case PathFormula.Next(f) =>
          getTransitions(startConfig).map { case (edge, nextState) =>
            val p = startConfig.weights.getOrElse(edge, 1.0)
            if (evaluateFormula(nextState, f)) p else 0.0
          }.sum

        case PathFormula.Future(f) =>
          calculatePathProbability(startConfig, PathFormula.Until(True, f))

        case PathFormula.Until(a, b) =>
          var V = mutable.Map[RxGraph, Double]()
          
          for (s <- allStates) {
            if (evaluateFormula(s, b)) V(s) = 1.0 
            else if (!evaluateFormula(s, a)) V(s) = 0.0 
            else if (getTransitions(s).isEmpty) V(s) = 0.0
            else V(s) = 0.0 
          }

          iterateValues(allStates, V, s => evaluateFormula(s, b) || !evaluateFormula(s, a))
          V(startConfig)

        case PathFormula.Globally(f) =>
          var V = mutable.Map[RxGraph, Double]()
          
          for (s <- allStates) {
            if (!evaluateFormula(s, f)) V(s) = 0.0
            else V(s) = 1.0
          }

          iterateValues(allStates, V, s => !evaluateFormula(s, f))
          V(startConfig)
      }
    }

    private def iterateValues(allStates: List[RxGraph], V: mutable.Map[RxGraph, Double], isFixed: RxGraph => Boolean): Unit = {
      val numStates = allStates.size
      
      val stateToId = allStates.zipWithIndex.toMap
      val idToState = allStates.toArray
      
      val vArray = new Array[Double](numStates)
      val fixedArray = new Array[Boolean](numStates)
      
      val transitionMatrix = new Array[Array[(Int, Double)]](numStates)

      for (i <- 0 until numStates) {
        val s = idToState(i)
        vArray(i) = V(s)
        fixedArray(i) = isFixed(s)
        
        if (!fixedArray(i)) {
          transitionMatrix(i) = getTransitions(s).map { case (edge, nextState) =>
            val p = s.weights.getOrElse(edge, 1.0)
            (stateToId(nextState), p)
          }.toArray
        } else {
          transitionMatrix(i) = Array.empty
        }
      }

      var maxDiff = 1.0
      var iteration = 0
      val nextVArray = new Array[Double](numStates)

      while (maxDiff > epsilon && iteration < maxIterations) {
        maxDiff = 0.0
        
        var i = 0
        while (i < numStates) {
          if (fixedArray(i)) {
            nextVArray(i) = vArray(i)
          } else {
            var sumProb = 0.0
            val trans = transitionMatrix(i)
            var j = 0
            while (j < trans.length) {
              sumProb += trans(j)._2 * vArray(trans(j)._1)
              j += 1
            }
            
            val diff = Math.abs(vArray(i) - sumProb)
            if (diff > maxDiff) maxDiff = diff
            
            nextVArray(i) = sumProb
          }
          i += 1
        }
        
        System.arraycopy(nextVArray, 0, vArray, 0, numStates)
        iteration += 1
      }

      for (i <- 0 until numStates) {
        V(idToState(i)) = vArray(i)
      }
    }

    private def calculateReachabilityProbability(startConfig: RxGraph, targetFormula: PdlFormula): Double = {
      val reachableStates = mutable.Set[RxGraph]()
      val queue = mutable.Queue[RxGraph](startConfig)
      
      while (queue.nonEmpty) {
        val curr = queue.dequeue()
        if (reachableStates.add(curr)) {
          getTransitions(curr).foreach { case (_, next) => queue.enqueue(next) }
        }
      }

      var V = mutable.Map[RxGraph, Double]()
      val allStates = reachableStates.toList

      for (s <- allStates) {
        if (evaluateFormula(s, targetFormula)) V(s) = 1.0 
        else if (getTransitions(s).isEmpty) V(s) = 0.0    
        else V(s) = 0.0                                   
      }

      iterateValues(allStates, V, s => evaluateFormula(s, targetFormula) || getTransitions(s).isEmpty)
      
      V(startConfig)
    }

    private def isMatching(edge: Edge, program: PdlProgram): Boolean = program match {
      case Act(nameFromFormula) =>
        val (from, _, id, label) = edge
        val currentScope = from.scope
        nameFromFormula == label || nameFromFormula == id ||
        nameFromFormula == (currentScope / label) || nameFromFormula == (currentScope / id)
      case _ => false 
    }

    private def compare(v1: Double, op: String, v2: Double): Boolean = op match {
      case ">=" => v1 >= v2 - epsilon
      case "<=" => v1 <= v2 + epsilon
      case ">"  => v1 > v2 + epsilon
      case "<"  => v1 < v2 - epsilon
      case "==" | "=" => Math.abs(v1 - v2) < epsilon
      case "!=" => Math.abs(v1 - v2) >= epsilon
      case _    => false
    }

    private def getFinalConfigs(config: RxGraph, formula: PdlFormula): Set[RxGraph] = {
      formula match {
        case DiamondP(prog, p) =>
          evaluateProgram(Set(config), prog).filter(finalConfig => evaluateFormula(finalConfig, p))
        case BoxP(prog, p) =>
          val finalConfigs = evaluateProgram(Set(config), prog)
          if (finalConfigs.forall(evaluateFormula(_, p))) finalConfigs else Set.empty
        case PipeAnd(p, q) =>
          getFinalConfigs(config, p).flatMap(getFinalConfigs(_, q))
        case _ =>
          if (evaluateFormula(config, formula)) Set(config) else Set.empty
      }
    }
  }

  def evaluateFormula(startState: QName, formula: PdlFormula, rx: RxGraph): String = {
    val initialConfig = rx.copy(inits = Set(startState))
    val session = new EvaluatorSession()
    session.evaluateRoot(initialConfig, formula)
  }
}