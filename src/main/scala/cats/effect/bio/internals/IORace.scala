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

import cats.effect.{ContextShift, Fiber}
import cats.effect.bio.BIO
import cats.effect.bio.internals.IORunLoop.CustomException
import cats.effect.internals.Logger

import scala.concurrent.Promise

private[effect] object IORace {
  /**
    * Implementation for `IO.race` - could be described with `racePair`,
    * but this way it is more efficient, as we no longer have to keep
    * internal promises.
    */
  def simple[E, A, B](cs: ContextShift[BIO[E, ?]], lh: BIO[E, A], rh: BIO[E, B]): BIO[E, Either[A, B]] = {
    // Signals successful results
    def onSuccess[T, U](
                         isActive: AtomicBoolean,
                         main: IOConnection[E],
                         other: IOConnection[E],
                         cb: Callback.T[E, Either[T, U]],
                         r: Either[T, U]): Unit = {

      if (isActive.getAndSet(false)) {
        // First interrupts the other task
        other.cancel.unsafeRunAsync { r2 =>
          main.pop()
          cb(Right(r))
          maybeReport(r2)
        }
      }
    }

    def onError[T](
                    active: AtomicBoolean,
                    cb: Callback.T[E, T],
                    main: IOConnection[E],
                    other: IOConnection[E],
                    err: E): Unit = {

      if (active.getAndSet(false)) {
        other.cancel.unsafeRunAsync { r2 =>
          main.pop()
          maybeReport(r2)
          cb(Left(err))
        }
      } else {
        Logger.reportFailure(CustomException(err))
      }
    }

    val start: Start[E, Either[A, B]] = (conn, cb) => {
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection[E]()
      // Cancelable connection for the right value
      val connR = IOConnection[E]()
      // Registers both for cancellation — gets popped right
      // before callback is invoked in onSuccess / onError
      conn.pushPair(connL, connR)

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[E, A](IOForkedStart(lh, cs), connL, {
        case Right(a) =>
          onSuccess(active, conn, connR, cb, Left(a))
        case Left(err) =>
          onError(active, cb, conn, connR, err)
      })

      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[E, B](IOForkedStart(rh, cs), connR, {
        case Right(b) =>
          onSuccess(active, conn, connL, cb, Right(b))
        case Left(err) =>
          onError(active, cb, conn, connL, err)
      })
    }

    BIO.Async(start, trampolineAfter = true)
  }

  type Pair[E, A, B] = Either[(A, Fiber[BIO[E, ?], B]), (Fiber[BIO[E, ?], A], B)]

  /**
    * Implementation for `IO.racePair`
    */
  def pair[E, A, B](cs: ContextShift[BIO[E, ?]], lh: BIO[E, A], rh: BIO[E, B]): BIO[E, Pair[E, A, B]] = {
    val start: Start[E, Pair[E, A, B]] = (conn, cb) => {
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection[E]()
      val promiseL = Promise[Either[E, A]]()
      // Cancelable connection for the right value
      val connR = IOConnection[E]()
      val promiseR = Promise[Either[E, B]]()

      // Registers both for cancellation — gets popped right
      // before callback is invoked in onSuccess / onError
      conn.pushPair(connL, connR)

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[E, A](IOForkedStart(lh, cs), connL, {
        case Right(a) =>
          if (active.getAndSet(false)) {
            conn.pop()
            cb(Right(Left((a, IOStart.fiber[E, B](promiseR, connR)))))
          } else {
            promiseL.trySuccess(Right(a))
          }
        case Left(err) =>
          if (active.getAndSet(false)) {
            connR.cancel.unsafeRunAsync { r2 =>
              conn.pop()
              maybeReport(r2)
              cb(Left(err))
            }
          } else {
            promiseL.trySuccess(Left(err))
          }
      })

      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[E, B](IOForkedStart(rh, cs), connR, {
        case Right(b) =>
          if (active.getAndSet(false)) {
            conn.pop()
            cb(Right(Right((IOStart.fiber[E, A](promiseL, connL), b))))
          } else {
            promiseR.trySuccess(Right(b))
          }

        case Left(err) =>
          if (active.getAndSet(false)) {
            connL.cancel.unsafeRunAsync { r2 =>
              conn.pop()
              maybeReport(r2)
              cb(Left(err))
            }
          } else {
            promiseR.trySuccess(Left(err))
          }
      })
    }

    BIO.Async(start, trampolineAfter = true)
  }

  private[this] def maybeReport[E](r: Either[E, _]): Unit =
    r match {
      case Left(e) => Logger.reportFailure(CustomException(e))
      case _ => ()
    }
}
