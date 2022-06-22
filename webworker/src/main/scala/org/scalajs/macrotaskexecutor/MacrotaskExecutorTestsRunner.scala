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

import scala.scalajs.concurrent.QueueExecutionContext.timeouts
import scala.scalajs.js

object MacrotaskExecutorTestsRunner {

  def main(args: Array[String]): Unit = {
    val tests = new MacrotaskExecutorTests

    implicit val ec = timeouts()

    for {
      clamping <- tests.`sequence a series of 10,000 recursive executions without clamping`
      fairness <- tests.`preserve fairness with setTimeout`
      parallel <- tests.`execute a bunch of stuff in 'parallel' and ensure it all runs`
    } yield js.Dynamic.global.postMessage(js.Dictionary(
      "clamping" -> clamping.isSuccess,
      "fairness" -> fairness.isSuccess,
      "parallel" -> parallel.isSuccess
    ))

    ()
  }
}
