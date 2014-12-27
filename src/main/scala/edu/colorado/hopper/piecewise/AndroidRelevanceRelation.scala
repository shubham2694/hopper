package edu.colorado.hopper.piecewise

import com.ibm.wala.analysis.pointers.HeapGraph
import com.ibm.wala.ipa.callgraph.propagation.{HeapModel, InstanceKey}
import com.ibm.wala.ipa.callgraph.{CGNode, CallGraph}
import com.ibm.wala.ipa.cha.IClassHierarchy
import com.ibm.wala.ssa.SSAInstruction
import com.ibm.wala.util.graph.impl.GraphInverter
import com.ibm.wala.util.graph.traverse.BFSPathFinder
import com.ibm.wala.util.intset.OrdinalSet
import edu.colorado.hopper.state.{PtEdge, Qry}
import edu.colorado.walautil.{CFGUtil, ClassUtil}

import scala.collection.JavaConversions._

// relevance relation that filters away instructions that are not control-feasible based on domain-specific information
// about Android
class AndroidRelevanceRelation(cg : CallGraph, hg : HeapGraph[InstanceKey], hm : HeapModel, cha : IClassHierarchy,
                               cgTransitiveClosure : java.util.Map[CGNode,OrdinalSet[CGNode]] = null)
  extends RelevanceRelation(cg, hg, hm, cha, cgTransitiveClosure) {

  val invertedCG = GraphInverter.invert(cg)

  val DEBUG = false

  override def getConstraintProducerMap(q : Qry, ignoreLocalConstraints : Boolean = false) : Map[PtEdge,List[(CGNode,SSAInstruction)]] = {
    val constraintProducerMap = super.getConstraintProducerMap(q, ignoreLocalConstraints)
    // TODO: filter!

    /*constraintProducerMap.map(pair => pair._1 match {
      case ObjPtEdge(_, InstanceFld(f), _) =>
        val fldClass = f.getReference.getDeclaringClass
        val methods = pair._2.filter(pair => pair._1.getMethod.getDeclaringClass.getReference == fldClass && // the "this" pointer is used
      case _ => pair
    })*/




    constraintProducerMap
  }


  // given current label l_cur and two relevant labels l_1 and l_2, we have two ways to rule out l_1/l_2
  // (1) l_1 and/or l_2 is not backward reachable from l_cur
  // (2) if every concrete traces visits l_1 -> l_2 -> l_cur, we can rule out l_1

  /** check condition (1); @return true if @param toFilter is not backward-reachable from @param curNode */
  def isNotBackwardReachableFrom(toFilter : CGNode, curNode : CGNode) : Boolean = {
    // TODO: implement more precise check here?
    false
  }

  // TODO: there's some unstated precondition for being able to call this at all...constraints must be fields of the
  // *same* object instance whose methods we are trying to filter, and writes to fields of that object must be through
  // the "this" pointer, or something like that. alternatively, the class whose methods are under consideration is one
  // that is somehow known or proven to have only one instance in existence at a time,

  /** @return true if we can prove that @param toFilter is control-infeasible with respect to @param curNode based on
    * the fact that @param otherRelNodes are also relevant */
  def canFilter(curNode : CGNode, toFilter : CGNode, nodeProducerMap : Map[CGNode,Set[SSAInstruction]]) : Boolean =
    isNotBackwardReachableFrom(toFilter, curNode) || {
      val path = new BFSPathFinder(cg, toFilter, curNode).find()
      // TODO: this is *very* unsound, but need to do it for now to avoid absurd paths. fix CG issues that cause this later
      val reachable =
        path != null && path.size > 0 && path.exists(n => n != toFilter && n != curNode && !ClassUtil.isLibrary(n)) && path.size < 20
      if (reachable) {
        println(s"can't filter $toFilter since it's reachable from ${ClassUtil.pretty(curNode)}")
        path.foreach(println)
      }

      // check if there is a path from toFilter to curNode in the call graph; if so, we can't (easily) filter, so don't try
      !reachable && {
        // TODO: what about subclassing? is there something we can do here about superclass constructors without being unsound?
        val toFilterMethod = toFilter.getMethod
        val toFilterClass = toFilterMethod.getDeclaringClass
        val res =
        // we can filter if toFilter is a constructor o.<init>() and one of otherRelNodes is a method o.m()
        // TODO: or a callee of o.m(). but this will force us to generalize the conditional check below
          (toFilterMethod.isInit &&
            nodeProducerMap.keys.exists(n => n != toFilter && {
              val m = n.getMethod
              !m.isInit && !m.isClinit
              m.getDeclaringClass == toFilterClass
            } && nodeProducerMap(n).exists(i => !CFGUtil.isGuardedByConditional(i, n)))
            ) ||
            // .. or similarly for a class initializer o.<clinit> and any method o.m()
            (toFilterMethod.isClinit &&
              nodeProducerMap.keys.exists(n => n != toFilter && n.getMethod.getDeclaringClass == toFilterClass &&
                nodeProducerMap(n).exists(i => !CFGUtil.isGuardedByConditional(i, n))))
        // TODO: we don't actually need this check for Android so long as we only jump at the "harness boundary", but we may need it elsewhere
        // if n (transtively) calls curNode, we don't know if it's relevant instruction will execute before curNode is
        // reached or not. we can try to figure this out, but it's rather hard so for now we just insist on unreachbility
        //!DFS.getReachableNodes(cg, java.util.Collections.singleton(n)).contains(curNode) &&
        // there must be some relevant instruction that cannot be guarded by a conditional, otherwise we cannot
        // guarantee that it will execute before we reach curNode
        // ...and there exists a relevant command in otherRelNode that must be executed on the path to the current block in curNode
        // TODO: && there must be some i in n that is not guarded by a catch block locally, and n should not be guarded by a catch block in any of its callers
        if (DEBUG && res) println(s"Filtered node $toFilter!")
        res
      }
    }


  override def getNodeProducerMap(q : Qry,
                                  ignoreLocalConstraints : Boolean = false) : Map[CGNode,Set[SSAInstruction]] = {
    val nodeProducerMap = super.getNodeProducerMap(q, ignoreLocalConstraints)
    nodeProducerMap.filterNot(pair => canFilter(q.node, pair._1, nodeProducerMap))
  }

}