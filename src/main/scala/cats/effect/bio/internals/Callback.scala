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

import cats.effect.bio.internals.IORunLoop.CustomException
import cats.effect.internals.Logger

import scala.concurrent.Promise
import scala.util.{Failure, Left, Success, Try}
import cats.effect.internals.TrampolineEC._
/**
  * Internal API — utilities for working with `IO.async` callbacks.
  */
private[effect] object Callback {
  type T[-E, -A] = Either[E, A] => Unit


  /**
    * Builds a callback reference that throws any received
    * error immediately.
    */
  def report[E, A]: T[E, A] =
    reportRef.asInstanceOf[T[E, A]]

  private def reportRef[E] = (r: Either[E, _]) =>
    r match {
      case Left(e) => Logger.reportFailure(CustomException(e))
      case _ => ()
    }

  /** Reusable `Right(())` reference. */
  val rightUnit = Right(())
  /** Reusable `Success(())` reference. */
  val successUnit = Success(())

  /** Reusable no-op, side-effectful `Function1` reference. */
  val dummy1: Any => Unit = _ => ()

  /** Builds a callback with async execution. */
  def async[E, A](cb: T[E, A]): T[E, A] =
    async(null, cb)

  /**
    * Builds a callback with async execution.
    *
    * Also pops the `Connection` just before triggering
    * the underlying callback.
    */
  def async[E, A](conn: IOConnection[E], cb: T[E, A]): T[E, A] =
    value => immediate.execute(
      new Runnable {
        def run(): Unit = {
          if (conn ne null) conn.pop()
          cb(value)
        }
      })

  /**
    * Callback wrapper used in `IO.async` that:
    *
    *  - guarantees (thread safe) idempotency
    *  - triggers light (trampolined) async boundary for stack safety
    *  - pops the given `Connection` (only if != null)
    *  - logs extraneous errors after callback was already called once
    */
  def asyncIdempotent[E, A](conn: IOConnection[E], cb: T[E, A]): T[E, A] =
    new AsyncIdempotentCallback[E, A](conn, cb)

  /**
    * Builds a callback from a standard Scala `Promise`.
    */
  def promise[E, A](p: Promise[A]): T[E, A] = {
    case Right(a) => p.success(a)
    case Left(e) => p.failure(new CustomException(e))
  }

  /** Helpers async callbacks. */
  implicit final class Extensions[-E, -A](val self: T[E, A]) extends AnyVal {
    /**
      * Executes the source callback with a light (trampolined) async
      * boundary, meant to protect against stack overflows.
      */
    def async(value: Either[E, A]): Unit =
      immediate.execute(new Runnable {
        def run(): Unit = self(value)
      })
  }

  private final class AsyncIdempotentCallback[-E, -A](
                                                   conn: IOConnection[E],
                                                   cb: Either[E, A] => Unit)
    extends (Either[E, A] => Unit) with Runnable {

    private[this] val canCall = new AtomicBoolean(true)
    private[this] var value: Either[E, A] = _
    def run(): Unit = cb(value)

    def apply(value: Either[E, A]): Unit = {
      if (canCall.getAndSet(false)) {
        if (conn ne null) conn.pop()
        this.value = value
        immediate.execute(this)
      } else value match {
        case Right(_) => ()
        case Left(e) =>
          Logger.reportFailure(new CustomException(e))
      }
    }
  }
}
