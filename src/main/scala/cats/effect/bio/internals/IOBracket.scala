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

package cats.effect.bio.internals

import cats.effect.bio.BIO
import cats.effect.ExitCase
import scala.concurrent.CancellationException

private[effect] object IOBracket {

  private final val cancelException = new CancellationException("cancel in bracket")

  /**
    * Implementation for `IO.bracket`.
    */
  def apply[A, B](acquire: BIO[Throwable, A])
    (use: A => BIO[Throwable, B])
    (release: (A, ExitCase[Throwable]) => BIO[Throwable, Unit]): BIO[Throwable, B] = {

    acquire.flatMap { a =>
      BIO.Bind(
        use(a).onCancelRaiseError(cancelException),
        new ReleaseFrame[A, B](a, release))
    }
  }

  private final class ReleaseFrame[A, B](a: A,
    release: (A, ExitCase[Throwable]) => BIO[Throwable, Unit])
    extends IOFrame[Throwable, B, BIO[Throwable, B]] {

    def recover(e: Throwable): BIO[Throwable, B] = {
      if (e ne cancelException)
        release(a, ExitCase.error(e))
          .flatMap(new ReleaseRecover(e))
      else
        release(a, ExitCase.canceled)
          .flatMap(Function.const(BIO.never))
    }

    def apply(b: B): BIO[Throwable, B] =
      release(a, ExitCase.complete)
        .map(_ => b)
  }

  private final class ReleaseRecover(e: Throwable)
    extends IOFrame[Throwable, Unit, BIO[Throwable, Nothing]] {

    def recover(e2: Throwable): BIO[Throwable, Nothing] = {
      // Logging the error somewhere, because exceptions
      // should never be silent
      Logger.reportFailure(e2)
      BIO.raiseError(e)
    }

    def apply(a: Unit): BIO[Throwable, Nothing] =
      BIO.raiseError(e)
  }
}
