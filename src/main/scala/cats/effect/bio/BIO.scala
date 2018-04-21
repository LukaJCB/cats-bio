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

package cats.effect
package bio

import cats._

import cats.effect.bio.internals.{IOFrame, IOPlatform, IORunLoop, NonFatal}
import cats.effect.bio.internals.IOPlatform.fusionMaxStackDepth
import cats.effect.IO
import cats.syntax.bifunctor._
import cats.instances.either._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Left, Right}

/**
  * A pure abstraction representing the intention to perform a
  * side effect, where the result of that side effect may be obtained
  * synchronously (via return) or asynchronously (via callback).
  *
  * Effects contained within this abstraction are not evaluated until
  * the "end of the world", which is to say, when one of the "unsafe"
  * methods are used.  Effectful results are not memoized, meaning that
  * memory overhead is minimal (and no leaks), and also that a single
  * effect may be run multiple times in a referentially-transparent
  * manner.  For example:
  *
  * {{{
  * val ioa = IO { println("hey!") }
  *
  * val program = for {
  *   _ <- ioa
  *   _ <- ioa
  * } yield ()
  *
  * program.unsafeRunSync()
  * }}}
  *
  * The above will print "hey!" twice, as the effect will be re-run
  * each time it is sequenced in the monadic chain.
  *
  * `IO` is trampolined for all ''synchronous'' joins.  This means that
  * you can safely call `flatMap` in a recursive function of arbitrary
  * depth, without fear of blowing the stack.  However, `IO` cannot
  * guarantee stack-safety in the presence of arbitrarily nested
  * asynchronous suspensions.  This is quite simply because it is
  * ''impossible'' (on the JVM) to guarantee stack-safety in that case.
  * For example:
  *
  * {{{
  * def lie[A]: IO[A] = IO.async(cb => cb(Right(lie))).flatMap(a => a)
  * }}}
  *
  * This should blow the stack when evaluated. Also note that there is
  * no way to encode this using `tailRecM` in such a way that it does
  * ''not'' blow the stack.  Thus, the `tailRecM` on `Monad[IO]` is not
  * guaranteed to produce an `IO` which is stack-safe when run, but
  * will rather make every attempt to do so barring pathological
  * structure.
  *
  * `IO` makes no attempt to control finalization or guaranteed
  * resource-safety in the presence of concurrent preemption, simply
  * because `IO` does not care about concurrent preemption at all!
  * `IO` actions are not interruptible and should be considered
  * broadly-speaking atomic, at least when used purely.
  */
sealed abstract class BIO[E, +A] {
  import BIO._

  /**
    * Functor map on `IO`. Given a mapping functions, it transforms the
    * value produced by the source, while keeping the `IO` context.
    *
    * Any exceptions thrown within the function will be caught and
    * sequenced into the `IO`, because due to the nature of
    * asynchronous processes, without catching and handling exceptions,
    * failures would be completely silent and `IO` references would
    * never terminate on evaluation.
    */
  final def map[B](f: A => B): BIO[E, B] =
    this match {
      case BiMap(source, g, index) =>
        // Allowed to do fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `IOPlatform` for details on this maximum.
        if (index != fusionMaxStackDepth) BiMap(source, g.andThen(_.map(f)), index + 1)
        else BiMap.right(this, f, 0)
      case _ =>
        BiMap.right(this, f, 0)
    }

  final def leftMap[X](f: E => X): BIO[X, A] =
    this match {
      case BiMap(source, g, index) =>
        // Allowed to do fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `IOPlatform` for details on this maximum.
        if (index != fusionMaxStackDepth) BiMap(source, g.andThen(_.left.map(f)), index + 1)
        else BiMap.left(this, f, 0)
      case _ =>
        BiMap.left(this, f, 0)
    }

  final def bimap[X, B](f: E => X, g: A => B): BIO[X, B] =
    this match {
      case BiMap(source, h, index) =>
        // Allowed to do fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `IOPlatform` for details on this maximum.
        if (index != fusionMaxStackDepth) BiMap(source, h.andThen(_.bimap(f, g)), index + 1)
        else BiMap.bi(this, f, g, 0)
      case _ =>
        BiMap.bi(this, f, g, 0)
    }

