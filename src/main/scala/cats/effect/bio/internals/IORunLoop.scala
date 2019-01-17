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
import cats.effect.bio.BIO.{Async, Bind, ContextSwitch, Delay, Map, Pure, RaiseError, Suspend}

import scala.util.control.NonFatal

private[effect] object IORunLoop {
  private type Current = BIO[Any, Any]
  private type Bind = Any => BIO[Any, Any]
  private type CallStack = ArrayStack[Bind]
  private type Callback = Either[Any, Any] => Unit


  case class CustomException[E](e: E) extends Exception(e.toString, null, true, false)
  /**
    * Evaluates the given `IO` reference, calling the given callback
    * with the result when completed.
    */
  def start[E, A](source: BIO[E, A], cb: Either[E, A] => Unit): Unit =
    loop(source, IOConnection.uncancelable, cb.asInstanceOf[Callback], null, null, null)

  /**
    * Evaluates the given `IO` reference, calling the given callback
    * with the result when completed.
    */
  def startCancelable[E, A](source: BIO[E, A], conn: IOConnection[E], cb: Either[E, A] => Unit): Unit =
    loop(source, conn, cb.asInstanceOf[Callback], null, null, null)

  /**
    * Loop for evaluating an `IO` value.
    *
    * The `rcbRef`, `bFirstRef` and `bRestRef`  parameters are
    * nullable values that can be supplied because the loop needs
    * to be resumed in [[RestartCallback]].
    */
  private def loop[E](
                    source: Current,
                    cancelable: IOConnection[E],
                    cb: Either[Any, Any] => Unit,
                    rcbRef: RestartCallback,
                    bFirstRef: Bind,
                    bRestRef: CallStack): Unit = {

    var currentIO: Current = source
    // Can change on a context switch
    var conn: IOConnection[E] = cancelable
    var bFirst: Bind = bFirstRef
    var bRest: CallStack = bRestRef
    var rcb: RestartCallback = rcbRef
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null
    // For auto-cancellation
    var currentIndex = 0

    do {
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
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
          findErrorHandler[Any](bFirst, bRest) match {
            case null =>
              cb(Left(ex))
              return
            case bind =>
              val fa = try bind.recover(ex) catch { case NonFatal(e) => RaiseError(e) }
              bFirst = null
              currentIO = fa.asInstanceOf[Current]
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case async @ Async(_, _) =>
          if (conn eq null) conn = IOConnection[E]()
          if (rcb eq null) rcb = new RestartCallback(conn, cb.asInstanceOf[Callback])
          rcb.start(async, bFirst, bRest)
          return

        case ContextSwitch(next, modify, restore) =>
          val old = if (conn ne null) conn else IOConnection[E]()
          conn = modify(old).asInstanceOf[IOConnection[E]]
          currentIO = next
          if (conn ne old) {
            if (rcb ne null) rcb.contextSwitch(conn)
            if (restore ne null)
              currentIO = Bind[Any, Any, Any, Any](next, new RestoreContext(old, restore.asInstanceOf[(Any, Any, IOConnection[Any], IOConnection[Any]) => IOConnection[Any]]))
          }
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            cb(Right(unboxed))
            return
          case bind =>
            val fa = try bind(unboxed) catch { case NonFatal(ex) => RaiseError(ex) }
            hasUnboxed = false
            unboxed = null
            bFirst = null
            currentIO = fa
        }
      }

      // Auto-cancellation logic
      currentIndex += 1
      if (currentIndex == maxAutoCancelableBatchSize) {
        if (conn.isCanceled) return
        currentIndex = 0
      }
    } while (true)
  }

  /**
    * Evaluates the given `IO` reference until an asynchronous
    * boundary is hit.
    */
  def step[E, A](source: BIO[E, A]): BIO[E, A] = {
    var currentIO: Current = source
    var bFirst: Bind = null
    var bRest: CallStack = null
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
          bFirst = bindNext.asInstanceOf[Bind]
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
          findErrorHandler[Any](bFirst, bRest) match {
            case null =>
              return currentIO.asInstanceOf[BIO[E, A]]
            case bind =>
              val fa = try bind.recover(ex) catch { case NonFatal(e) => RaiseError(e) }
              bFirst = null
              currentIO = fa.asInstanceOf[Current]
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case _ =>
          // Cannot inline the code of this method — as it would
          // box those vars in scala.runtime.ObjectRef!
          return suspendInAsync(currentIO, bFirst, bRest).asInstanceOf[BIO[E, A]]
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return (if (currentIO ne null) currentIO else Pure(unboxed))
              .asInstanceOf[BIO[E, A]]
          case bind =>
            currentIO = try bind(unboxed) catch { case NonFatal(ex) => RaiseError(ex) }
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
                                 bFirst: Bind,
                                 bRest: CallStack): BIO[E, A] = {

    // Hitting an async boundary means we have to stop, however
    // if we had previous `flatMap` operations then we need to resume
    // the loop with the collected stack
    if (bFirst != null || (bRest != null && !bRest.isEmpty))
      Async[E, A] { (conn, cb) =>
        loop(currentIO, conn, cb.asInstanceOf[Callback], null, bFirst, bRest)
      }
    else
      currentIO
  }

  /**
    * Pops the next bind function from the stack, but filters out
    * `IOFrame.ErrorHandler` references, because we know they won't do
    * anything — an optimization for `handleError`.
    */
  private def popNextBind(bFirst: Bind, bRest: CallStack): Bind = {
    if ((bFirst ne null) && !bFirst.isInstanceOf[IOFrame.ErrorHandler[_, _, _, _]])
      return bFirst

    if (bRest eq null) return null
    do {
      val next = bRest.pop()
      if (next eq null) {
        return null
      } else if (!next.isInstanceOf[IOFrame.ErrorHandler[_, _, _, _]]) {
        return next
      }
    } while (true)
    // $COVERAGE-OFF$
    null
    // $COVERAGE-ON$
  }

  /**
    * Finds a [[IOFrame]] capable of handling errors in our bind
    * call-stack, invoked after a `RaiseError` is observed.
    */
  private def findErrorHandler[E](bFirst: Bind, bRest: CallStack): IOFrame[E, Any, BIO[E, Any]] = {
    bFirst match {
      case ref: IOFrame[E, Any, BIO[E, Any]] @unchecked => ref
      case _ =>
        if (bRest eq null) null else {
          do {
            val ref = bRest.pop()
            if (ref eq null)
              return null
            else if (ref.isInstanceOf[IOFrame[_, _, _]])
              return ref.asInstanceOf[IOFrame[E, Any, BIO[E, Any]]]
          } while (true)
          // $COVERAGE-OFF$
          null
          // $COVERAGE-ON$
        }
    }
  }

  /**
    * A `RestartCallback` gets created only once, per [[startCancelable]]
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
  private final class RestartCallback(connInit: IOConnection[Any], cb: Callback)
    extends Callback with Runnable {

    import cats.effect.internals.TrampolineEC.{immediate => ec}

    // can change on a ContextSwitch
    private[this] var conn: IOConnection[Any] = connInit
    private[this] var canCall = false
    private[this] var trampolineAfter = false
    private[this] var bFirst: Bind = _
    private[this] var bRest: CallStack = _

    // Used in combination with trampolineAfter = true
    private[this] var value: Either[Any, Any] = _

    def contextSwitch(conn: IOConnection[Any]): Unit = {
      this.conn = conn
    }

    def start(task: BIO.Async[Any, Any], bFirst: Bind, bRest: CallStack): Unit = {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
      this.trampolineAfter = task.trampolineAfter
      // Go, go, go
      task.k(conn, this)
    }

    private[this] def signal(either: Either[Any, Any]): Unit = {
      // Allow GC to collect
      val bFirst = this.bFirst
      val bRest = this.bRest
      this.bFirst = null
      this.bRest = null

      // Auto-cancelable logic: in case the connection was cancelled,
      // we interrupt the bind continuation
      if (!conn.isCanceled) either match {
        case Right(success) =>
          loop(Pure(success), conn, cb, this, bFirst, bRest)
        case Left(e) =>
          loop(RaiseError(e), conn, cb, this, bFirst, bRest)
      }
    }

    override def run(): Unit = {
      // N.B. this has to be set to null *before* the signal
      // otherwise a race condition can happen ;-)
      val v = value
      value = null
      signal(v)
    }

    def apply(either: Either[Any, Any]): Unit =
      if (canCall) {
        canCall = false
        if (trampolineAfter) {
          this.value = either
          ec.execute(this)
        } else {
          signal(either)
        }
      }
  }

  private final class RestoreContext(
                                      old: IOConnection[Any],
                                      restore: (Any, Any, IOConnection[Any], IOConnection[Any]) => IOConnection[Any])
    extends IOFrame[Any, Any, BIO[Any, Any]] {

    def apply(a: Any): BIO[Any, Any] =
      ContextSwitch(Pure(a), current => restore(a, null, old, current), null)
    def recover(e: Any): BIO[Any, Any] =
      ContextSwitch(RaiseError(e), current => restore(null, e, old, current), null)
  }

  /**
    * Number of iterations before the connection is checked for its
    * cancelled status, to interrupt synchronous flatMap loops.
    */
  private[this] val maxAutoCancelableBatchSize = 512
}