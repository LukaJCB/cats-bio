/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.bio

import cats.kernel.Eq
import scala.concurrent.Future
import scala.util.{Failure, Success}
import cats.effect.laws.util.TestContext
import cats.effect.bio.internals.IORunLoop

/**
 * Defines instances for `Future` and for `IO`, meant for law testing
 * by means of [[TestContext]].
 *
 * The [[TestContext]] interpreter is used here for simulating
 * asynchronous execution.
 */
trait TestInstances {
  /**
   * Defines equality for `IO` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqBIO[E, A](implicit A: Eq[A], ec: TestContext): Eq[BIO[E, A]] =
    new Eq[BIO[E, A]] {
      def eqv(x: BIO[E, A], y: BIO[E, A]): Boolean =
        eqFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }

  /**
   * Defines equality for `IO.Par` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqIOPar[E, A](implicit A: Eq[A], ec: TestContext): Eq[BIO.Par[E, A]] =
    new Eq[BIO.Par[E, A]] {
      import BIO.Par.unwrap
      def eqv(x: BIO.Par[E, A], y: BIO.Par[E, A]): Boolean =
        eqFuture[A].eqv(unwrap(x).unsafeToFuture(), unwrap(y).unsafeToFuture())
    }

  /**
   * Defines equality for `Future` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqFuture[A](implicit A: Eq[A], ec: TestContext): Eq[Future[A]] =
    new Eq[Future[A]] {
      def eqv(x: Future[A], y: Future[A]): Boolean = {
        // Executes the whole pending queue of runnables
        ec.tick()

        x.value match {
          case None =>
            y.value.isEmpty
          case Some(Success(a)) =>
            y.value match {
              case Some(Success(b)) => A.eqv(a, b)
              case _ => false
            }
          case Some(Failure(ex)) =>
            y.value match {
              case Some(Failure(ey)) => eqThrowable.eqv(ex, ey)
              case _ => false
            }
        }
      }
    }

  implicit val eqThrowable: Eq[Throwable] =
    new Eq[Throwable] {
      def eqv(x: Throwable, y: Throwable): Boolean = {
        if (x.isInstanceOf[IORunLoop.CustomException[_]])
          x == y
        else
          (x ne null) == (y ne null)
      }
    }
}

object TestInstances extends TestInstances