  /**
    * Monadic bind on `IO`, used for sequentially composing two `IO`
    * actions, where the value produced by the first `IO` is passed as
    * input to a function producing the second `IO` action.
    *
    * Due to this operation's signature, `flatMap` forces a data
    * dependency between two `IO` actions, thus ensuring sequencing
    * (e.g. one action to be executed before another one).
    *
    * Any exceptions thrown within the function will be caught and
    * sequenced into the `IO`, because due to the nature of
    * asynchronous processes, without catching and handling exceptions,
    * failures would be completely silent and `IO` references would
    * never terminate on evaluation.
    */
  final def flatMap[B](f: A => BIO[E, B]): BIO[E, B] =
    Bind(this, f)

  /**
    * Materializes any sequenced exceptions into value space, where
    * they may be handled.
    *
    * This is analogous to the `catch` clause in `try`/`catch`, being
    * the inverse of `IO.raiseError`. Thus:
    *
    * {{{
    * IO.raiseError(ex).attempt.unsafeRunAsync === Left(ex)
    * }}}
    *
    * @see [[BIO.raiseError]]
    */
  def attempt: BIO[E, Either[E, A]] =
    Bind(this, AttemptIO.asInstanceOf[A => BIO[E, Either[E, A]]])

  /**
    * Produces an `IO` reference that is guaranteed to be safe to run
    * synchronously (i.e. [[unsafeRunSync]]), being the safe analogue
    * to [[unsafeRunAsync]].
    *
    * This operation is isomorphic to [[unsafeRunAsync]]. What it does
    * is to let you describe asynchronous execution with a function
    * that stores off the results of the original `IO` as a
    * side effect, thus ''avoiding'' the usage of impure callbacks or
    * eager evaluation.
    */
  final def runAsync(cb: Either[E, A] => BIO[E, Unit]): BIO[Throwable, Unit] = BIO {
    unsafeRunAsync(cb.andThen(_.unsafeRunAsync(_ => ())))
  }

  /**
    * Produces the result by running the encapsulated effects as impure
    * side effects.
    *
    * If any component of the computation is asynchronous, the current
    * thread will block awaiting the results of the async computation.
    * On JavaScript, an exception will be thrown instead to avoid
    * generating a deadlock. By default, this blocking will be
    * unbounded.  To limit the thread block to some fixed time, use
    * `unsafeRunTimed` instead.
    *
    * Any exceptions raised within the effect will be re-thrown during
    * evaluation.
    *
    * As the name says, this is an UNSAFE function as it is impure and
    * performs side effects, not to mention blocking, throwing
    * exceptions, and doing other things that are at odds with
    * reasonable software.  You should ideally only call this function
    * *once*, at the very end of your program.
    */
  final def unsafeRunSync(): A = unsafeRunTimed(Duration.Inf).get

  /**
    * Passes the result of the encapsulated effects to the given
    * callback by running them as impure side effects.
    *
    * Any exceptions raised within the effect will be passed to the
    * callback in the `Either`.  The callback will be invoked at most
    * *once*.  Note that it is very possible to construct an IO which
    * never returns while still never blocking a thread, and attempting
    * to evaluate that IO with this method will result in a situation
    * where the callback is *never* invoked.
    *
    * As the name says, this is an UNSAFE function as it is impure and
    * performs side effects.  You should ideally only call this
    * function ''once'', at the very end of your program.
    */
  final def unsafeRunAsync(cb: Either[E, A] => Unit): Unit =
    IORunLoop.start(this, cb)

  /**
    * Similar to `unsafeRunSync`, except with a bounded blocking
    * duration when awaiting asynchronous results.
    *
    * Please note that the `limit` parameter does not limit the time of
    * the total computation, but rather acts as an upper bound on any
    * *individual* asynchronous block.  Thus, if you pass a limit of `5
    * seconds` to an `IO` consisting solely of synchronous actions, the
    * evaluation may take considerably longer than 5 seconds!
    * Furthermore, if you pass a limit of `5 seconds` to an `IO`
    * consisting of several asynchronous actions joined together,
    * evaluation may take up to `n * 5 seconds`, where `n` is the
    * number of joined async actions.
    *
    * As soon as an async blocking limit is hit, evaluation
    * ''immediately'' aborts and `None` is returned.
    *
    * Please note that this function is intended for ''testing''; it
    * should never appear in your mainline production code!  It is
    * absolutely not an appropriate function to use if you want to
    * implement timeouts, or anything similar. If you need that sort
    * of functionality, you should be using a streaming library (like
    * fs2 or Monix).
    *
    * @see [[unsafeRunSync]]
    */
  final def unsafeRunTimed(limit: Duration): Option[A] =
    IORunLoop.step(this) match {
      case Pure(a) => Some(a)
      case RaiseError(e) => throw new IORunLoop.CustomException(e)
      case self @ Async(_) =>
        IOPlatform.unsafeResync(self, limit)
      case _ =>
        // $COVERAGE-OFF$
        throw new AssertionError("unreachable")
      // $COVERAGE-ON$
    }

