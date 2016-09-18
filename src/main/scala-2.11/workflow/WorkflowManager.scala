package workflow

import java.time.temporal.ChronoUnit
import java.time.{ZoneId, ZonedDateTime}
import java.util.{NoSuchElementException, UUID}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.{Consumer, IntUnaryOperator}

import akka.actor.{Actor, ActorSystem, Props}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.language.postfixOps
import scala.concurrent.duration.{Duration, FiniteDuration}

object WorkflowManager {

  private def newUUID = UUID.randomUUID().toString

  trait CleanupParams {
    def interval: FiniteDuration
    def olderThan: Duration
  }

  object DoCleanup extends CleanupParams {
    import scala.concurrent.duration._

    val interval = 1 minute
    val olderThan = 1 minute
  }

  object WorkflowId {
    def apply() = new WorkflowId(newUUID)
  }
  final case class WorkflowId(id: String)

  object Workflow {
    case class ExecutionId(id: String)

    object Execution {
      def apply(wf: Workflow) = new Execution(wf, ExecutionId(newUUID), ZonedDateTime.now(ZoneId.of("UTC")))
    }

    class Execution(val wf: Workflow, val id: ExecutionId, val created: ZonedDateTime) {
      private val step = new AtomicInteger(0)

      private def intOp(f: Int=>Int) = new IntUnaryOperator {
        override def applyAsInt(operand: Int) = f(operand)
      }

      def currentStep: Int = step.get()

      def nextStep : Try[Int] = {
        val prev = step.getAndUpdate( intOp{ s=> if (s < wf.numberOfSteps - 1 ) s + 1 else s })
        if (prev < wf.numberOfSteps - 1 )
          Try(prev+1)
        else
          Failure(new IllegalArgumentException("Maximum number of steps reached"))
      }

      def isFinished: Boolean = step.get() >=  wf.numberOfSteps - 1
    }
  }

  class Workflow(val id: WorkflowId, val numberOfSteps: Int) {
    import Workflow._
    private val executions = TrieMap[ExecutionId, Execution]()

    def createExecution = {
      val exec = Execution(this)
      executions.put(exec.id, exec)
      exec
    }

    private[workflow] def forExecution[T](execId: ExecutionId)(f: Execution=>T ) : Try[T] = {
      executions.get(execId) match {
        case None => {
          Failure(new NoSuchElementException(s"Execution ID $execId not found"))
        }
        case Some(a) => Success( f(a) )
      }
    }

    def getExecution(id: ExecutionId) = {
      Try( executions.get(id) )
    }

    def cleanup(olderThan: ZonedDateTime): Unit = {
      executions.readOnlySnapshot().filter
      { case (k, exec) =>  exec.isFinished && exec.created.compareTo(olderThan) < 0 }
        .foreach
      { case (key, _) => executions.remove(key) }
    }
  }

  def apply() = new WorkflowManager

  def apply(d: CleanupParams)(implicit as: ActorSystem) = {
    new WorkflowManager with Cleanup with CleanupParams {
      override val ec = as.dispatcher
      override val actorSystem = as
      val interval = d.interval
      val olderThan = d.olderThan
    }
  }
}

trait Cleanup {
  manager: WorkflowManager with WorkflowManager.CleanupParams =>

  implicit def ec: ExecutionContext
  def actorSystem : ActorSystem

  trait Cleaner {
    def stop(): Unit
  }

  private case object Tick
  private case object Stop

  private class CleanupScheduler extends Actor {
    import scala.concurrent.duration._

    val ticker =
      context.system.scheduler.schedule(1 milli, manager.interval, self, Tick)

    override def postStop() = ticker.cancel()

    def receive = {
      case Tick =>
        val cutOff = ZonedDateTime.now(ZoneId.of("UTC")).minus( manager.olderThan.toMillis, ChronoUnit.MILLIS )
        manager.cleanup(cutOff)
      case Stop =>
        context.stop(self)
    }
  }

  def startCleanup: Cleaner = {
    new Cleaner {
      val ref = actorSystem.actorOf(Props(new CleanupScheduler))
      def stop(): Unit = {
        ref ! Stop
      }
    }
  }
}

class WorkflowManager {
  import WorkflowManager._
  import Workflow.ExecutionId

  private val registry = new ConcurrentHashMap[WorkflowId, Workflow]()

  private[workflow] def forWorkflow[T](wfId: WorkflowId)(f: Workflow => T): Try[T] = {
    registry.get(wfId) match {
      case null => Failure(new NoSuchElementException(s"Workflow ID $wfId not found"))
      case wf: Workflow => Try( f(wf) )
    }
  }

  def cleanup(olderThan: ZonedDateTime): Unit = {
    registry.forEachValue(8192, new Consumer[Workflow] {
      override def accept(t: Workflow) = t.cleanup(olderThan)
    } )
  }

  def createWorkflow(numOfSteps: Int): Try[WorkflowId] = {
    if (numOfSteps < 1)
      Failure(new IllegalArgumentException(s"Invalid number of steps $numOfSteps"))
    else {
      val wf = new Workflow(WorkflowId(), numOfSteps)
      registry.put( wf.id, wf )
      Success( wf.id )
    }
  }

  def createExecution(wfId: WorkflowId): Try[ExecutionId] = {
    forWorkflow(wfId){ _.createExecution.id }
  }

  def nextExecutionStep(wfId: WorkflowId, executionId: ExecutionId ): Try[Int] = {
    val r = forWorkflow(wfId) { _.forExecution(executionId) { exec=> exec.nextStep} }
    r.flatten.flatten
  }

  def isFinished(wfId: WorkflowId, executionId: ExecutionId) : Try[Boolean] = {
    val r = forWorkflow(wfId) { _.forExecution(executionId) { exec=> exec.isFinished} }
    r.flatten
  }
}

