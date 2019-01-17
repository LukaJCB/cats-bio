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



import cats.effect.bio.BIO

import scala.concurrent.ExecutionContext

private[effect] object IOShift {
  /** Implementation for `IO.shift`. */
  def apply[E](ec: ExecutionContext): BIO[E, Unit] =
    BIO.Async(new IOForkedStart[E, Unit] {
      def apply(conn: IOConnection[E], cb: Callback.T[E, Unit]): Unit =
        ec.execute(new Tick(cb))
    })

  def shiftOn[E, A](cs: ExecutionContext, targetEc: ExecutionContext, io: BIO[E, A]): BIO[E, A] =
    IOBracket[E, Unit, A](IOShift(cs))(_ => io)((_, _) => IOShift(targetEc))

  private[internals] final class Tick[E](cb: Either[E, Unit] => Unit)
    extends Runnable {
    def run() = cb(Callback.rightUnit)
  }
}