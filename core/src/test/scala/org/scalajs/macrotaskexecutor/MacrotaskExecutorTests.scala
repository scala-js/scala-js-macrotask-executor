/*
 * Copyright 2021 Scala.js (https://www.scala-js.org/)
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

package org.scalajs.macrotaskexecutor

import org.junit.Test

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.scalajs.js
import scala.util.Try

class MacrotaskExecutorTests {
  import MacrotaskExecutor.Implicits._

  private final val Undefined = "undefined"

  @Test
  def `sequence a series of 10,000 recursive executions without clamping` = {
    def loop(n: Int): Future[Int] =
      if (n <= 0)
        Future(0)
      else
        Future.successful(()).flatMap(_ => loop(n - 1)).map(_ + 1)

    val start = System.currentTimeMillis()
    val MinimumClamp =
      10000 * 2 * 4 // HTML5 specifies a 4ms clamp (https://developer.mozilla.org/en-US/docs/Web/API/WindowTimers.setTimeout#Minimum.2F_maximum_delay_and_timeout_nesting)

    loop(10000) flatMap { res =>
      Future {
        val end = System.currentTimeMillis()

        Try {
          assert(res == 10000)
          assert(
            (end - start).toDouble / MinimumClamp < 0.25
          ) // we should beat the clamping by at least 4x even on slow environments
        }
      }
    }
  }

  // this test fails to terminate with a Promise-based executor
  @Test
  def `preserve fairness with setTimeout` = {
    var cancel = false

    def loop(): Future[Try[Unit]] =
      Future(cancel) flatMap { canceled =>
        if (canceled)
          Future.successful(Try(()))
        else
          loop()
      }

    js.timers.setTimeout(100.millis) {
      cancel = true
    }

    loop()
  }

  @Test
  def `report failures as uncaught errors rather than unhandled rejections` =
    if (!canObserveUncaughtErrors)
      Future.successful(Try(()))
    else
      observingFailures {
        MacrotaskExecutor.execute(new Runnable {
          def run(): Unit = throw new RuntimeException("expected")
        })
      } map { case (uncaught, rejections) =>
        Try {
          assert(rejections == 0)
          assert(uncaught == 1)
        }
      }

  /**
   * Skip for Selenium in main thread
   */
  private def canObserveUncaughtErrors: Boolean = {
    val inDocument = js.typeOf(js.Dynamic.global.document) != Undefined

    val inJsdom = js.typeOf(js.Dynamic.global.navigator) != Undefined &&
      js.Dynamic
        .global
        .navigator
        .userAgent
        .asInstanceOf[js.UndefOr[String]]
        .exists(_.contains("jsdom"))

    !inDocument || inJsdom
  }

  private def observingFailures(body: => Unit): Future[(Int, Int)] = {
    var uncaught = 0
    var rejections = 0

    val onNodeUncaught: js.Function1[js.Dynamic, Unit] = { (_: js.Dynamic) => uncaught += 1 }
    val onNodeRejection: js.Function1[js.Dynamic, Unit] = { (_: js.Dynamic) => rejections += 1 }

    def browserHandler(bump: () => Unit): js.Function1[js.Dynamic, Unit] = {
      (event: js.Dynamic) =>
        bump()
        event.preventDefault()
        ()
    }

    val onBrowserUncaught = browserHandler(() => uncaught += 1)
    val onBrowserRejection = browserHandler(() => rejections += 1)

    var teardown: List[() => Unit] = Nil

    nodeProcess foreach { p =>
      p.on("uncaughtException", onNodeUncaught)
      p.on("unhandledRejection", onNodeRejection)

      teardown ::= { () =>
        p.removeListener("uncaughtException", onNodeUncaught)
        p.removeListener("unhandledRejection", onNodeRejection)
        ()
      }
    }

    if (js.typeOf(js.Dynamic.global.addEventListener) != Undefined) {
      js.Dynamic.global.addEventListener("error", onBrowserUncaught)
      js.Dynamic.global.addEventListener("unhandledrejection", onBrowserRejection)

      teardown ::= { () =>
        js.Dynamic.global.removeEventListener("error", onBrowserUncaught)
        js.Dynamic.global.removeEventListener("unhandledrejection", onBrowserRejection)
        ()
      }
    }

    body

    val result = Promise[(Int, Int)]()

    // both mechanisms report asynchronously, so give them a turn to fire
    js.timers.setTimeout(100.millis) {
      teardown.foreach(_())
      result.success((uncaught, rejections))
    }

    result.future
  }

  private def nodeProcess: js.UndefOr[js.Dynamic] = {
    val candidate: js.Dynamic =
      if (js.typeOf(js.Dynamic.global.process) != Undefined)
        js.Dynamic.global.process
      else if (js.typeOf(js.Dynamic.global.Node) != Undefined)
        js.Dynamic
          .global
          .Node
          .constructor("return typeof process === 'undefined' ? undefined : process")()
      else
        js.undefined.asInstanceOf[js.Dynamic]

    if (js.typeOf(candidate) != Undefined && js.typeOf(candidate.on) != Undefined)
      candidate
    else
      js.undefined
  }

  @Test
  def `execute a bunch of stuff in 'parallel' and ensure it all runs` = {
    var i = 0

    Future.sequence(List.fill(10000)(Future { i += 1 })) flatMap { _ =>
      Future {
        Try(assert(i == 10000))
      }
    }
  }
}
