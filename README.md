# com.github.crinklywrappr/weathertop

A Clojure library that instruments your application namespaces and renders a
live call-stack heatmap in the browser.  Every function call is recorded;
the heatmap updates once per second via SSE so you can watch hot paths light
up in real time.

## Installation

```clojure
;; deps.edn
com.github.crinklywrappr/weathertop {:mvn/version "0.1.0"}
```

Transitive dependencies pulled in automatically: `http-kit`, `ring-core`,
`jsonista`.

## Quick start

Require and call `start!` after your application namespaces are loaded.
`:ns-prefixes` is required — pass every top-level prefix that belongs to
your code.

```clojure
(require '[crinklywrappr.weathertop.core :as weathertop])

(weathertop/start! {:ns-prefixes ["myapp"]
                    :port        7777})   ; port is optional, default 7777
```

Open `http://localhost:7777` in a browser.  The heatmap updates every second
as your application receives traffic.

To stop:

```clojure
(weathertop/stop!)
```

## API

### `start!`

```clojure
(start! opts)
```

| Key | Required | Default | Description |
|-----|----------|---------|-------------|
| `:ns-prefixes` | yes | — | Vector of namespace prefix strings. All loaded namespaces whose name starts with any prefix are instrumented. |
| `:port` | no | `7777` | HTTP port for the heatmap server. |

Throws `ExceptionInfo` if `:ns-prefixes` is missing or empty.

Instruments all public, non-macro, non-protocol, non-multimethod functions in
every matching namespace.  Calling `start!` a second time uninstruments the
previous set before re-instrumenting, so it is safe to call from a REPL
during development.

**Only namespaces already loaded at the time `start!` is called are
instrumented.**  Require your application namespaces before calling `start!`.

### `stop!`

```clojure
(stop!)
```

Restores all instrumented vars to their original functions, stops the drainer
thread, and shuts down the heatmap server.

## Multiple prefixes

```clojure
(weathertop/start! {:ns-prefixes ["myapp" "mylib"]})
```

Both `myapp.*` and `mylib.*` namespaces will appear in the heatmap.

## Heatmap server endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Heatmap UI (D3 v7 tree, live via SSE) |
| `GET /data` | Current call-path tree as JSON (one-shot snapshot) |
| `GET /events` | SSE stream — emits the full tree once per second |

## Known limitations

- **`*call-stack*` is thread-local.** Calls made inside `future`, `pmap`, or
  any other threadpool start a fresh top-level entry in the heatmap.
  `core.async` go blocks lose call-stack context at park points.
- **Protocol methods are not instrumented** in v0.1.
- **Namespaces loaded after `start!`** are not instrumented.  Call `start!`
  again (or `stop!` / `start!`) to pick them up.
- **Call depth is capped at 20** to prevent runaway recursion from filling
  the store.

## Demo

A worked example lives in `demo/bookstore/` — a small compojure REST API
with a stress-test script.  See [`demo/bookstore/README.md`](demo/bookstore/README.md).

## License

Copyright © 2026 Crinklywrappr

Distributed under the Eclipse Public License version 1.0.
