# scala-js-macrotask-executor

An implementation of `ExecutionContext` in terms of JavaScript's [`setImmediate`](https://developer.mozilla.org/en-US/docs/Web/API/Window/setImmediate). Unfortunately for everyone involved, `setImmediate` is only available on Edge and Node.js, meaning that this functionality must be polyfilled on all other environments. The details of this polyfill can be found in the readme of the excellent [YuzuJS/setImmediate](https://github.com/YuzuJS/setImmediate) project, though the implementation here is in terms of Scala.js primitives rather than raw JavaScript.

**Unless you have some very, very specific and unusual requirements, this is the optimal `ExecutionContext` implementation for use in any Scala.js project.** If you're using `ExecutionContext` and *not* using this project, you likely have some serious bugs and/or performance issues waiting to be discovered.

## Usage

```sbt
libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0"
```

Published for Scala 2.11, 2.12, 2.13, 3. Functionality is fully supported on all platforms supported by Scala.js (including web workers). In the event that a given platform does *not* have the necessary functionality to implement `setImmediate`-style yielding (usually `postMessage` is what is required), the implementation will transparently fall back to using `setTimeout`, which will drastically inhibit performance but remain otherwise functional.

```scala
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
```

You can also simply import `MacrotaskExecutor` if using the `ExecutionContext` directly.

Once imported, this executor functions exactly the same as `ExecutionContext.global`, except it does not suffer from the various limitations of a `Promise`- or `setTimeout`-based implementation. In other words, you can use `Future` (and other `ExecutionContext`-based tooling) effectively exactly as you would on the JVM, and it will behave effectively identically modulo the single-threaded nature of the runtime.

## Background

The original motivation for this functionality comes from the following case (written here in terms of `Future`, but originally discovered in terms of `IO` in the [Cats Effect project](https://github.com/typelevel/cats-effect)):

```scala
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
```

The `loop()` future will run forever when using the default Scala.js executor, which is written in terms of JavaScript's `Promise`. The *reason* this will run forever stems from the fact that JavaScript includes two separate work queues: the [microtask and the macrotask queue](https://javascript.info/event-loop). The microtask queue is used exclusively by `Promise`, while the macrotask queue is used by everything else, including UI rendering, `setTimeout`, and I/O such as Fetch or Node.js things. The semantics are such that, whenever the microtask queue has work, it takes full precedence over the macrotask queue until the microtask queue is completely exhausted.

This explains why the above snippet will run forever on a `Promise`-based executor: the microtask queue is *never* empty because we're constantly adding new tasks! Thus, `setTimeout` is never able to run because the macrotask queue never receives control.

This is fixable by using a `setTimeout`-based executor, such as the `QueueExecutionContext.timeouts()` implementation in Scala.js. Unfortunately, this runs into an even more serious issue: `setTimeout` is *clamped* in all JavaScript environments. In particular, it is clamped to a minimum of 4ms and, in practice, usually somewhere between 4ms and 10ms. This clamping kicks in whenever more than 5 consecutive timeouts have been scheduled. You can read more details [in the MDM documentation](https://developer.mozilla.org/en-US/docs/Web/API/WindowTimers.setTimeout#Minimum.2F_maximum_delay_and_timeout_nesting).

The only solution to this mess is to yield to the macrotask queue *without* using `setTimeout`. This is precisely what `setImmediate` does on Edge and Node.js. In particular, `setImmediate(...)` is *semantically* equivalent to `setTimeout(0, ...)`, except without the associated clamping. Unfortunately, due to the fact that only a pair of platforms support this function, alternative implementations are required across other major browsers. In particular, *most* environments take advantage of `postMessage` in some way.

### Performance Notes

`setImmediate` in practice seems to be somewhat slower than `Promise.then()`, particularly on Chrome. However, since `Promise` also has seriously detrimental effects (such as blocking UI rendering), it doesn't seem to be a particularly fair comparison. `Promise` is also *slower* than `setImmediate` on Firefox for very unclear reasons likely having to do with fairness issues in the Gecko engine itself.

`setImmediate` is *dramatically* faster than `setTimeout`, mostly due to clamping but also because `setTimeout` has other sources of overhead. In particular, executing 10,000 sequential tasks takes about 30 seconds with `setTimeout` and about 400 *milliseconds* using `setImmediate`.

See [scala-js#4129](https://github.com/scala-js/scala-js/issues/4129) for some background discussion.
