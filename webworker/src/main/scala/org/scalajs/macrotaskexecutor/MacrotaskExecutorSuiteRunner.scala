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

import munit.MUnitRunner
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import org.scalajs.dom.webworkers.DedicatedWorkerGlobalScope

import scala.scalajs.js
import scala.util

object MacrotaskExecutorSuiteRunner {

  import MacrotaskExecutor.Implicits._

  def postMessage(msg: js.Any): Unit =
    DedicatedWorkerGlobalScope.self.postMessage(msg)

  def main(args: Array[String]): Unit =
    new MUnitRunner(
      classOf[MacrotaskExecutorSuite],
      () => new MacrotaskExecutorSuite
    ).runAsync(new RunNotifier {

      var count = new MacrotaskExecutorSuite().munitTests().size
      var overallSuccess = true
      def reportTest(success: Boolean): Unit = {
        overallSuccess &= success
        count -= 1
        if (count == 0) postMessage(overallSuccess)
      }

      def fireTestStarted(description: Description): Unit = ()

      def fireTestSuiteStarted(description: Description): Unit =
        postMessage(s"${classOf[MacrotaskExecutorSuite].getName}:")

      // This doesn't account for async and fires before any tests are run!
      def fireTestSuiteFinished(description: Description): Unit = ()

      def fireTestIgnored(description: Description): Unit = ()

      def fireTestFinished(description: Description): Unit = {
        postMessage(s"  + ${description.getMethodName}")
        reportTest(success = true)
      }

      def fireTestFailure(failure: Failure): Unit = {
        postMessage(
          s"==> X ${classOf[MacrotaskExecutorSuite].getName}.${failure.description.getMethodName}"
        )
        reportTest(success = false)
      }

      def fireTestAssumptionFailed(failure: Failure): Unit =
        reportTest(success = false)

    })
}
