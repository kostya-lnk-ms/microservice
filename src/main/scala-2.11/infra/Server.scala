package infra

import akka.http.scaladsl.Http
import workflow.WorkflowManager

import scala.io.StdIn

object Server extends Service {

  override val wfManager = WorkflowManager(WorkflowManager.DoCleanup)
  val bindIFace = "0.0.0.0"

  def main(args: Array[String]) {

    val cleaner = wfManager.startCleanup
    val bindingFuture = Http().bindAndHandle( route, bindIFace, 9000)
    println(s"Server online at http://$bindIFace:9000/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => {
        cleaner.stop()
        system.terminate()
      }) // and shutdown when done
  }

}
