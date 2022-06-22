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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.concurrent.QueueExecutionContext.timeouts
import scala.scalajs.js
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class WebWorkerMacrotaskTests {

  implicit val ec: ExecutionContext = timeouts()

  val worker =
    js.Dynamic.newInstance(js.Dynamic.global.Worker)(s"file://${BuildInfo.workerDir}/main.js")

  val testsResult = Promise[js.Dictionary[Boolean]]()
  worker.onmessage = { (event: js.Dynamic) =>
    testsResult.success(event.data.asInstanceOf[js.Dictionary[Boolean]])
  }

  def getTestResult(id: String): Future[Try[Unit]] =
    testsResult.future.map { results =>
      if (results.getOrElse(id, false))
        Success(())
      else
        Failure(new AssertionError)
    }

  @Test
  def `sequence a series of 10,000 recursive executions without clamping` =
    getTestResult("clamping")

  @Test
  def `preserve fairness with setTimeout` =
    getTestResult("fairness")

  @Test
  def `execute a bunch of stuff in 'parallel' and ensure it all runs` =
    getTestResult("parallel")

}