  /**
    * Evaluates the effect and produces the result in a `Future`.
    *
    * This is similar to `unsafeRunAsync` in that it evaluates the `IO`
    * as a side effect in a non-blocking fashion, but uses a `Future`
    * rather than an explicit callback.  This function should really
    * only be used if interoperating with legacy code which uses Scala
    * futures.
    *
    * @see [[BIO.fromFuture]]
    */
  final def unsafeToFuture(): Future[A] = {
    val p = Promise[A]
    unsafeRunAsync(_.fold(e => p.failure(new IORunLoop.CustomException(e)), p.success))
    p.future
  }

  override def toString = this match {
    case Pure(a) => s"IO($a)"
    case RaiseError(e) => s"IO(throw $e)"
    case _ => "IO$" + System.identityHashCode(this)
  }
}

private[effect] trait IOLowPriorityInstances {

  private[effect] class IOSemigroup[E, A: Semigroup] extends Semigroup[BIO[E, A]] {
    def combine(ioa1: BIO[E, A], ioa2: BIO[E, A]) =
      ioa1.flatMap(a1 => ioa2.map(a2 => Semigroup[A].combine(a1, a2)))
  }

  implicit def ioSemigroup[E, A: Semigroup]: Semigroup[BIO[E, A]] = new IOSemigroup[E, A]

  implicit def ioMonadError[E]: MonadError[BIO[E, ?], E] = new MonadError[BIO[E, ?], E] {
    override def pure[A](a: A): BIO[E, A] =
      BIO.pure(a)
    override def flatMap[A, B](ioa: BIO[E, A])(f: A => BIO[E, B]): BIO[E, B] =
      ioa.flatMap(f)
    override def map[A, B](fa: BIO[E, A])(f: A => B): BIO[E, B] =
      fa.map(f)
    override def unit: BIO[E, Unit] =
      BIO.unit
    override def attempt[A](ioa: BIO[E, A]): BIO[E, Either[E, A]] =
      ioa.attempt
    override def handleErrorWith[A](ioa: BIO[E, A])(f: E => BIO[E, A]): BIO[E, A] =
      BIO.Bind(ioa, IOFrame.errorHandler[E, A](f))
    override def raiseError[A](e: E): BIO[E, A] =
      BIO.raiseError(e)

    override def tailRecM[A, B](a: A)(f: A => BIO[E, Either[A, B]]): BIO[E, B] =
      f(a) flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
  }
}

