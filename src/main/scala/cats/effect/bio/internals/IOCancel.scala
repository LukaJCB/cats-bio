package cats.effect.bio.internals

import cats.effect.bio.BIO
import cats.effect.bio.BIO.ContextSwitch

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

private[effect] object IOCancel {
  /** Implementation for `IO.uncancelable`. */
  def uncancelable[E, A](fa: BIO[E, A]): BIO[E, A] =
    ContextSwitch(fa, makeUncancelable[E], disableUncancelable)

  /** Internal reusable reference. */
  private[this] def makeUncancelable[E]: IOConnection[E] => IOConnection[E] =
    _ => IOConnection.uncancelable
  private[this] def disableUncancelable[E]: (Any, E, IOConnection[E], IOConnection[E]) => IOConnection[E] =
    (_, _, old, _) => old
}
