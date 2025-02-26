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

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.scalajs.js
import scala.scalajs.js.annotation._
import scala.util.Random
import scala.util.control.NonFatal

/**
 * Based on https://github.com/YuzuJS/setImmediate
 */
object MacrotaskExecutor extends ExecutionContextExecutor {
  private[this] final val Undefined = "undefined"

  def execute(runnable: Runnable): Unit =
    setImmediate(runnable)

  def reportFailure(cause: Throwable): Unit =
    cause.printStackTrace()

  @js.native
  private[this] trait TaskMap extends js.Object {
    @JSBracketAccess
    def apply(handle: Int): Runnable
    @JSBracketAccess
    def update(handle: Int, task: Runnable): Unit
  }

  private[this] val setImmediate: Runnable => Unit = {
    if (js.typeOf(js.Dynamic.global.setImmediate) != Undefined) {
      { k =>
        js.Dynamic.global.setImmediate(() => k.run())
        ()
      }
    } else if (js.typeOf(js.Dynamic.global.scheduler) != Undefined
                && js.typeOf(js.Dynamic.global.scheduler.postTask) != Undefined) {
      { k =>
        js.Dynamic.global.scheduler.postTask(() => k.run())
        ()
      }
    } else {
      var nextHandle = 1
      val tasksByHandle = (new js.Object).asInstanceOf[TaskMap]
      var currentlyRunningATask = false

      def canUsePostMessage(): Boolean = {
        // The test against `importScripts` prevents this implementation from being installed inside a web worker,
        // where `global.postMessage` means something completely different and can't be used for this purpose.
        if (js.typeOf(js.Dynamic.global.postMessage) != Undefined && js.typeOf(
            js.Dynamic.global.importScripts) == Undefined) {
          var postMessageIsAsynchronous = true
          val oldOnMessage = js.Dynamic.global.onmessage

          try {
            // This line throws `ReferenceError: onmessage is not defined` in JSDOMNodeJS environment
            js.Dynamic.global.onmessage = { () => postMessageIsAsynchronous = false }
            js.Dynamic.global.postMessage("", "*")
            js.Dynamic.global.onmessage = oldOnMessage
            postMessageIsAsynchronous
          } catch {
            case NonFatal(_) =>
              false
          }
        } else {
          false
        }
      }

      def runTaskForHandle(handle: Int): Unit = {
        if (currentlyRunningATask) {
          js.Dynamic.global.setTimeout(() => runTaskForHandle(handle), 0)
        } else {
          val task = tasksByHandle(handle)
          currentlyRunningATask = true
          try {
            task.run()
          } finally {
            js.special.delete(tasksByHandle, handle)
            currentlyRunningATask = false
          }
        }

        ()
      }

      if (
        js.typeOf(
          js.Dynamic.global.navigator
        ) != Undefined && js.Dynamic.global.navigator.userAgent
          .asInstanceOf[js.UndefOr[String]]
          .exists(_.contains("jsdom"))
      ) {
        val setImmediate =
          js.Dynamic.global.Node.constructor("return setImmediate")()

        { k =>
          setImmediate(() => k.run())
          ()
        }
      } else if (canUsePostMessage()) {
        // postMessage is what we use for most modern browsers (when not in a webworker)

        // generate a unique messagePrefix for everything we do
        // collision here is *extremely* unlikely, but the random makes it somewhat less so
        // as an example, if end-user code is using the setImmediate.js polyfill, we don't
        // want to accidentally collide. Then again, if they *are* using the polyfill, we
        // would pick it up above unless they init us first. Either way, the odds of
        // collision here are microscopic.
        val messagePrefix = "setImmediate$" + Random.nextInt() + "$"

        def onGlobalMessage(event: js.Dynamic): Unit = {
          if (/*event.source == js.Dynamic.global.global &&*/ js.typeOf(
              event.data) == "string" && event
              .data
              .indexOf(messagePrefix)
              .asInstanceOf[Int] == 0) {
            runTaskForHandle(event.data.toString.substring(messagePrefix.length).toInt)
          }
        }

        if (js.typeOf(js.Dynamic.global.addEventListener) != Undefined) {
          js.Dynamic.global.addEventListener("message", onGlobalMessage _, false)
        } else {
          js.Dynamic.global.attachEvent("onmessage", onGlobalMessage _)
        }

        { k =>
          val handle = nextHandle
          nextHandle += 1

          tasksByHandle(handle) = k
          js.Dynamic.global.postMessage(messagePrefix + handle, "*")
          ()
        }
      } else if (js.typeOf(js.Dynamic.global.MessageChannel) != Undefined) {
        val channel = js.Dynamic.newInstance(js.Dynamic.global.MessageChannel)()

        channel.port1.onmessage = { (event: js.Dynamic) =>
          runTaskForHandle(event.data.asInstanceOf[Int])
        }

        { k =>
          val handle = nextHandle
          nextHandle += 1

          tasksByHandle(handle) = k
          channel.port2.postMessage(handle)
          ()
        }
      } else {
        // we don't try to look for process.nextTick since scalajs doesn't support old node
        // we're also not going to bother fast-pathing for IE6; just fall through

        { k =>
          js.Dynamic.global.setTimeout(() => k.run(), 0)
          ()
        }
      }
    }
  }

  object Implicits {
    implicit def global: ExecutionContext = MacrotaskExecutor
  }
}
