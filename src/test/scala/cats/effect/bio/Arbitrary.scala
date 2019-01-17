package cats.effect.bio

import cats.effect.bio.internals.IORunLoop
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}

import scala.util.Either

object ArbitraryInstances {
  implicit def catsEffectLawsArbitraryForBIO[E: Arbitrary, A: Arbitrary: Cogen]: Arbitrary[BIO[E, A]] =
    Arbitrary(Gen.delay(genBIO[E, A]))

  implicit def catsEffectLawsArbitraryForBIOParallel[E: Arbitrary, A: Arbitrary: Cogen]: Arbitrary[BIO.Par[E, A]] =
    Arbitrary(catsEffectLawsArbitraryForBIO[E, A].arbitrary.map(BIO.Par.apply))

  def genBIO[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] = {
    Gen.frequency(
      5 -> genPure[E, A],
      5 -> genApply[E, A],
      1 -> genFail[E, A],
      5 -> genAsync[E, A],
      5 -> genNestedAsync[E, A],
      5 -> getMapOne[E, A],
      5 -> getMapTwo[E, A],
      10 -> genFlatMap[E, A])
  }

  def genSyncBIO[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] = {
    Gen.frequency(
      5 -> genPure[E, A],
      5 -> genApply[E, A],
      1 -> genFail[E, A],
      5 -> genBindSuspend[E, A])
  }

  def genPure[E: Arbitrary, A: Arbitrary]: Gen[BIO[E, A]] =
    getArbitrary[A].map(BIO.pure)

  def genApply[E: Arbitrary, A: Arbitrary]: Gen[BIO[E, A]] =
    getArbitrary[A].map(BIO.unsafeDelay(_))

  def genFail[E: Arbitrary, A]: Gen[BIO[E, A]] =
    getArbitrary[E].map(BIO.raiseError)

  def genAsync[E: Arbitrary, A: Arbitrary]: Gen[BIO[E, A]] =
    getArbitrary[(Either[E, A] => Unit) => Unit].map(BIO.async)

  def genNestedAsync[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] =
    getArbitrary[(Either[E, BIO[E, A]] => Unit) => Unit]
      .map(k => BIO.async(k).flatMap(x => x))

  def genBindSuspend[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] =
    getArbitrary[A].map(BIO.unsafeDelay(_).flatMap(BIO.pure))

  def genFlatMap[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] =
    for {
      ioa <- getArbitrary[BIO[E, A]]
      f <- getArbitrary[A => BIO[E, A]]
    } yield ioa.flatMap(f)

  def getMapOne[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] =
    for {
      ioa <- getArbitrary[BIO[E, A]]
      f <- getArbitrary[A => A]
    } yield ioa.map(f)

  def getMapTwo[E: Arbitrary, A: Arbitrary: Cogen]: Gen[BIO[E, A]] =
    for {
      ioa <- getArbitrary[BIO[E, A]]
      f1 <- getArbitrary[A => A]
      f2 <- getArbitrary[A => A]
    } yield ioa.map(f1).map(f2)

  implicit def catsEffectLawsCogenForBIO[E, A](implicit cga: Cogen[A], cge: Cogen[E]): Cogen[BIO[E, A]] =
    Cogen { (seed, io) =>
      IORunLoop.step(io) match {
        case BIO.Pure(a) => cga.perturb(seed, a)
        case BIO.RaiseError(e) => cge.perturb(seed, e)
        case _ => seed
      }
    }
}
