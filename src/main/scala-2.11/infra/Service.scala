package infra

import java.util.NoSuchElementException

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import workflow.WorkflowManager
import workflow.WorkflowManager._
import scala.util.{Failure, Success}

final case class WorkflowReq(number_of_steps: Int)
final case class WorkflowIdReqResp(workflow_id: String)
final case class WorkflowExecId(workflow_execution_id: String)
final case class ExecutionStatus(finished: Boolean)

private[infra] object Do {

  def createWorkflow(mgr: WorkflowManager, numberOfSteps: Int)(implicit ec: ExecutionContext) = {
    Future {
      mgr.createWorkflow(numberOfSteps)
    }
  }

  def isFinished(mgr: WorkflowManager, wfId: String, execId: String)(implicit ec: ExecutionContext) = {
    Future {
      mgr.isFinished(WorkflowId(wfId), Workflow.ExecutionId(execId) )
    }
  }

  def createExec(mgr: WorkflowManager, wfId: String )(implicit ec: ExecutionContext) = {
    Future {
      mgr.createExecution( WorkflowId(wfId) )
    }
  }

  def nextExecutionStep(mgr: WorkflowManager, wfId: String, execId: String)(implicit ec: ExecutionContext) = {
    Future {
      mgr.nextExecutionStep(WorkflowId(wfId), Workflow.ExecutionId(execId) )
    }
  }
}

trait Service {
  implicit val system = ActorSystem("workflow-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  implicit val wfReqFormat = jsonFormat1(WorkflowReq)
  implicit val wfIdFormat = jsonFormat1(WorkflowIdReqResp)
  implicit val wfExecIdFormat = jsonFormat1(WorkflowExecId)
  implicit val execStatusFormat = jsonFormat1(ExecutionStatus)

  final val WORKFLOWS = "workflows"
  final val EXECUTIONS = "executions"

  def wfManager: WorkflowManager

   private def errorCode(err: Throwable): ToResponseMarshallable = err match {
    case _ : NoSuchElementException => StatusCodes.NotFound
    case _ : IllegalArgumentException => StatusCodes.BadRequest
    case _ => StatusCodes.InternalServerError
  }

  val route: Route =
    get {
      // GET /workflows/<workflow_id>/executions/<workflow_execution_id>
      pathPrefix(WORKFLOWS / Segment / EXECUTIONS / Segment ) { (wfId, execId)=>

        val res = Do.isFinished(wfManager, wfId, execId)
        onSuccess(res) {
          case Success(isFinished) => complete( (StatusCodes.OK, ExecutionStatus(isFinished)) )
          case Failure(e) => complete( errorCode(e) )
        }
      }
    } ~
    post {
      path(WORKFLOWS) {
        entity(as[WorkflowReq]) { wfReq =>
          val saved = Do.createWorkflow(wfManager, wfReq.number_of_steps)
          onSuccess(saved) { done =>
            complete( (StatusCodes.Created, WorkflowIdReqResp(done.get.id)) )
          }
        }
      }} ~
    post {
      // POST /workflows/<workflow_id>/executions  with empty body
      path(WORKFLOWS / Segment / EXECUTIONS) { wfId =>
        val res = Do.createExec(wfManager, wfId)
        onSuccess(res) {
          case Success(exId) => complete((StatusCodes.Created, WorkflowExecId(exId.id)))
          case Failure(e) => complete(StatusCodes.NotFound)
        }
      }
    } ~
    put {
      // PUT /workflows/<workflow_id>/executions/<workflow_execution_id>  with empty body
      path(WORKFLOWS / Segment / EXECUTIONS / Segment ) { (wfId, execId)=>
        val res = Do.nextExecutionStep(wfManager, wfId, execId)
        onSuccess(res) {
          case Success(_) => complete(StatusCodes.NoContent)
          case Failure(e) => complete( errorCode(e) )
        }
      }
    }
}
