package workflow

import java.time.{ZoneId, ZonedDateTime}
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, ActorSystem, Props}

import scala.concurrent.ExecutionContext

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
    import scala.language.postfixOps

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
