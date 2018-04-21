package bio

import cats.MonadError
import cats.data.EitherT
import cats.effect.IO
import cats.effect.bio.BIO
import cats.syntax.all._
import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

import scalaz.ioeffect.{RTS, IO => ZIO}

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class FlatMapBench {


  @Benchmark
  def ioEitherT: Int = (ioCountdownEitherT(100000).value *> IO(1)).unsafeRunSync()


  @Benchmark
  def bio: Int =
    ioCountdownBIO(100000).attempt.flatMap(_ => BIO.pure(1)).unsafeRunSync()

  @Benchmark
  def zio: Int =
    ZBIO.unsafePerformIO(ioCountdownZIO(100000).attempt.flatMap(_ => ZIO.point[Custom, Int](1)))




  class Custom

  type EIO[E, A] = EitherT[IO, E, A]

  def ioCountdownEitherT(n: Int): EIO[Custom, Int] = n match {
    case 0 => MonadError[EIO[Custom, ?], Custom].raiseError(new Custom)
    case n => EitherT.pure[IO, Custom](n - 1).flatMap(ioCountdownEitherT)
  }

  def ioCountdownBIO(n: Int): BIO[Custom, Int] = n match {
    case 0 => BIO.raiseError(new Custom)
    case n => BIO.pure(n - 1).flatMap(ioCountdownBIO)
  }

  def ioCountdownZIO(n: Int): ZIO[Custom, Int] = n match {
    case 0 => ZIO.fail(new Custom)
    case n => ZIO.point[Custom, Int](n - 1).flatMap(n => ioCountdownZIO(n))
  }

}

object ZBIO extends RTS
