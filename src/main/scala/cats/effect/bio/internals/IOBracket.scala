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

private[effect] object IOBracket {

  /**
    * Implementation for `IO.bracket`.
    */
  def apply[E, A, B](acquire: BIO[E, A])
    (use: A => BIO[E, B])
    (release: (A, ExitCase[E]) => BIO[E, Unit]): BIO[E, B] = {

    acquire.flatMap { a =>
      BIO.Bind(
        use(a).onCancelRaiseError(null.asInstanceOf[E]),
        new ReleaseFrame[E, A, B](a, release))
    }
  }

  private final class ReleaseFrame[E, A, B](a: A,
    release: (A, ExitCase[E]) => BIO[E, Unit])
    extends IOFrame[E, B, BIO[E, B]] {

    def recover(e: E): BIO[E, B] = {
      if (e != null)
        release(a, ExitCase.error(e))
          .flatMap(new ReleaseRecover(e))
      else
        release(a, ExitCase.canceled)
          .flatMap(Function.const(BIO.never))
    }

    def apply(b: B): BIO[E, B] =
      release(a, ExitCase.complete)
        .map(_ => b)
  }

  private final class ReleaseRecover[E](e: E)
    extends IOFrame[E, Unit, BIO[E, Nothing]] {

    def recover(e2: E): BIO[E, Nothing] = {
      // Logging the error somewhere, because exceptions
      // should never be silent
      Logger.reportFailure(new IORunLoop.CustomException(e2))
      BIO.raiseError(e)
    }

    def apply(a: Unit): BIO[E, Nothing] =
      BIO.raiseError(e)
  }
}
