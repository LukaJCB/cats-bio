package cats.effect.bio

import java.io.{ByteArrayOutputStream, PrintStream}

import cats.effect.bio.BIO._
import cats.effect.internals.NonFatal
import cats.tests.CatsSuite

import ArbitraryInstances._
import cats.effect.laws.discipline.ConcurrentEffectTests
import cats.effect.laws.util.TestContext
import cats.laws.discipline.SemigroupalTests
import org.typelevel.discipline.Laws
import cats.effect.laws.util.TestInstances._

class BIOSuite extends CatsSuite with TestInstances {

  def silenceSystemErr[A](thunk: => A): A = synchronized {
    // Silencing System.err
    val oldErr = System.err
    val outStream = new ByteArrayOutputStream()
    val fakeErr = new PrintStream(outStream)
    System.setErr(fakeErr)
    try {
      val result = thunk
      System.setErr(oldErr)
      result
    } catch {
      case NonFatal(e) =>
        System.setErr(oldErr)
        // In case of errors, print whatever was caught
        fakeErr.close()
        val out = outStream.toString("utf-8")
        if (out.nonEmpty) oldErr.println(out)
        throw e
    }
  }

  def checkAllAsync(name: String, f: TestContext => Laws#RuleSet) {
    val context = TestContext()
    val ruleSet = f(context)

    for ((id, prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        silenceSystemErr(check(prop))
      }
  }

  implicit def bioIsomorphisms[E]: SemigroupalTests.Isomorphisms[BIO[E, ?]] =
    SemigroupalTests.Isomorphisms.invariant[BIO[E, ?]]

  checkAllAsync("BIO[Throwable, ?]", implicit ec => ConcurrentEffectTests[BIO[Throwable, ?]].concurrentEffect[Int, Int, Int])

}
