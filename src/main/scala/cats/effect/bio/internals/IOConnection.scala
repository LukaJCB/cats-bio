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
import cats.effect.bBIO.internals.CancelUtils
import cats.effect.bio.BIO

import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance

/**
  * INTERNAL API — Represents a composite of functions
  * (meant for cancellation) that are stacked.
  *
  * Implementation notes:
  *
  *  - `cancel()` is idempotent
  *  - all methods are thread-safe / atomic
  *
  * Used in the implementation of `cats.effect.IO`. Inspired by the
  * implementation of `StackedCancelable` from the Monix library.
  */
private[effect] sealed abstract class IOConnection[+E] {
  /**
    * Cancels the unit of work represented by this reference.
    *
    * Guaranteed idempotency - calling it multiple times should have the
    * same side-effect as calling it only once. Implementations
    * of this method should also be thread-safe.
    */
  def cancel[E1 >: E]: CancelToken[BIO[E1, ?]]

  /**
    * @return true in case this cancelable hasn't been canceled,
    *         or false otherwise.
    */
  def isCanceled: Boolean

  /**
    * Removes a cancelable reference from the stack in FIFO order.
    *
    * @return the cancelable reference that was removed.
    */
  def pop[E1 >: E](): CancelToken[BIO[E1, ?]]

  /**
    * Tries to reset an `IOConnection[E]`, from a cancelled state,
    * back to a pristine state, but only if possible.
    *
    * Returns `true` on success, or `false` if there was a race
    * condition (i.e. the connection wasn't cancelled) or if
    * the type of the connection cannot be reactivated.
    */
  def tryReactivate(): Boolean

  /**
    * Pushes a cancelable reference on the stack, to be
    * popped or canceled later in FIFO order.
    */
  def push[E1 >: E](token: CancelToken[BIO[E1, ?]]): Unit

  /**
    * Pushes a pair of `IOConnection[E]` on the stack, which on
    * cancellation will get trampolined.
    *
    * This is useful in `IO.race` for example, because combining
    * a whole collection of `IO` tasks, two by two, can lead to
    * building a cancelable that's stack unsafe.
    */
  def pushPair[E1 >: E](lh: IOConnection[E1], rh: IOConnection[E1]): Unit
}

private[effect] object IOConnection {
  /** Builder for [[IOConnection[E]]]. */
  def apply[E](): IOConnection[E] =
    new Impl

  /**
    * Reusable [[IOConnection[E]]] reference that cannot
    * be canceled.
    */
  def uncancelable[E]: IOConnection[E] =
    new Uncancelable

  private final class Uncancelable[E] extends IOConnection[E] {
    def cancel[E1 >: E] = BIO.unit
    def isCanceled: Boolean = false
    def push[E1 >: E](token: CancelToken[BIO[E1, ?]]): Unit = ()
    def pop[E1 >: E](): CancelToken[BIO[E1, ?]] = BIO.unit
    def tryReactivate(): Boolean = true
    def pushPair[E1 >: E](lh: IOConnection[E1], rh: IOConnection[E1]): Unit = ()
  }

  private final class Impl[E] extends IOConnection[E] {
    private[this] val state = new AtomicReference(List.empty[CancelToken[BIO[E, ?]]])

    def cancel[E1 >: E] = BIO.unsafeSuspend {
      state.getAndSet(null) match {
        case null | Nil =>
          BIO.unit[E1]
        case list =>
          CancelUtils.cancelAll(list.iterator)
      }
    }

    def isCanceled: Boolean =
      state.get eq null

    @tailrec def push[E1 >: E](cancelable: CancelToken[BIO[E1, ?]]): Unit =
      state.get() match {
        case null =>
          cancelable.unsafeRunAsyncAndForget()
        case list =>
          val update = cancelable :: list
          if (!state.compareAndSet(list, update.asInstanceOf[List[CancelToken[BIO[E, ?]]]])) push(cancelable)
      }

    def pushPair[E1 >: E](lh: IOConnection[E1], rh: IOConnection[E1]): Unit =
      push(CancelUtils.cancelAll(lh.cancel, rh.cancel))

    @tailrec def pop[E1 >: E](): CancelToken[BIO[E1, ?]] =
      state.get() match {
        case null | Nil => BIO.unit
        case current @ (x :: xs) =>
          if (!state.compareAndSet(current, xs)) pop()
          else x
      }

    def tryReactivate(): Boolean =
      state.compareAndSet(null, Nil)
  }
}
