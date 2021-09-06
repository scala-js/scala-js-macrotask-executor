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

import munit.FunSuite

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.scalajs.js

class MacrotaskExecutorSuite extends FunSuite {
  import MacrotaskExecutor.Implicits._

  test("sequence a series of 10,000 recursive executions without clamping") {
    def loop(n: Int): Future[Int] =
      if (n <= 0)
        Future(0)
      else
        Future.unit.flatMap(_ => loop(n - 1)).map(_ + 1)

    val start = System.currentTimeMillis()
    val MinimumClamp = 10000 * 2 * 4    // HTML5 specifies a 4ms clamp (https://developer.mozilla.org/en-US/docs/Web/API/WindowTimers.setTimeout#Minimum.2F_maximum_delay_and_timeout_nesting)

    loop(10000) flatMap { res =>
      Future {
        val end = System.currentTimeMillis()

        assert(res == 10000)
        assert((end - start).toDouble / MinimumClamp < 0.25)   // we should beat the clamping by at least 4x even on slow environments
      }
    }
  }

  // this test fails to terminate with a Promise-based executor
  test("preserve fairness with setTimeout") {
    var cancel = false

    def loop(): Future[Unit] =
      Future(cancel) flatMap { canceled =>
        if (canceled)
          Future.unit
        else
          loop()
      }

    js.timers.setTimeout(100.millis) {
      cancel = true
    }

    loop()
  }

  test("execute a bunch of stuff in 'parallel' and ensure it all runs") {
    var i = 0

    Future.sequence(List.fill(10000)(Future { i += 1 })) flatMap { _ =>
      Future {
        assert(i == 10000)
      }
    }
  }
}
