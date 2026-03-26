# com.github.crinklywrappr/weathertop

A Clojure library that instruments your application namespaces via JVM bytecode
transformation and renders a live call-path tree in the browser. Every instrumented
function entry is recorded; the tree updates once per second via SSE so you can watch
hot paths appear in real time.

## Prerequisites

Weathertop uses the ByteBuddy agent to attach itself to the running JVM. Add these
JVM flags to your application startup:

```
-Djdk.attach.allowAttachSelf=true
-XX:+EnableDynamicAgentLoading
```

With Clojure CLI, put them in your alias's `:jvm-opts`:

```clojure
:run {:main-opts ["-m" "myapp.core"]
      :jvm-opts  ["-Djdk.attach.allowAttachSelf=true"
                  "-XX:+EnableDynamicAgentLoading"]}
```

## Installation

```clojure
;; deps.edn
com.github.crinklywrappr/weathertop {:mvn/version "0.1.0"}
```

Transitive dependencies pulled in automatically: `http-kit`, `ring-core`, `jsonista`.

## Quick start

Require and call `start!` after your application namespaces are loaded.
`:ns-prefixes` is required — pass every top-level prefix that belongs to your code.

```clojure
(require '[crinklywrappr.weathertop.core :as weathertop])

(weathertop/start! {:ns-prefixes ["myapp"]
                    :port        7777})   ; port is optional, default 7777
```

Open `http://localhost:7777` in a browser. The call-path tree updates every second
as your application receives traffic.

To stop:

```clojure
(weathertop/stop!)
```

## How it works

`start!` uses ByteBuddy to install a JVM `ClassFileTransformer` that injects a hook
at the entry point of every function in the target namespaces. On each call the hook
uses Java's `StackWalker` to capture the current call path (outermost frame first),
then records it in an in-memory counter. A background thread broadcasts the aggregated
call-path tree to connected browsers once per second via SSE.

The tree distinguishes protocol variants (by record type) and multimethod dispatch
values, so you can see not just which function was called but which implementation
or branch handled each request.

## API

### `start!`

```clojure
(start! opts)
```

| Key | Required | Default | Description |
|-----|----------|---------|-------------|
| `:ns-prefixes` | yes | — | Vector of namespace prefix strings. All loaded namespaces whose name starts with any prefix are instrumented. |
| `:port` | no | `7777` | HTTP port for the call-path tree server. |

Throws `ExceptionInfo` if `:ns-prefixes` is missing or empty.

Instruments all fns, protocol methods, and multimethod bodies in every matching
namespace. Calling `start!` a second time uninstruments the previous set before
re-instrumenting, so it is safe to call from a REPL during development.

**Only namespaces already loaded at the time `start!` is called are instrumented.**
Require your application namespaces before calling `start!`.

### `stop!`

```clojure
(stop!)
```

Disables instrumentation, stops the drainer thread, and shuts down the server.
Injected hook bytecode remains in the loaded classes but is gated by an internal
check, so it is harmless after `stop!` returns.

## Multiple prefixes

```clojure
(weathertop/start! {:ns-prefixes ["myapp" "mylib"]})
```

Both `myapp.*` and `mylib.*` namespaces will appear in the tree.

## Server endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Call-path tree UI (D3 v7 collapsible tree, live via SSE) |
| `GET /data` | Current call-path tree as JSON (one-shot snapshot) |
| `GET /events` | SSE stream — emits the full tree once per second |

## Known limitations

- **Thread granularity.** Each OS thread is an independent call origin. Calls made
  inside `future`, `pmap`, or other threadpool contexts start new top-level entries
  in the tree rather than appearing as children of the spawning call.
- **Namespaces loaded after `start!`** are not instrumented. Call `start!` again to
  pick them up.

## Demo

A worked example lives in `demo/bookstore/` — a small Compojure REST API with a
stress-test script. See [`demo/bookstore/README.md`](demo/bookstore/README.md).

## License

Copyright © 2026 Crinklywrappr

Distributed under the Eclipse Public License version 1.0.
