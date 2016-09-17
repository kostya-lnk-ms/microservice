package infra

import akka.http.scaladsl.model.{HttpEntity, _}
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{Matchers, WordSpec}
import spray.json.{JsonParser, ParserInput}
import workflow.WorkflowManager

class RestTestSpec extends WordSpec with Matchers with ScalatestRouteTest  {

  private def httpJsonReq(v: spray.json.JsValue) = HttpEntity(MediaTypes.`application/json`, v.toString)

  "Workflow API" should {

    val svc = new Service {
      override val wfManager = WorkflowManager()
    }

    val rt = Route.seal( svc.route )

    def createWorkflow(steps: Int)(checks: =>Unit) = {
      Post("/" + svc.WORKFLOWS, httpJsonReq( svc.wfReqFormat.write(WorkflowReq(steps))) ) ~> rt ~> check {
        mediaType === MediaTypes.`application/json`
        checks
        if (status.isSuccess()) {
          val js = JsonParser(ParserInput(responseAs[String]))
          val r = svc.wfIdFormat.read(js)
          Some(r)
        } else
          None
      }
    }

    def createExecution(wfId: WorkflowIdResp)(checks: => Unit) = {
      Post(s"/${svc.WORKFLOWS}/${wfId.workflow_id}/${svc.EXECUTIONS}") ~> rt ~> check {
        mediaType === MediaTypes.`application/json`
        checks
        if (status.isSuccess()) {
          val js = JsonParser(ParserInput(responseAs[String]))
          val r = svc.wfExecIdFormat.read(js)
          Some(r)
        } else
          None
      }
    }

    def nextStep(wfId: WorkflowIdResp, execId: WorkflowExecId)(checks: => Unit) = {
      Put(s"/${svc.WORKFLOWS}/${wfId.workflow_id}/${svc.EXECUTIONS}/${execId.workflow_execution_id}") ~> rt ~> check {
        mediaType === MediaTypes.`application/json`
        checks
      }
    }

    def getState(wfId: WorkflowIdResp, execId: WorkflowExecId)(checks: => Unit) = {
      Get(s"/${svc.WORKFLOWS}/${wfId.workflow_id}/${svc.EXECUTIONS}/${execId.workflow_execution_id}") ~> rt ~> check {
        mediaType === MediaTypes.`application/json`
        checks
        if (status.isSuccess()) {
          val js = JsonParser(ParserInput(responseAs[String]))
          val r = svc.execStatusFormat.read(js)
          Some(r)
        } else
          None
      }
    }

    def createdCheck(): Unit = {
      status.isSuccess() shouldEqual true
      status === StatusCodes.Created
    }

    def noContentCheck(): Unit = {
      status.isSuccess() shouldEqual true
      status === StatusCodes.Created
    }

    def OKCheck(): Unit = {
      status.isSuccess() shouldEqual true
      status === StatusCodes.OK
    }

    def notFoundCheck(): Unit = {
      status.isSuccess() shouldEqual false
      status === StatusCodes.NotFound
    }

    def badRequestCheck(): Unit = {
      status.isSuccess() shouldEqual false
      status === StatusCodes.BadRequest
    }

    s"Post'ing to ${svc.WORKFLOWS} creates a new workflow with a given number of steps" in {
      createWorkflow(1) (createdCheck _)
      createWorkflow(0) { status.isSuccess() shouldEqual false }
    }

    s"Post'ing to ${svc.WORKFLOWS}/<workflow_id>/${svc.EXECUTIONS} should create an execution" in {
      val wfId = createWorkflow(2) (createdCheck _)
      createExecution(wfId.get) (createdCheck _)
      createExecution(WorkflowIdResp("zebra")) (notFoundCheck _)
      }

    s"Put'ing to ${svc.WORKFLOWS}/<workflow_id>/${svc.EXECUTIONS}/<workflow_execution_id> should increment step" in {
      val wfId = createWorkflow(2) (createdCheck _)
      val execId = createExecution(wfId.get) (createdCheck _)
      nextStep(wfId.get, execId.get) (noContentCheck _)

      nextStep(WorkflowIdResp("zebra"), execId.get) (notFoundCheck _)
      nextStep(wfId.get, WorkflowExecId("pont")) (notFoundCheck _)

      nextStep(wfId.get, execId.get) (badRequestCheck _)
    }

    s"Get'ing the ${svc.WORKFLOWS}/<workflow_id>/${svc.EXECUTIONS}/<workflow_execution_id> should return completion state" in {
      val wfId = createWorkflow(2) (createdCheck _)
      val execId = createExecution(wfId.get) (createdCheck _)
      val r1 = getState(wfId.get, execId.get) (OKCheck _)
      r1.get.finished === false
      nextStep(wfId.get, execId.get) (noContentCheck _)
      val r2 = getState(wfId.get, execId.get) (OKCheck _)
      r2.get.finished === true

      getState(WorkflowIdResp("zebra"), execId.get) (notFoundCheck _)
      getState(wfId.get, WorkflowExecId("pont")) (notFoundCheck _)
    }


  }

}