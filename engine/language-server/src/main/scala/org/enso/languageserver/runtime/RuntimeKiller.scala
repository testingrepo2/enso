package org.enso.languageserver.runtime

import java.util.UUID
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import com.typesafe.scalalogging.LazyLogging
import org.enso.languageserver.boot.ComponentSupervisor
import org.enso.languageserver.runtime.RuntimeKiller._
import org.enso.languageserver.util.UnhandledLogging
import org.enso.polyglot.runtime.Runtime.Api

import scala.concurrent.duration._
import scala.util.control.NonFatal

/** An actor that shuts the runtime down. It implements a routine responsible
  * for disposing of runtime resources and closing the Truffle context in a
  * resilient manner.
  *
  * @param runtimeConnector a proxy to the runtime
  * @param truffleContext a Truffle context
  */
class RuntimeKiller(
  runtimeConnector: ActorRef,
  truffleContextSupervisor: ComponentSupervisor
) extends Actor
    with LazyLogging
    with UnhandledLogging {

  import context.dispatcher

  override def receive: Receive = idle()

  private def idle(): Receive = { case ShutDownRuntime =>
    logger.info("Shutting down the runtime server [{}].", runtimeConnector)
    runtimeConnector ! Api.Request(
      UUID.randomUUID(),
      Api.ShutDownRuntimeServer()
    )
    val cancellable =
      context.system.scheduler
        .scheduleOnce(5.seconds, self, ResourceDisposalTimeout)
    context.become(shuttingDownRuntimeServer(sender(), cancellable))
  }

  private def shuttingDownRuntimeServer(
    replyTo: ActorRef,
    cancellable: Cancellable
  ): Receive = {
    case ResourceDisposalTimeout =>
      logger.error("Disposal of runtime resources timed out.")
      shutDownTruffle(replyTo)

    case Api.Response(_, Api.RuntimeServerShutDown()) =>
      cancellable.cancel()
      shutDownTruffle(replyTo)
  }

  private def shuttingDownTruffle(
    replyTo: ActorRef,
    retryCount: Int
  ): Receive = { case TryToStopTruffle =>
    shutDownTruffle(replyTo, retryCount)
  }

  private def shutDownTruffle(replyTo: ActorRef, retryCount: Int = 0): Unit = {
    try {
      logger.info(
        "Shutting down the Truffle context. Attempt #{}.",
        retryCount + 1
      )
      truffleContextSupervisor.close()
      replyTo ! RuntimeGracefullyStopped
      context.stop(self)
    } catch {
      case NonFatal(ex) =>
        logger.error(
          s"An error occurred during stopping Truffle context. {}",
          ex.getMessage
        )
        if (retryCount < MaxRetries) {
          context.system.scheduler
            .scheduleOnce((retryCount + 1).seconds, self, TryToStopTruffle)
          context.become(shuttingDownTruffle(replyTo, retryCount + 1))
        } else {
          replyTo ! RuntimeNotStopped
          context.stop(self)
        }
    }
  }

}

object RuntimeKiller {

  /** Number of retries to close a Truffle context.
    */
  val MaxRetries = 3

  /** A command that starts shutting down the Runtime server.
    */
  case object ShutDownRuntime

  /** A base trait for ADT of a shutdown result.
    */
  sealed trait RuntimeShutdownResult

  /** Signals that the Runtime stopped gracefully.
    */
  case object RuntimeGracefullyStopped extends RuntimeShutdownResult

  /** Signals that it is impossible to shut the runtime down.
    */
  case object RuntimeNotStopped extends RuntimeShutdownResult

  private case object ResourceDisposalTimeout

  private case object TryToStopTruffle

  /** Creates configuration object used to create a [[RuntimeKiller]].
    *
    * @param runtimeConnector a proxy to the runtime
    * @param truffleContext a Truffle context
    * @return a configuration object
    */
  def props(
    runtimeConnector: ActorRef,
    truffleContextSupervisor: ComponentSupervisor
  ): Props =
    Props(new RuntimeKiller(runtimeConnector, truffleContextSupervisor))

}
