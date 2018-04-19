package cats
package effect
package bio

import cats.effect.internals.NonFatal

object BIO extends BIOInstances {
  private[bio] type Base
  private[bio] trait Tag extends Any
  type Type[+E, +A] <: Base with Tag

  private[bio] def create[E, A](io: IO[A]): BIO[E, A] =
    io.asInstanceOf[BIO[E, A]]

  private[bio] def unwrap[E, A](io: BIO[E, A]): IO[A] =
    io.asInstanceOf[IO[A]]

  private[bio] def subst[F[_], E, A](value: F[IO[A]]): F[Type[E, A]] =
    value.asInstanceOf[F[Type[E, A]]]

  private[bio] case class CustomException[E](e: E) extends Exception(e.toString, null, true, false)

  def fromIO[A](io: IO[A]): BIO[Throwable, A] =
    io.asInstanceOf[BIO[Throwable, A]]

  def toIO[A](bio: BIO[Throwable, A]): IO[A] =
    bio.asInstanceOf[IO[A]]

  def pure[A](a: A): BIO[Nothing, A] =
    create(IO.pure(a))

  def raiseError[E](e: E): BIO[E, Nothing] =
    create(IO.raiseError(CustomException(e)))

  def apply[E, A](thunk: => A)(f: Throwable => E): BIO[E, A] =
    create(IO(try thunk catch { case NonFatal(t) => throw CustomException(f(t)) }))

  def unsafeSyncNoCatch[E, A](thunk: => A): BIO[Nothing, A] =
    create(IO(thunk))

  implicit class BIOOps[E, A](val value: BIO[E, A]) extends AnyVal {
    def flatMap[B](f: A => BIO[E, B]): BIO[E, B] =
      BIO.create(BIO.unwrap(value).flatMap(a => BIO.unwrap(f(a))))

    def unsafeRunSync(): A =
      BIO.unwrap(value).unsafeRunSync()

  }

}

sealed abstract class BIOInstances extends BIOInstances0 {
  implicit val catsEffectSyncForBIO: Sync[BIO[Throwable, ?]] = new Sync[BIO[Throwable, ?]] {
    def flatMap[A, B](fa: BIO[Throwable, A])(f: A => BIO[Throwable, B]): BIO[Throwable, B] =
      BIO.create(BIO.unwrap(fa).flatMap(a => BIO.unwrap(f(a))))

    def raiseError[A](e: Throwable): BIO[Throwable, A] = BIO.raiseError(e)

    def pure[A](x: A): BIO[Throwable, A] = BIO.pure(x)

    def handleErrorWith[A](fa: BIO[Throwable, A])(f: Throwable => BIO[Throwable, A]): BIO[Throwable, A] =
      BIO.create(Sync[IO].handleErrorWith(BIO.unwrap(fa))((a => BIO.unwrap(f(a)))))

    def suspend[A](thunk: => BIO[Throwable, A]): BIO[Throwable, A] =
      BIO.create(IO.suspend(BIO.unwrap(thunk)))

    def tailRecM[A, B](a: A)(f: A => BIO[Throwable, Either[A, B]]): BIO[Throwable, B] =
      BIO.create(Monad[IO].tailRecM(a)(a => BIO.unwrap(f(a))))

    def bracketCase[A, B](acquire: BIO[Throwable, A])
                         (use: A => BIO[Throwable, B])
                         (release: (A, ExitCase[Throwable]) => BIO[Throwable, Unit]): BIO[Throwable, B] =
      BIO.create(Sync[IO].bracketCase(BIO.unwrap(acquire))
        (a => BIO.unwrap(use(a)))((a, e) => BIO.unwrap(release(a, e))))
  }
}

sealed abstract class BIOInstances0 {
  implicit def catsMonadErrorForBIO[E]: MonadError[BIO[E, ?], E] = new MonadError[BIO[E, ?], E] {
    def flatMap[A, B](fa: BIO[E, A])(f: A => BIO[E, B]): BIO[E, B] =
      BIO.create(BIO.unwrap(fa).flatMap(a => BIO.unwrap(f(a))))

    def raiseError[A](e: E): BIO[E, A] = BIO.raiseError(e)

    def pure[A](x: A): BIO[E, A] = BIO.pure(x)

    def handleErrorWith[A](fa: BIO[E, A])(f: E => BIO[E, A]): BIO[E, A] =
      BIO.create(Sync[IO].handleErrorWith(BIO.unwrap(fa)) {
        case BIO.CustomException(t) => BIO.unwrap(f(t.asInstanceOf[E]))
      })

    def tailRecM[A, B](a: A)(f: A => BIO[E, Either[A, B]]): BIO[E, B] =
      BIO.create(Monad[IO].tailRecM(a)(a => BIO.unwrap(f(a))))
  }
}
