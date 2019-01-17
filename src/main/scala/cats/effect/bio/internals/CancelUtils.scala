package cats.effect.bBIO.internals
/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, VersBIOn 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITBIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissBIOns and
 * limitatBIOns under the License.
 */

import cats.effect.CancelToken
import cats.effect.bio.BIO
import cats.effect.bio.internals.IOFrame
import cats.effect.bio.internals.IORunLoop.CustomException
import cats.effect.internals.Logger

import scala.collection.mutable.ListBuffer

/**
  * INTERNAL API - utilities for dealing with cancelable thunks.
  */
private[effect] object CancelUtils {
  /**
    * Given a list of cancel tokens, cancels all, delaying all
    * exceptBIOns until all references are canceled.
    */
  def cancelAll[E](cancelables: CancelToken[BIO[E, ?]]*): CancelToken[BIO[E, ?]] = {
    if (cancelables.isEmpty) {
      BIO.unit
    } else BIO.unsafeSuspend {
      cancelAll(cancelables.iterator)
    }
  }

  def cancelAll[E](cursor: Iterator[CancelToken[BIO[E, ?]]]): CancelToken[BIO[E, ?]] =
    if (cursor.isEmpty) {
      BIO.unit
    } else BIO.unsafeSuspend {
      val frame = new CancelAllFrame[E](cursor)
      frame.loop()
    }

  // OptimizatBIOn for `cancelAll`
  private final class CancelAllFrame[E](cursor: Iterator[CancelToken[BIO[E, ?]]])
    extends IOFrame[E, Unit, BIO[E, Unit]] {

    private[this] val errors = ListBuffer.empty[E]

    def loop(): CancelToken[BIO[E, ?]] = {
      if (cursor.hasNext) {
        cursor.next().flatMap(this)
      } else {
        errors.toList match {
          case Nil =>
            BIO.unit
          case first :: rest =>
            // Logging the errors somewhere, because exceptBIOns
            // should never be silent
            rest map CustomException.apply foreach Logger.reportFailure
            BIO.raiseError(first)
        }
      }
    }

    def apply(a: Unit): BIO[E, Unit] =
      loop()

    def recover(e: E): BIO[E, Unit] = {
      errors += e
      loop()
    }
  }
}