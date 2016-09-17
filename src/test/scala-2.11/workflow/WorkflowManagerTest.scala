package workflow

import java.time.{ZoneId, ZonedDateTime}
import java.util.NoSuchElementException

import akka.actor.ActorSystem
import org.scalatest.FunSuite
import workflow.WorkflowManager.Workflow.ExecutionId
import workflow.WorkflowManager.{CleanupParams, WorkflowId}

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration}

class WorkflowManagerTest extends FunSuite {

  private def doCleanupTest(wfManager: WorkflowManager, cutOff: ZonedDateTime)(doClean: => Unit): Unit = {
    val execs = genExecutions(wfManager)
    val (toExecute, toLeave) = execs.partition {
      case (wId, stepsLeft, eId) => stepsLeft % 2 == 1 &&
        wfManager.forWorkflow(wId){ _.forExecution(eId) { _.created } }.flatten.get.compareTo(cutOff) < 0
    }
    execAll(wfManager, toExecute)

    doClean

    toExecute.foreach {
      case (wfId, _, execId) =>
        val r = wfManager.isFinished(wfId, execId)
        assert(r.isFailure)
        assert(r.failed.get.isInstanceOf[NoSuchElementException])
    }
    toLeave.foreach {
      case (wfId, _, execId) =>
        assert(!wfManager.isFinished(wfId, execId).get)
    }
  }

  test("testCleanup") {
    val wfManager = WorkflowManager()

    def doTest(cutOff: ZonedDateTime) = doCleanupTest(wfManager, cutOff) { wfManager.cleanup(cutOff) }

    doTest( ZonedDateTime.now(ZoneId.of("UTC")).plusDays(1) )
    doTest( ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1) )
  }


  test("testAutoCleanup") {
    import scala.language.postfixOps
    implicit val system = ActorSystem("workflow-system")
    val wfManager = WorkflowManager(new  CleanupParams {
      import scala.concurrent.duration._
      val interval = 1 day
      val olderThan = 1 milli
    })

    doCleanupTest(wfManager, ZonedDateTime.now(ZoneId.of("UTC")).plusSeconds(1)) {
      val cleaner = wfManager.startCleanup
      Thread.sleep(java.time.Duration.ofSeconds(1).toMillis)
      cleaner.stop()
    }
  }

  test("testCreateExecution") {
    val wfManager = WorkflowManager()
    val wf1 = wfManager.createWorkflow(5)
    assert(wf1.isSuccess)

    val exec1 = wfManager.createExecution(wf1.get)
    assert(exec1.isSuccess)

    val exec2 = wfManager.createExecution( WorkflowManager.WorkflowId("random") )
    assert(exec2.isFailure)

    assert( exec2.failed.get.isInstanceOf[NoSuchElementException] )
  }

  test("testCreateWorkflow") {
    val wfManager = WorkflowManager()

    val wf1 = wfManager.createWorkflow(1)
    assert(wf1.isSuccess)

    val wf2 = wfManager.createWorkflow(1)
    assert(wf2.isSuccess)

    assert( wf1 != wf2 )

    (-1 to 0).foreach { n =>
      val wf3 = wfManager.createWorkflow(n)
      assert(wf3.isFailure)
      assert(wf3.failed.get.isInstanceOf[IllegalArgumentException])
    }
  }

  test("testNextExecutionStep") {
    import WorkflowManager._
    val wfManager = WorkflowManager()
    val wfAndExecs = genExecutions(wfManager)
    execAll(wfManager, wfAndExecs)
  }

  private def genExecutions(wfManager: WorkflowManager) = {
    Seq(3, 5, /* 1, TODO: according to the spec 1 step is not possible*/ 2, 4, 8).flatMap { numSteps =>
      val wfId = wfManager.createWorkflow(numSteps).get
      (1 to numSteps).map { _ => (wfId, numSteps - 1, wfManager.createExecution(wfId).get) }
    }
  }

  @tailrec
  private
  def execAll(wfManager: WorkflowManager, execs: Seq[(WorkflowId, Int, ExecutionId)]) : Unit = {
    val r = execs.map {
      case (wf, stepsLeft, execId) if stepsLeft > 0 =>
        assert(wfManager.nextExecutionStep(wf, execId).isSuccess)
        (wf, stepsLeft - 1, execId)
      case a => a
    }
    if (! r.forall{
      case (wfId, ns, execId)=> (ns == 0) && wfManager.isFinished(wfId, execId).get
    })
      execAll(wfManager, r)
  }


}
