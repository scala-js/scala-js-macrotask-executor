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
import scala.scalajs.js.annotation.JSGlobal
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
      def fireTestStarted(description: Description): Unit = ()
      def fireTestSuiteStarted(description: Description): Unit = ()
      def fireTestSuiteFinished(description: Description): Unit = ()
      def fireTestIgnored(description: Description): Unit = ()
      def fireTestFinished(description: Description): Unit = ()
      def fireTestFailure(failure: Failure): Unit = ()
      def fireTestAssumptionFailed(failure: Failure): Unit = ()
    }).onComplete {
      case util.Success(_) => postMessage(true)
      case util.Failure(_) => postMessage(false)
    }
}
