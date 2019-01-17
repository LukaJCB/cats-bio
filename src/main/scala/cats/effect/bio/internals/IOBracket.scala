package cats.effect.bio.internals

/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.bio.BIO
import cats.effect.bio.BIO.ContextSwitch
import cats.effect.bio.internals.IORunLoop.CustomException
import cats.effect.internals.Logger
import cats.effect.internals.TrampolineEC.immediate
import cats.effect.{CancelToken, ExitCase}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

private[effect] object IOBracket {
  /**
    * Implementation for `IO.bracketCase`.
    */
  def apply[E, A, B](acquire: BIO[E, A])
                 (use: A => BIO[E, B])
                 (release: (A, ExitCase[E]) => BIO[E, Unit]): BIO[E, B] = {

    BIO.Async[E, B] { (conn, cb) =>
      // Placeholder for the future finalizer
      val deferredRelease = ForwardCancelable[E]()
      conn.push(deferredRelease.cancel)
      // Race-condition check, avoiding starting the bracket if the connection
      // was cancelled already, to ensure that `cancel` really blocks if we
      // start `acquire` — n.b. `isCanceled` is visible here due to `push`
      if (!conn.isCanceled) {
        // Note `acquire` is uncancelable due to usage of `IORunLoop.start`
        // (in other words it is disconnected from our IOConnection[E])
        IORunLoop.start(acquire, new BracketStart[E, A, B](use, release, conn, deferredRelease, cb))
      } else {
        deferredRelease.complete(BIO.unit)
      }
    }
  }

  // Internals of `IO.bracketCase`.
  private final class BracketStart[E, A, B](
                                          use: A => BIO[E, B],
                                          release: (A, ExitCase[E]) => BIO[E, Unit],
                                          conn: IOConnection[E],
                                          deferredRelease: ForwardCancelable[E],
                                          cb: Callback.T[E, B])
    extends (Either[E, A] => Unit) with Runnable {

    // This runnable is a dirty optimization to avoid some memory allocations;
    // This class switches from being a Callback to a Runnable, but relies on
    // the internal IO callback protocol to be respected (called at most once)
    private[this] var result: Either[E, A] = _

    def apply(ea: Either[E, A]): Unit = {
      if (result ne null) {
        throw new IllegalStateException("callback called multiple times!")
      }
      // Introducing a light async boundary, otherwise executing the required
      // logic directly will yield a StackOverflowException
      result = ea
      ec.execute(this)
    }

    def run(): Unit = result match {
      case Right(a) =>
        val frame = new BracketReleaseFrame[E, A, B](a, release)
        val onNext = use(a).flatMap(frame)
        // Registering our cancelable token ensures that in case
        // cancellation is detected, `release` gets called
        deferredRelease.complete(frame.cancel)
        // Actual execution
        IORunLoop.startCancelable[E, B](onNext, conn, cb)

      case error @ Left(_) =>
        cb(error.asInstanceOf[Either[E, B]])
    }
  }

  /**
    * Implementation for `IO.guaranteeCase`.
    */
  def guaranteeCase[E, A](source: BIO[E, A], release: ExitCase[E] => BIO[E, Unit]): BIO[E, A] = {
    BIO.Async[E, A] { (conn, cb) =>
      // Light async boundary, otherwise this will trigger a StackOverflowException
      ec.execute(new Runnable {
        def run(): Unit = {
          val frame = new EnsureReleaseFrame[E, A](release)
          val onNext = source.flatMap(frame)
          // Registering our cancelable token ensures that in case
          // cancellation is detected, `release` gets called
          conn.push(frame.cancel)
          // Race condition check, avoiding starting `source` in case
          // the connection was already cancelled — n.b. we don't need
          // to trigger `release` otherwise, because it already happened
          if (!conn.isCanceled) {
            IORunLoop.startCancelable(onNext, conn, cb)
          }
        }
      })
    }
  }

  private final class BracketReleaseFrame[E, A, B](
                                                 a: A,
                                                 releaseFn: (A, ExitCase[E]) => BIO[E, Unit])
    extends BaseReleaseFrame[E, A, B] {

    def release(c: ExitCase[E]): CancelToken[BIO[E, ?]] =
      releaseFn(a, c)
  }

  private final class EnsureReleaseFrame[E, A](
                                             releaseFn: ExitCase[E] => BIO[E, Unit])
    extends BaseReleaseFrame[E, Unit, A] {

    def release(c: ExitCase[E]): CancelToken[BIO[E, ?]] =
      releaseFn(c)
  }

  private abstract class BaseReleaseFrame[E, A, B] extends IOFrame[E, B, BIO[E, B]] {
    // Guard used for thread-safety, to ensure the idempotency
    // of the release; otherwise `release` can be called twice
    private[this] val waitsForResult = new AtomicBoolean(true)

    def release(c: ExitCase[E]): CancelToken[BIO[E, ?]]

    private def applyRelease(e: ExitCase[E]): BIO[E, Unit] =
      BIO.unsafeSuspend {
        if (waitsForResult.compareAndSet(true, false))
          release(e)
        else
          BIO.unit
      }

    final val cancel: CancelToken[BIO[E, ?]] =
      applyRelease(ExitCase.Canceled).uncancelable

    final def recover(e: E): BIO[E, B] = {
      // Unregistering cancel token, otherwise we can have a memory leak;
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(applyRelease(ExitCase.error(e)), makeUncancelable, disableUncancelableAndPop)
        .flatMap(new ReleaseRecover(e))
    }

    final def apply(b: B): BIO[E, B] = {
      // Unregistering cancel token, otherwise we can have a memory leak
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(applyRelease(ExitCase.complete), makeUncancelable, disableUncancelableAndPop)
        .map(_ => b)
    }
  }

  private final class ReleaseRecover[E](e: E)
    extends IOFrame[E, Unit, BIO[E, Nothing]] {

    def recover(e2: E): BIO[E, Nothing] = {
      // Logging the error somewhere, because exceptions
      // should never be silent
      Logger.reportFailure(CustomException(e2))
      BIO.raiseError(e)
    }

    def apply(a: Unit): BIO[E, Nothing] =
      BIO.raiseError(e)
  }

  /**
    * Trampolined execution context used to preserve stack-safety.
    */
  private[this] val ec: ExecutionContext = immediate

  private[this] def makeUncancelable[E]: IOConnection[E] => IOConnection[E] =
    _ => IOConnection.uncancelable

  private[this] def disableUncancelableAndPop[E]: (Any, E, IOConnection[E], IOConnection[E]) => IOConnection[E] =
    (_, _, old, _) => {
      old.pop()
      old
    }
}