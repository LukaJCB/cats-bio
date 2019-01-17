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

import java.util.concurrent.ScheduledExecutorService

import cats.effect.{Clock, Timer}
import cats.effect.bio.BIO

import scala.concurrent.ExecutionContext

/**
  * Internal API — gets mixed-in the `IO` companion object.
  */
private[effect] trait IOTimerRef {
  /**
    * Returns a [[cats.effect.Timer]] instance for [[cats.effect.bio.BIO]], built from a
    * Scala `ExecutionContext`.
    *
    * N.B. this is the JVM-specific version. On top of JavaScript
    * the implementation needs no `ExecutionContext`.
    *
    * @param ec is the execution context used for actual execution
    *        tasks (e.g. bind continuations)
    */
  def timer[E](ec: ExecutionContext, clock: Clock[BIO[E, ?]]): Timer[BIO[E, ?]] =
    IOTimer(ec, clock)

  /**
    * Returns a [[Timer]] instance for [[BIO]], built from a
    * Scala `ExecutionContext` and a Java `ScheduledExecutorService`.
    *
    * N.B. this is the JVM-specific version. On top of JavaScript
    * the implementation needs no `ExecutionContext`.
    *
    * @param ec is the execution context used for actual execution
    *        tasks (e.g. bind continuations)
    * @param sc is the `ScheduledExecutorService` used for scheduling
    *        ticks with a delay
    */
  def timer[E](ec: ExecutionContext, sc: ScheduledExecutorService, clock: Clock[BIO[E, ?]]): Timer[BIO[E, ?]] =
    IOTimer(ec, sc, clock)
}
