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
import org.scalajs.dom.webworkers.Worker

import scala.concurrent.Promise

class WebWorkerMacrotaskSuite extends FunSuite {

  import MacrotaskExecutor.Implicits._

  def scalaVersion = if (BuildInfo.scalaVersion.startsWith("2"))
    BuildInfo.scalaVersion.split('.').init.mkString(".")
  else
    BuildInfo.scalaVersion

  def targetDir = s"${BuildInfo.baseDirectory}/target/scala-${scalaVersion}"

  override def munitIgnore = {
    println(BuildInfo)
    !BuildInfo.isBrowser
  }

  test("pass the MacrotaskSuite in a web worker") {
    val p = Promise[Boolean]()

    val worker = new Worker(
      s"file://${targetDir}/scala-js-macrotask-executor-webworker-fastopt/main.js"
    )

    worker.onmessage = { event =>
      event.data match {
        case log: String      => println(log)
        case success: Boolean => p.success(success)
        case _                => ()
      }
    }

    p.future.map(assert(_))

  }

}