private[effect] trait IOInstances extends IOLowPriorityInstances {

  implicit val ioEffect: Effect[BIO[Throwable, ?]] = new Effect[BIO[Throwable, ?]] {
    override def pure[A](a: A): BIO[Throwable, A] =
      BIO.pure(a)
    override def flatMap[A, B](ioa: BIO[Throwable, A])(f: A => BIO[Throwable, B]): BIO[Throwable, B] =
      ioa.flatMap(f)
    override def map[A, B](fa: BIO[Throwable, A])(f: A => B): BIO[Throwable, B] =
      fa.map(f)
    override def delay[A](thunk: => A): BIO[Throwable, A] =
      BIO(thunk)
    override def unit: BIO[Throwable, Unit] =
      BIO.unit
    override def attempt[A](ioa: BIO[Throwable, A]): BIO[Throwable, Either[Throwable, A]] =
      ioa.attempt
    override def handleErrorWith[A](ioa: BIO[Throwable, A])(f: Throwable => BIO[Throwable, A]): BIO[Throwable, A] =
      BIO.Bind(ioa, IOFrame.errorHandler[Throwable, A](f))
    override def raiseError[A](e: Throwable): BIO[Throwable, A] =
      BIO.raiseError(e)
    override def suspend[A](thunk: => BIO[Throwable, A]): BIO[Throwable, A] =
      BIO.suspend(thunk)
    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): BIO[Throwable, A] =
      BIO.async(k)
    override def runAsync[A](ioa: BIO[Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      IO.async(c => ioa.runAsync(eta => liftIO(cb(eta))).unsafeRunAsync(c))

    override def liftIO[A](ioa: IO[A]): BIO[Throwable, A] =
      async(cb => ioa.unsafeRunAsync(cb))

    def bracketCase[A, B](acquire: BIO[Throwable, A])
                         (use: A => BIO[Throwable, B])
                         (release: (A, ExitCase[Throwable]) => BIO[Throwable, Unit]): BIO[Throwable, B] =
      flatMap(acquire) { a =>
        rethrow(flatTap(attempt(use(a))) {
          case Right(_) => release(a, ExitCase.complete)
          case Left(t) => release(a, ExitCase.error(t))
        })
      }

    // this will use stack proportional to the maximum number of joined async suspensions
    override def tailRecM[A, B](a: A)(f: A => BIO[Throwable, Either[A, B]]): BIO[Throwable, B] =
      f(a) flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
  }

  implicit def ioMonoid[E, A: Monoid]: Monoid[BIO[E, A]] = new IOSemigroup[E, A] with Monoid[BIO[E, A]] {
    def empty = BIO.pure(Monoid[A].empty)
  }
}

object BIO extends IOInstances {

  /**
    * Suspends a synchronous side effect in `IO`.
    *
    * Any exceptions thrown by the effect will be caught and sequenced
    * into the `IO`.
    */
  def apply[A](body: => A): BIO[Throwable, A] = Delay(body _, identity)

  /**
    * Suspends a synchronous side effect which produces an `IO` in `IO`.
    *
    * This is useful for trampolining (i.e. when the side effect is
    * conceptually the allocation of a stack frame).  Any exceptions
    * thrown by the side effect will be caught and sequenced into the
    * `IO`.
    */
  def suspend[A](thunk: => BIO[Throwable, A]): BIO[Throwable, A] =
    Suspend(thunk _, identity)

  /**
    * Suspends a pure value in `IO`.
    *
    * This should ''only'' be used if the value in question has
    * "already" been computed!  In other words, something like
    * `IO.pure(readLine)` is most definitely not the right thing to do!
    * However, `IO.pure(42)` is correct and will be more efficient
    * (when evaluated) than `IO(42)`, due to avoiding the allocation of
    * extra thunks.
    */
  def pure[E, A](a: A): BIO[E, A] = Pure(a)

  /** Alias for `IO.pure(())`. */
  def unit[E]: BIO[E, Unit] = pure(())

  /**
    * Lifts an `Eval` into `IO`.
    *
    * This function will preserve the evaluation semantics of any
    * actions that are lifted into the pure `IO`.  Eager `Eval`
    * instances will be converted into thunk-less `IO` (i.e. eager
    * `IO`), while lazy eval and memoized will be executed as such.
    */
  def eval[A](fa: Eval[A]): BIO[Throwable, A] = fa match {
    case Now(a) => pure(a)
    case notNow => apply(notNow.value)
  }

  /**
    * Suspends an asynchronous side effect in `IO`.
    *
    * The given function will be invoked during evaluation of the `IO`
    * to "schedule" the asynchronous callback, where the callback is
    * the parameter passed to that function.  Only the ''first''
    * invocation of the callback will be effective!  All subsequent
    * invocations will be silently dropped.
    *
    * As a quick example, you can use this function to perform a
    * parallel computation given an `ExecutorService`:
    *
    * {{{
    * def fork[A](body: => A)(implicit E: ExecutorService): IO[A] = {
    *   IO async { cb =>
    *     E.execute(new Runnable {
    *       def run() =
    *         try cb(Right(body)) catch { case NonFatal(t) => cb(Left(t)) }
    *     })
    *   }
    * }
    * }}}
    *
    * The `fork` function will do exactly what it sounds like: take a
    * thunk and an `ExecutorService` and run that thunk on the thread
    * pool.  Or rather, it will produce an `IO` which will do those
    * things when run; it does *not* schedule the thunk until the
    * resulting `IO` is run!  Note that there is no thread blocking in
    * this implementation; the resulting `IO` encapsulates the callback
    * in a pure and monadic fashion without using threads.
    *
    * This function can be thought of as a safer, lexically-constrained
    * version of `Promise`, where `IO` is like a safer, lazy version of
    * `Future`.
    */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): BIO[Throwable, A] = {
    Async { cb =>
      val cb2 = IOPlatform.onceOnly(cb)
      try k(cb2) catch { case NonFatal(t) => cb2(Left(t)) }
    }
  }

  /**
    * Constructs an `IO` which sequences the specified exception.
    *
    * If this `IO` is run using `unsafeRunSync` or `unsafeRunTimed`,
    * the exception will be thrown.  This exception can be "caught" (or
    * rather, materialized into value-space) using the `attempt`
    * method.
    *
    * @see [[BIO#attempt]]
    */
  def raiseError[E, A](e: E): BIO[E, A] = RaiseError(e)

  /**
    * Constructs an `IO` which evaluates the given `Future` and
    * produces the result (or failure).
    *
    * Because `Future` eagerly evaluates, as well as because it
    * memoizes, this function takes its parameter as an `IO`,
    * which could be lazily evaluated.  If this laziness is
    * appropriately threaded back to the definition site of the
    * `Future`, it ensures that the computation is fully managed by
    * `IO` and thus referentially transparent.
    *
    * Example:
    *
    * {{{
    *   // Lazy evaluation, equivalent with by-name params
    *   IO.fromFuture(IO(f))
    *
    *   // Eager evaluation, for pure futures
    *   IO.fromFuture(IO.pure(f))
    * }}}
    *
    * Note that the ''continuation'' of the computation resulting from
    * a `Future` will run on the future's thread pool.  There is no
    * thread shifting here; the `ExecutionContext` is solely for the
    * benefit of the `Future`.
    *
    * Roughly speaking, the following identities hold:
    *
    * {{{
    * IO.fromFuture(IO(f)).unsafeToFuture() === f // true-ish (except for memoization)
    * IO.fromFuture(IO(ioa.unsafeToFuture())) === ioa // true
    * }}}
    *
    * @see [[BIO#unsafeToFuture]]
    */
  def fromFuture[A](iof: BIO[Throwable, Future[A]])(implicit ec: ExecutionContext): BIO[Throwable, A] = iof flatMap { f =>
    BIO async { cb =>
      import scala.util.{Success, Failure}

      f onComplete {
        case Failure(e) => cb(Left(e))
        case Success(a) => cb(Right(a))
      }
    }
  }

  /**
    * Lifts an Either[Throwable, A] into the IO[A] context raising the throwable
    * if it exists.
    */
  def fromEither[E, A](e: Either[E, A]): BIO[E, A] = e match {
    case Right(a) => pure(a)
    case Left(e) => raiseError(e)
  }

  /**
    * Shifts the bind continuation of the `IO` onto the specified thread
    * pool.
    *
    * Asynchronous actions cannot be shifted, since they are scheduled
    * rather than run. Also, no effort is made to re-shift synchronous
    * actions which *follow* asynchronous actions within a bind chain;
    * those actions will remain on the continuation thread inherited
    * from their preceding async action.  The only computations which
    * are shifted are those which are defined as synchronous actions and
    * are contiguous in the bind chain ''following'' the `shift`.
    *
    * As an example:
    *
    * {{{
    * for {
    *   _ <- IO.shift(BlockingIO)
    *   bytes <- readFileUsingJavaIO(file)
    *   _ <- IO.shift(DefaultPool)
    *
    *   secure = encrypt(bytes, KeyManager)
    *   _ <- sendResponse(Protocol.v1, secure)
    *
    *   _ <- IO { println("it worked!") }
    * } yield ()
    * }}}
    *
    * In the above, `readFileUsingJavaIO` will be shifted to the pool
    * represented by `BlockingIO`, so long as it is defined using `apply`
    * or `suspend` (which, judging by the name, it probably is).  Once
    * its computation is complete, the rest of the `for`-comprehension is
    * shifted ''again'', this time onto the `DefaultPool`.  This pool is
    * used to compute the encrypted version of the bytes, which are then
    * passed to `sendResponse`.  If we assume that `sendResponse` is
    * defined using `async` (perhaps backed by an NIO socket channel),
    * then we don't actually know on which pool the final `IO` action (the
    * `println`) will be run.  If we wanted to ensure that the `println`
    * runs on `DefaultPool`, we would insert another `shift` following
    * `sendResponse`.
    *
    * Another somewhat less common application of `shift` is to reset the
    * thread stack and yield control back to the underlying pool.  For
    * example:
    *
    * {{{
    * lazy val repeat: IO[Unit] = for {
    *   _ <- doStuff
    *   _ <- IO.shift
    *   _ <- repeat
    * } yield ()
    * }}}
    *
    * In this example, `repeat` is a very long running `IO` (infinite, in
    * fact!) which will just hog the underlying thread resource for as long
    * as it continues running.  This can be a bit of a problem, and so we
    * inject the `IO.shift` which yields control back to the underlying
    * thread pool, giving it a chance to reschedule things and provide
    * better fairness.  This shifting also "bounces" the thread stack, popping
    * all the way back to the thread pool and effectively trampolining the
    * remainder of the computation.  This sort of manual trampolining is
    * unnecessary if `doStuff` is defined using `suspend` or `apply`, but if
    * it was defined using `async` and does ''not'' involve any real
    * concurrency, the call to `shift` will be necessary to avoid a
    * `StackOverflowError`.
    *
    * Thus, this function has four important use cases: shifting blocking
    * actions off of the main compute pool, defensively re-shifting
    * asynchronous continuations back to the main compute pool, yielding
    * control to some underlying pool for fairness reasons, and preventing
    * an overflow of the call stack in the case of improperly constructed
    * `async` actions.
    */
  def shift(implicit ec: ExecutionContext): BIO[Throwable, Unit] = {
    BIO async { (cb: Either[Throwable, Unit] => Unit) =>
      ec.execute(new Runnable {
        def run() = cb(Right(()))
      })
    }
  }

  private[effect] final case class Pure[E, +A](a: A)
    extends BIO[E, A]
  private[effect] final case class Delay[E, +A](thunk: () => A, f: Throwable => E)
    extends BIO[E, A]
  private[effect] final case class RaiseError[E](e: E)
    extends BIO[E, Nothing]
  private[effect] final case class Suspend[E, +A](thunk: () => BIO[E, A], f: Throwable => E)
    extends BIO[E, A]
  private[effect] final case class Async[E, +A](k: (Either[E, A] => Unit) => Unit)
    extends BIO[E, A]
  private[effect] final case class Bind[X, E, +A](source: BIO[X, E], f: E => BIO[X, A])
    extends BIO[X, A]

  /** State for representing `map` ops that itself is a function in
    * order to avoid extraneous memory allocations when building the
    * internal call-stack.
    */
  private[effect] final case class BiMap[Y ,X, E, +A](source: BIO[Y, E], f: Either[Y, E] => Either[X, A], index: Int)
    extends BIO[X, A] with (Either[Y, E] => BIO[X, A]) {

    override def apply(value: Either[Y, E]): BIO[X, A] = f(value) match {
      case Right(a) => BIO.pure(a)
      case Left(x) => BIO.raiseError(x)
    }
  }

  private[effect] object BiMap {
    def right[E, A, B](source: BIO[E, A], f: A => B, index: Int): BiMap[E, E, A, B] =
      BiMap(source, _.map(f), index)

    def left[X, Y, A](source: BIO[X, A], f: X => Y, index: Int): BiMap[X, Y, A, A] =
      BiMap(source, _.left.map(f), index)

    def bi[A, B, C, D](source: BIO[A, C], f: A => B, g: C => D, index: Int): BiMap[A, B, C, D] =
      BiMap(source, _.bimap(f, g), index)
  }

  /** Internal reference, used as an optimization for [[BIO.attempt]]
    * in order to avoid extraneous memory allocations.
    */
  private object AttemptIO extends IOFrame[Any, Any, BIO[Any, Either[Any, Any]]] {
    override def apply(a: Any) =
      Pure(Right(a))
    override def recover(e: Any) =
      Pure(Left(e))
  }

  implicit class IONothingSyntax[A](val io: BIO[Nothing, A]) extends AnyRef {
    def cast[E]: BIO[E, A] = io.asInstanceOf[BIO[E, A]]
  }
}
