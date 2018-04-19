package cats.effect

package object bio {
  type BIO[E, A] = BIO.Type[E, A]
}
