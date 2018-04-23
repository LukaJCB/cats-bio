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

/** A mapping function that is also able to handle errors,
 * being the equivalent of:
 *
 * ```
 * Either[Throwable, A] => R
 * ```
 *
 * Internal to `IO`'s implementations, used to specify
 * error handlers in their respective `Bind` internal states.
 */
private[effect] abstract class IOFrame[E, -A, +R]
  extends (A => R) { self =>

  def apply(a: A): R
  def recover(e: E): R

  final def fold(value: Either[E, A]): R =
    value match {
      case Right(a) => apply(a)
      case Left(e) => recover(e)
    }
}

private[effect] object IOFrame {
  /** Builds a [[IOFrame]] instance that maps errors, but that isn't
   * defined for successful values (a partial function)
   */
  def errorHandler[E, A](fe: E => BIO[E, A]): IOFrame[E, A, BIO[E, A]] =
    new ErrorHandler(fe)

  /** [[IOFrame]] reference that only handles errors, useful for
   * quick filtering of `onErrorHandleWith` frames.
   */
  final class ErrorHandler[E, A](fe: E => BIO[E, A])
    extends IOFrame[E, A, BIO[E, A]] {

    def recover(e: E): BIO[E, A] = fe(e)
    def apply(a: A): BIO[E, A] = BIO.pure(a)
  }
}
