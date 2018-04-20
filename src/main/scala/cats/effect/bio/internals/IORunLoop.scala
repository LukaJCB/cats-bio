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
import cats.effect.bio.BIO._

import scala.collection.mutable.ArrayStack

private[effect] object IORunLoop {
  private type Current[E] = BIO[E, Any]
  private type Bind[E] = Any => BIO[E, Any]
  private type CallStack[E] = ArrayStack[Bind[E]]
  private type Callback[E] = Either[E, Any] => Unit

  class CustomException[E](e: E) extends Exception(e.toString, null, true, false)

  /** Evaluates the given `IO` reference, calling the given callback
    * with the result when completed.
    */
  def start[E, A](source: BIO[E, A], cb: Either[E, A] => Unit): Unit =
    loop(source, cb.asInstanceOf[Callback[E]], null, null, null)

  /** Loop for evaluating an `IO` value.
    *
    * The `rcbRef`, `bFirstRef` and `bRestRef`  parameters are
    * nullable values that can be supplied because the loop needs
    * to be resumed in [[RestartCallback]].
    */
  private def loop[E](
    source: Current[E],
    cb: Either[E, Any] => Unit,
    rcbRef: RestartCallback[E],
    bFirstRef: Bind[E],
    bRestRef: CallStack[E]): Unit = {

    var currentIO: Current[E] = source
    var bFirst: Bind[E] = bFirstRef
    var bRest: CallStack[E] = bRestRef
    var rcb: RestartCallback[E] = rcbRef
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    do {
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind[E]]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk, f) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch { case NonFatal(e) =>
            currentIO = RaiseError(f(e))
          }

        case Suspend(thunk, f) =>
          currentIO = try thunk() catch { case NonFatal(ex) => RaiseError(f(ex)) }

        case RaiseError(ex) =>
          findErrorHandler(bFirst, bRest) match {
            case null =>
              cb(Left(ex))
              return
            case bind =>
              val fa = try bind.recover(ex) catch { case NonFatal(e) => RaiseError(ex) }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ BiMap(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind[E]]
          currentIO = fa.asInstanceOf[Current[E]]

        case Async(register) =>
          if (rcb eq null) rcb = RestartCallback(cb.asInstanceOf[Callback[E]])
          rcb.prepare(bFirst, bRest)
          register(rcb)
          return
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            cb(Right(unboxed))
            return
          case bind =>
            val fa = bind(unboxed)
            hasUnboxed = false
            unboxed = null
            bFirst = null
            currentIO = fa
        }
      }
    } while (true)
  }

  /** Evaluates the given `IO` reference until an asynchronous
    * boundary is hit.
    */
  def step[E, A](source: BIO[E, A]): BIO[E, A] = {
    var currentIO: Current[E] = source
    var bFirst: Bind[E] = null
    var bRest: CallStack[E] = null
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    do {
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind[E]]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk, f) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch { case NonFatal(e) =>
            currentIO = RaiseError(f(e))
          }

        case Suspend(thunk, f) =>
          currentIO = try thunk() catch { case NonFatal(ex) => RaiseError(f(ex)) }

        case RaiseError(ex) =>
          findErrorHandler(bFirst, bRest) match {
            case null =>
              return currentIO.asInstanceOf[BIO[E, A]]
            case bind =>
              val fa = try bind.recover(ex) catch { case NonFatal(e) => RaiseError(ex) }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ BiMap(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind[E]]
          currentIO = fa.asInstanceOf[Current[E]]

        case Async(register) =>
          // Cannot inline the code of this method — as it would
          // box those vars in scala.runtime.ObjectRef!
          return suspendInAsync(currentIO.asInstanceOf[BIO[E, A]], bFirst, bRest, register)
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return (if (currentIO ne null) currentIO else Pure(unboxed))
              .asInstanceOf[BIO[E, A]]
          case bind =>
            currentIO = bind(unboxed)
            hasUnboxed = false
            unboxed = null
            bFirst = null
        }
      }
    } while (true)
    // $COVERAGE-OFF$
    null // Unreachable code
    // $COVERAGE-ON$
  }

  private def suspendInAsync[E, A](
                                    currentIO: BIO[E, A],
                                    bFirst: Bind[E],
                                    bRest: CallStack[E],
                                    register: (Either[E, Any] => Unit) => Unit): BIO[E, A] = {

    // Hitting an async boundary means we have to stop, however
    // if we had previous `flatMap` operations then we need to resume
    // the loop with the collected stack
    if (bFirst != null || (bRest != null && bRest.nonEmpty))
      Async[E, A] { cb =>
        val rcb = RestartCallback(cb.asInstanceOf[Callback[E]])
        rcb.prepare(bFirst, bRest)
        register(rcb)
      }
    else
      currentIO
  }

  /** Pops the next bind function from the stack, but filters out
    * `IOFrame.ErrorHandler` references, because we know they won't do
    * anything — an optimization for `handleError`.
    */
  private def popNextBind[E](bFirst: Bind[E], bRest: CallStack[E]): Bind[E] = {
    if ((bFirst ne null) && !bFirst.isInstanceOf[IOFrame.ErrorHandler[E, _]])
      bFirst
    else if (bRest != null) {
      var cursor: Bind[E] = null
      while (cursor == null && bRest.nonEmpty) {
        val ref = bRest.pop()
        if (!ref.isInstanceOf[IOFrame.ErrorHandler[E, _]]) cursor = ref
      }
      cursor
    } else {
      null
    }
  }

  /** Finds a [[IOFrame]] capable of handling errors in our bind
    * call-stack, invoked after a `RaiseError` is observed.
    */
  private def findErrorHandler[E](bFirst: Bind[E], bRest: CallStack[E]): IOFrame[E, Any, BIO[E, Any]] = {
    var result: IOFrame[E, Any, BIO[E, Any]] = null
    var cursor = bFirst
    var continue = true

    while (continue) {
      if (cursor != null && cursor.isInstanceOf[IOFrame[_, _, _]]) {
        result = cursor.asInstanceOf[IOFrame[E, Any, BIO[E, Any]]]
        continue = false
      } else {
        cursor = if (bRest != null && bRest.nonEmpty) bRest.pop() else null
        continue = cursor != null
      }
    }
    result
  }

  /** A `RestartCallback` gets created only once, per [[start]]
    * (`unsafeRunAsync`) invocation, once an `Async` state is hit,
    * its job being to resume the loop after the boundary, but with
    * the bind call-stack restored
    *
    * This is a trick the implementation is using to avoid creating
    * extraneous callback references on asynchronous boundaries, in
    * order to reduce memory pressure.
    *
    * It's an ugly, mutable implementation.
    * For internal use only, here be dragons!
    */
  private final class RestartCallback[E] private (cb: Callback[E])
    extends Callback[E] {

    private[this] var canCall = false
    private[this] var bFirst: Bind[E] = _
    private[this] var bRest: CallStack[E] = _

    def prepare(bFirst: Bind[E], bRest: CallStack[E]): Unit = {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
    }

    def apply(either: Either[E, Any]): Unit =
      if (canCall) {
        canCall = false
        either match {
          case Right(value) =>
            loop(Pure(value), cb, this, bFirst, bRest)
          case Left(e) =>
            loop(RaiseError(e), cb, this, bFirst, bRest)
        }
      }
  }

  private object RestartCallback {
    /** Builder that avoids double wrapping. */
    def apply[E](cb: Callback[E]): RestartCallback[E] =
      cb match {
        case ref: RestartCallback[E] => ref
        case _ => new RestartCallback(cb)
      }
  }
}
