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

import cats.effect.CancelToken
import cats.effect.bio.BIO
import cats.effect.internals.Logger
import cats.effect.internals.TrampolineEC.immediate

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
  * A placeholder for a [[cats.effect.CancelToken]] that will be set at a later time,
  * the equivalent of a `Deferred[IO, CancelToken]`.
  *
  * Used in the implementation of `bracket`, see [[IOBracket]].
  */
private[effect] final class ForwardCancelable[E] private () {
  import ForwardCancelable._

  private[this] val state = new AtomicReference[State[E]](init)

  val cancel: CancelToken[BIO[E, ?]] = {
    @tailrec def loop(conn: IOConnection[E], cb: Callback.T[E, Unit]): Unit =
      state.get() match {
        case current @ Empty(list) =>
          if (!state.compareAndSet(current, Empty(cb :: list.asInstanceOf[List[Callback.T[E, Unit]]])))
            loop(conn, cb)

        case Active(token) =>
          state.lazySet(finished) // GC purposes
          context.execute(new Runnable {
            def run() =
              IORunLoop.startCancelable[E, Unit](token.asInstanceOf[BIO[E, Unit]], conn, cb)
          })
      }

    BIO.Async(loop)
  }

  def complete(value: CancelToken[BIO[E, ?]]): Unit =
    state.get() match {
      case current @ Active(_) =>
        value.unsafeRunAsyncAndForget()
        throw new IllegalStateException(current.toString)

      case current @ Empty(stack) =>
        if (current eq init) {
          // If `init`, then `cancel` was not triggered yet
          if (!state.compareAndSet(current, Active(value)))
            complete(value)
        } else {
          if (!state.compareAndSet(current, finished))
            complete(value)
          else
            execute(value, stack.asInstanceOf[List[Callback.T[E, Unit]]])
        }
    }
}

private[effect] object ForwardCancelable {
  /**
    * Builds reference.
    */
  def apply[E](): ForwardCancelable[E] =
    new ForwardCancelable[E]

  /**
    * Models the internal state of [[ForwardCancelable]]:
    *
    *  - on start, the state is [[Empty]] of `Nil`, aka [[init]]
    *  - on `cancel`, if no token was assigned yet, then the state will
    *    remain [[Empty]] with a non-nil `List[Callback]`
    *  - if a `CancelToken` is provided without `cancel` happening,
    *    then the state transitions to [[Active]] mode
    *  - on `cancel`, if the state was [[Active]], or if it was [[Empty]],
    *    regardless, the state transitions to `Active(IO.unit)`, aka [[finished]]
    */
  private sealed abstract class State[+E]

  private final case class Empty[E](stack: List[Callback.T[E, Unit]])
    extends State
  private final case class Active[E](token: CancelToken[BIO[E, ?]])
    extends State

  private val init: State[Nothing] = Empty(Nil)
  private val finished: State[Nothing] = Active(BIO.unit)
  private val context: ExecutionContext = immediate

  private def execute[E](token: CancelToken[BIO[E, ?]], stack: List[Callback.T[E, Unit]]): Unit =
    context.execute(new Runnable {
      def run(): Unit =
        token.unsafeRunAsync { r =>
          for (cb <- stack)
            try { cb(r) } catch {
              // $COVERAGE-OFF$
              case NonFatal(e) => Logger.reportFailure(e)
              // $COVERAGE-ON$
            }
        }
    })
}
