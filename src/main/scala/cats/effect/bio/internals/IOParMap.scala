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

import java.util.concurrent.atomic.AtomicReference

import cats.effect.ContextShift
import cats.effect.bio.BIO
import cats.effect.bio.internals.Callback.Extensions
import cats.effect.bio.internals.IORunLoop.CustomException
import cats.effect.internals.{Logger, TrampolineEC}

private[effect] object IOParMap {
  /**
    * Implementation for `parMap2`.
    */
  def apply[E <: AnyRef, A, B, C](cs: ContextShift[BIO[E, ?]], fa: BIO[E, A], fb: BIO[E, B])(f: (A, B) => C): BIO[E, C] = {
    BIO.Async[E, C](
      new IOForkedStart[E, C] {
        def apply(conn: IOConnection[E], cb: Callback.T[E, C]) = {
          // For preventing stack-overflow errors; using a
          // trampolined execution context, so no thread forks
          TrampolineEC.immediate.execute(
            new ParMapRunnable(cs, fa, fb, f, conn, cb))
        }
      },
      trampolineAfter = true)
  }

  private final class ParMapRunnable[E <: AnyRef, A, B, C](
                                               cs: ContextShift[BIO[E, ?]],
                                               fa: BIO[E, A],
                                               fb: BIO[E, B],
                                               f: (A, B) => C,
                                               conn: IOConnection[E],
                                               cb: Callback.T[E, C])
    extends Runnable {

    /**
      * State synchronized by an atomic reference. Possible values:
      *
      *  - null: none of the 2 tasks have finished yet
      *  - Left(a): the left task is waiting for the right one
      *  - Right(b): the right task is waiting for the left one
      *  - Throwable: an error was triggered
      *
      * Note - `getAndSet` is used for modifying this atomic, so the
      * final state (both are finished) is implicit.
      */
    private[this] val state = new AtomicReference[AnyRef]()

    /** Callback for the left task. */
    private def callbackA(connB: IOConnection[E]): Callback.T[E, A] = {
      case Left(e) => sendError(connB, e)
      case Right(a) =>
        // Using Java 8 platform intrinsics
        state.getAndSet(Left(a)) match {
          case null => () // wait for B
          case Right(b) =>
            complete(a, b.asInstanceOf[B])
          case _: Throwable => ()
          case left =>
            // $COVERAGE-OFF$
            throw new IllegalStateException(s"parMap: $left")
          // $COVERAGE-ON$
        }
    }

    /** Callback for the right task. */
    def callbackB(connA: IOConnection[E]): Callback.T[E, B] = {
      case Left(e) => sendError(connA, e)
      case Right(b) =>
        // Using Java 8 platform intrinsics
        state.getAndSet(Right(b)) match {
          case null => () // wait for A
          case Left(a) =>
            complete(a.asInstanceOf[A], b)
          case _: Throwable => ()
          case right =>
            // $COVERAGE-OFF$
            throw new IllegalStateException(s"parMap: $right")
          // $COVERAGE-ON$
        }
    }

    /** Called when both results are ready. */
    def complete(a: A, b: B): Unit = {
      conn.pop()
      cb(Right(f(a, b)))
    }

    /** Called when an error is generated. */
    private def sendError(other: IOConnection[E], e: E): Unit = {
      state.getAndSet(e) match {
        case _: Throwable =>
          Logger.reportFailure(CustomException(e))
        case null | Left(_) | Right(_) =>
          // Cancels the other before signaling the error
          other.cancel.unsafeRunAsync { r =>
            conn.pop()
            cb.async(Left(r match {
              case Left(e2) =>
                // Logging the error somewhere, because exceptions
                // should never be silent
                Logger.reportFailure(CustomException(e2))
                e
              case _ => e
            }))
          }
      }
    }

    def run(): Unit = {
      val connA = IOConnection[E]()
      val connB = IOConnection[E]()

      // Composite cancelable that cancels both.
      // NOTE: conn.pop() happens when cb gets called!
      conn.pushPair(connA, connB)

      IORunLoop.startCancelable(IOForkedStart(fa, cs), connA, callbackA(connB))
      IORunLoop.startCancelable(IOForkedStart(fb, cs), connB, callbackB(connA))
    }
  }
}