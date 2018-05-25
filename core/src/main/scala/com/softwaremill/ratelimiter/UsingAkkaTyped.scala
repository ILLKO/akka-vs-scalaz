package com.softwaremill.ratelimiter

import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorSystem, Behavior}
import com.softwaremill.ratelimiter.RateLimiterQueue.{Run, RunAfter}

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

object UsingAkkaTyped {
  class AkkaTypedRateLimiter(actorSystem: ActorSystem[RateLimiterMsg]) extends RateLimiter[Future] {
    def runLimited[T](f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
      val p = Promise[T]
      actorSystem ! LazyFuture(() => f.andThen { case r => p.complete(r) }.map(_ => ()))
      p.future
    }

    def stop(): Unit = {
      actorSystem.terminate()
    }
  }

  object AkkaTypedRateLimiter {
    def create(maxRuns: Int, per: FiniteDuration): RateLimiter[Future] = {
      val behavior = Behaviors.withTimers[RateLimiterMsg] { timer =>
        rateLimit(timer, RateLimiterQueue(maxRuns, per.toMillis, Queue.empty, Queue.empty, scheduled = false))
      }
      new AkkaTypedRateLimiter(ActorSystem(behavior, "rate-limiter"))
    }

    private def rateLimit(timer: TimerScheduler[RateLimiterMsg], data: RateLimiterQueue[LazyFuture]): Behavior[RateLimiterMsg] =
      Behaviors.receiveMessage {
        case lf: LazyFuture[Unit] => rateLimit(timer, pruneAndRun(timer, data.enqueue(lf)))
        case PruneAndRun => rateLimit(timer, pruneAndRun(timer, data.notScheduled))
      }

    private def pruneAndRun(timer: TimerScheduler[RateLimiterMsg], data: RateLimiterQueue[LazyFuture]): RateLimiterQueue[LazyFuture] = {
      val now = System.currentTimeMillis()

      val (tasks, data2) = data.pruneAndRun(now)
      tasks.foreach {
        case Run(LazyFuture(f)) => f()
        case RunAfter(millis)   => timer.startSingleTimer((), PruneAndRun, millis.millis)
      }

      data2
    }
  }

  private sealed trait RateLimiterMsg
  private case class LazyFuture[T](t: () => Future[T]) extends RateLimiterMsg
  private case object PruneAndRun extends RateLimiterMsg
}
