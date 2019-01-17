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


import cats.effect.ContextShift
import cats.effect.bio.BIO

import scala.annotation.tailrec

/**
  * A marker for detecting asynchronous tasks that will fork execution.
  *
  * We prefer doing this because extraneous asynchronous boundaries
  * are more expensive than doing this check.
  *
  * N.B. the rule for start functions being marked via `ForkedStart`
  * is that the injected callback MUST BE called after a full
  * asynchronous boundary.
  */
private[effect] abstract class IOForkedStart[E, +A] extends Start[E, A]

private[effect] object IOForkedStart {
  /**
    * Given a task, returns one that has a guaranteed
    * logical fork on execution.
    *
    * The equivalent of `IO.shift *> task` but it tries
    * to eliminate extraneous async boundaries. In case the
    * task is known to fork already, then this introduces
    * a light async boundary instead.
    */
  def apply[E, A](task: BIO[E, A], cs: ContextShift[BIO[E, ?]]): BIO[E, A] =
    if (detect(task)) task
    else cs.shift.flatMap(_ => task)

  /**
    * Returns `true` if the given task is known to fork execution,
    * or `false` otherwise.
    */
  @tailrec def detect(task: BIO[_, _], limit: Int = 8): Boolean = {
    if (limit > 0) task match {
      case BIO.Async(k, _) => k.isInstanceOf[IOForkedStart[_, _]]
      case BIO.Bind(other, _) => detect(other, limit - 1)
      case BIO.Map(other, _, _) => detect(other, limit - 1)
      case BIO.ContextSwitch(other, _, _) => detect(other, limit - 1)
      case _ => false
    } else {
      false
    }
  }
}
