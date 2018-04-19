package bio

import cats.MonadError
import cats.data.EitherT
import cats.effect.IO
import cats.effect.bio.BIO
import cats.implicits._
import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class FlatMapBench {


  @Benchmark
  def ioEitherT: Int = (ioCountdownEitherT(1000).value *> IO(1)).unsafeRunSync()


  @Benchmark
  def bio: Int =
    MonadError[BIO[Custom, ?], Custom].handleErrorWith(ioCountdownBIO(1000))(_ => BIO.pure(1)).unsafeRunSync()



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

}
