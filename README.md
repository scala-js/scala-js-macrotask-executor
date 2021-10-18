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

This explains why the above snippet will run forever on a `Promise`-based executor: the microtask queue is *never* empty because we're constantly adding new tasks! Thus, `setTimeout` is never able to run because the macrotask queue never receives control. This is horrible, even by JavaScript standards.

### `setTimeout`

This is fixable by using a `setTimeout`-based executor, such as the `QueueExecutionContext.timeouts()` implementation in Scala.js. Available in all browsers since the dawn of time, `setTimeout` takes two arguments: a time delay and a callback to invoke. The callback is invoked by the event loop once the time delay expires, and this is implemented by pushing the callback onto the back of the event queue at the appropriate time. Calling `setTimeout` with a delay of `0` would seem to achieve *exactly* the semantics we want: yield back to the event loop and allow it to resume our callback when it's our turn once again.

Unfortunately, `setTimeout` is slow. Very, very, very slow. The timing mechanism imposes quite a bit of overhead, even when the delay is `0`, and there are other complexities which ultimately impose a performance penalty too severe to accept. Any significant application of an `ExecutionContext` backed by `setTimeout`, would be almost unusable.

To make matters worse, `setTimeout` is *clamped* in all JavaScript environments. In particular, it is clamped to a minimum of 4ms and, in practice, usually somewhere between 4ms and 10ms. This clamping kicks in whenever more than 5 consecutive timeouts have been scheduled:

```javascript
setTimeout(() => {
  setTimeout(() => {
    setTimeout(() => {
      setTimeout(() => {
        setTimeout(() => {
          // this one (and all after it) are clamped!
        }, 0);
      }, 0);
    }, 0);
  }, 0);
}, 0);
```

Each timeout sets a new timeout, and so on and so on. This is exactly the sort of situation that we get into when chaining `Future`s, where each `map`/`flatMap`/`transform`/etc. schedules another `Future` which, in turn will schedule another... etc. etc. This is exactly where we see clamping. In particular, the innermost `setTimeout` in this example will be clamped to 4 milliseconds (meaning there is no difference between `setTimeout(.., 0)` and `setTimeout(.., 4)`), which would slow down execution *even more*.

You can read more details [in the MDN documentation](https://developer.mozilla.org/en-US/docs/Web/API/WindowTimers.setTimeout#Minimum.2F_maximum_delay_and_timeout_nesting).

### `setImmediate`

Fortunately, we aren't the only ones to have this problem. What we *want* is something which uses the macrotask queue (so we play nicely with `setTimeout`, I/O, and other macrotasks), but which doesn't have as much overhead as `setTimeout`. The answer is `setImmediate`.

The `setImmediate` function was first introduced in NodeJS, and its purpose is to solve *exactly* this problem: a faster `setTimeout(..., 0)`. In particular, `setImmediate(...)` is *semantically* equivalent to `setTimeout(0, ...)`, except without the associated clamping: it doesn't include a delay mechanism of any sort, it simply takes a callback and immediately submits it to the event loop, which in turn will run the callback as soon as its turn comes up.

Unfortunately, `setImmediate` isn't available on every platform. For reasons of... their own, Mozilla, Google, and Apple have all strenuously objected to the inclusion of `setImmediate` in the W3C standard set, despite the proposal (which originated at Microsoft) and obvious usefulness. This in turn has resulted in an all-too familiar patchwork of inconsistency across the JavaScript space.

That's the bad news. The good news is that all modern browsers include *some* sort of functionality which can be exploited to emulate `setImmediate` with similar performance characteristics. In particular, *most* environments take advantage of `postMessage` in some way. If you're interested in the nitty-gritty details of how this works, you are referred to [this excellent readme](https://github.com/YuzuJS/setImmediate#the-tricks).

scala-js-macrotask-executor implements *most* of the `setImmediate` polyfill in terms of ScalaJS, wrapped up in an `ExecutionContext` interface. The only elements of the polyfill which are *not* implemented are as follows:

- `process.nextTick` is used by the JavaScript polyfill when running on NodeJS versions below 0.9. However, ScalaJS itself does not support NodeJS 0.9 or below, so there's really no point in supporting this case.
- Similarly, older versions of IE (6 through 8, specifically) allow a particular exploitation of the `onreadystatechange` event fired when a `<script>` element is inserted into the DOM. However, ScalaJS does not support these environments *either*, and so there is no benefit to implementing this case.

On environments where the polyfill is unsupported, `setTimeout` is still used as a final fallback.

### Performance Notes

Optimal performance is currently available in the following environments:

- [NodeJS 0.9.1+](https://nodejs.org/api/timers.html#timers_setimmediate_callback_args)
- [Browsers implementing `window.postMessage()`](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage#browser_compatibility), including:
  - Chrome 1+
  - Safari 4+
  - Internet Explorer 9+ (including Edge)
  - Firefox 3+
  - Opera 9.5+
- [Web Workers implementing `MessageChannel`](https://developer.mozilla.org/en-US/docs/Web/API/MessageChannel#browser_compatibility)

`setImmediate` in practice seems to be somewhat slower than `Promise.then()`, particularly on Chrome. However, since `Promise` also has seriously detrimental effects (such as blocking UI rendering), it doesn't seem to be a particularly fair comparison. `Promise` is also *slower* than `setImmediate` on Firefox for very unclear reasons likely having to do with fairness issues in the Gecko engine itself.

`setImmediate` is *dramatically* faster than `setTimeout`, mostly due to clamping but also because `setTimeout` has other sources of overhead. In particular, executing 10,000 sequential tasks takes about 30 seconds with `setTimeout` and about 400 *milliseconds* using `setImmediate`.

See [scala-js#4129](https://github.com/scala-js/scala-js/issues/4129) for additional background discussion.
