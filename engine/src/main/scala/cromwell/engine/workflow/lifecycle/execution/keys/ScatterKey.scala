package cromwell.engine.workflow.lifecycle.execution.keys

import akka.actor.ActorRef
import cats.syntax.either._
import cats.syntax.validated._
import common.Checked
import common.collections.EnhancedCollections._
import common.validation.ErrorOr.ErrorOr
import cromwell.backend.BackendJobDescriptorKey
import cromwell.backend.BackendJobExecutionActor.JobFailedNonRetryableResponse
import cromwell.core.{ExecutionStatus, JobKey}
import cromwell.engine.workflow.lifecycle.execution.stores.ValueStore.ValueKey
import cromwell.engine.workflow.lifecycle.execution.{WorkflowExecutionActorData, WorkflowExecutionDiff}
import wom.graph.ScatterNode.{ScatterCollectionFunction, ScatterVariableAndValue}
import wom.graph._
import wom.graph.expression.{ExposedExpressionNode, ExpressionNodeLike}
import wom.values.WomArray.WomArrayLike
import wom.values.WomValue

import scala.language.postfixOps

private [execution] case class ScatterKey(node: ScatterNode) extends JobKey {

  // When scatters are nested, this might become Some(_)
  override val index = None
  override val attempt = 1
  override val tag = node.localName

  def makeCollectors(count: Int, scatterCollectionFunction: ScatterCollectionFunction): Set[ScatterCollectorKey] = (node.outputMapping.groupBy(_.outputToGather.source.graphNode) flatMap {
    case (_: CallNode | _: ExposedExpressionNode | _: ConditionalNode, scatterGatherPorts) =>
      scatterGatherPorts.map(sgp => ScatterCollectorKey(sgp, count, scatterCollectionFunction))
    case _ => Set.empty[ScatterCollectorKey]
  }).toSet

  /**
    * Creates a sub-ExecutionStore with Starting entries for each of the scoped children.
    *
    * @param count Number of ways to scatter the children.
    * @return ExecutionStore of scattered children.
    */
  def populate(count: Int, scatterCollectionFunction: ScatterCollectionFunction): Map[JobKey, ExecutionStatus.Value] = {
    val shards = node.innerGraph.nodes flatMap { makeShards(_, count) }
    val collectors = makeCollectors(count, scatterCollectionFunction)
    val callCompletions = node.innerGraph.nodes.filterByType[CallNode].map(cn => ScatteredCallCompletionKey(cn, count))
    (shards ++ collectors ++ callCompletions) map { _ -> ExecutionStatus.NotStarted } toMap
  }

  private def makeShards(scope: GraphNode, count: Int): Seq[JobKey] = scope match {
    case commandCall: CommandCallNode => (0 until count) map { i => BackendJobDescriptorKey(commandCall, Option(i), 1) }
    case expression: ExpressionNodeLike => (0 until count) map { i => ExpressionKey(expression, Option(i)) }
    case conditional: ConditionalNode => (0 until count) map { i => ConditionalKey(conditional, Option(i)) }
    case subworkflow: WorkflowCallNode => (0 until count) map { i => SubWorkflowKey(subworkflow, Option(i), 1) }
    case _: GraphInputNode => List.empty
    case _: PortBasedGraphOutputNode => List.empty
    case _: ScatterNode =>
      throw new UnsupportedOperationException("Nested Scatters are not supported (yet) ... but you might try a sub workflow to achieve the same effect!")
    case e =>
      throw new UnsupportedOperationException(s"Scope ${e.getClass.getName} is not supported.")
  }

  def processRunnable(data: WorkflowExecutionActorData, workflowExecutionActor: ActorRef, maxScatterWidth: Int): ErrorOr[WorkflowExecutionDiff] = {
    import cats.instances.list._
    import cats.syntax.traverse._

    def getScatterArray(scatterVariableNode: ScatterVariableNode): ErrorOr[ScatterVariableAndValue] = {
      val expressionNode = scatterVariableNode.scatterExpressionNode
      data.valueStore.get(expressionNode.singleOutputPort, None) map {
        case WomArrayLike(arrayLike) => ScatterVariableAndValue(scatterVariableNode, arrayLike).validNel
        case v: WomValue =>
          s"Scatter collection ${expressionNode.womExpression.sourceString} must evaluate to an array but instead got ${v.womType.toDisplayString}".invalidNel
      } getOrElse {
        s"Could not find an array value for scatter $tag. Missing array should have come from expression ${expressionNode.womExpression.sourceString}".invalidNel
      }
    }

    // The scatter iteration nodes mapped to their value retrieved from the value store
    val scatterArraysValuesCheck: Checked[List[ScatterVariableAndValue]] = node
      // Get all the iteration nodes (there will be as many as variables we're scattering over)
      .scatterVariableNodes
      // Retrieve the values of the collection nodes value from the ValueStore
      .traverse(getScatterArray)
      // Convert to either so we can flatMap later
      .toEither

    // Execution changes (for execution store and value store) generated by the scatter iteration nodes
    def buildExecutionChanges(scatterVariableAndValues: List[ScatterVariableAndValue]) = {
      val (executionStoreChanges, valueStoreChanges) = scatterVariableAndValues.map({
        case ScatterVariableAndValue(scatterVariableNode, arrayValue) =>
          val executionStoreChange = ScatterVariableInputKey(scatterVariableNode, arrayValue) -> ExecutionStatus.Done
          val valueStoreChange = ValueKey(scatterVariableNode.singleOutputPort, None) -> arrayValue

          executionStoreChange -> valueStoreChange
      }).unzip

      executionStoreChanges.toMap -> valueStoreChanges.toMap
    }

    // Checks the scatter width of a scatter node and builds WorkflowExecutionDiff accordingly
    // If scatter width is more than max allowed limit, it fails the ScatterNode key
    def buildExecutionDiff(scatterSize: Int, arrays: List[ScatterVariableAndValue]): WorkflowExecutionDiff = {
      if(scatterSize > maxScatterWidth) {
        workflowExecutionActor ! JobFailedNonRetryableResponse(this, new Exception(s"Workflow has scatter width $scatterSize, which is more than the max scatter width $maxScatterWidth allowed per scatter!"), None)

        WorkflowExecutionDiff(Map(this -> ExecutionStatus.Failed))
      }
      else {
        val (scatterVariablesExecutionChanges, valueStoreChanges) = buildExecutionChanges(arrays)
        val executionStoreChanges = populate(
          scatterSize,
          node.scatterCollectionFunctionBuilder(arrays.map(_.arrayValue.size))
        ) ++ scatterVariablesExecutionChanges ++ Map(this -> ExecutionStatus.Done)

        WorkflowExecutionDiff(
          executionStoreChanges = executionStoreChanges,
          valueStoreAdditions = valueStoreChanges
        )
      }
    }


    (for {
      arrays <- scatterArraysValuesCheck
      scatterSize <- node.scatterProcessingFunction(arrays)
    } yield buildExecutionDiff(scatterSize, arrays)).toValidated
  }
}
