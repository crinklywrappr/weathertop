# crinklywrappr/stacklens

A Clojure library that walks the current thread's JVM stack and returns a
structured list of Clojure-level frames — regular fns, defrecord protocol
methods, defmethod bodies, and extend-type/extend-protocol implementations —
with namespace-prefix filtering.

## Installation

```clojure
;; deps.edn  (local reference)
crinklywrappr/stacklens {:local/root "stacklens"}
```

Only requires `org.clojure/clojure`.

## Usage

```clojure
(require '[crinklywrappr.stacklens.core :as stacklens])

;; Walk the current thread's stack.
;; Returns a list of StackFrame records, outermost frame first.
(stacklens/current-stack {})

;; Limit to frames from namespaces whose munged class name starts with "myapp":
(stacklens/current-stack {:include ["myapp"]})

;; Exclude frames from a noisy namespace:
(stacklens/current-stack {:exclude ["myapp.generated"]})
```

## `current-stack`

```clojure
(current-stack opts) => (StackFrame ...)
```

Walks the current thread's JVM stack using `java.lang.StackWalker` and
returns a list of `StackFrame` records, **outermost frame first**.

Adjacent frames from the same declaring class are deduplicated (removes the
`invokeStatic`/`invoke` pair that Clojure emits for every named fn call).

**opts keys**

| Key | Type | Description |
|-----|------|-------------|
| `:include` | seq of strings | Only frames whose declaring class name starts with one of these prefixes are kept. Applied after `:exclude`. |
| `:exclude` | seq of strings | Frames whose declaring class name starts with one of these prefixes are dropped. Applied before `:include`. |

Both filters operate on JVM class names (dots, no slashes). Namespace
hyphens are munged to underscores by the Clojure compiler, so `my-ns`
becomes `my_ns` in the class name.

**StackFrame fields**

| Field | Always present | Description |
|-------|---------------|-------------|
| `:fn` | yes | Fully-qualified Clojure fn name, e.g. `"myapp.core/handler"` |
| `:record-type` | defrecord protocol/extend-type/extend-protocol methods | Simple class name of the implementing record, e.g. `"FullPrice"` |
| `:multimethod-dispatch-val` | defmethod bodies | The dispatch value the body was selected for |
| `:multimethod-dispatch-fn?` | anonymous defmulti dispatch fn frames | `true` when the frame is the dispatch fn itself |

## `prime-cache!`

```clojure
(prime-cache! opts)
```

Eagerly builds the internal method cache that maps JVM class names to
Clojure metadata (`:fq-sym`, `:dispatch-val`, `:record-type`, etc.).

Call this **before** instrumenting namespaces with a tool like ByteBuddy.
If you instrument first, the wrappers the instrumentation inserts may replace
the original fn objects before the cache is built, causing defmethod and
protocol-impl frames to be misclassified.

## Metadata keys for instrumentation wrappers

If you wrap MultiFn or method-body fn objects (e.g. to inject hooks) you can
preserve cache correctness by attaching these metadata keys to your wrapper:

| Key | Value | Effect |
|-----|-------|--------|
| `stacklens/original-multifn` | the original `MultiFn` | `build-method-cache` indexes the original MultiFn's methods instead of the wrapper |
| `stacklens/original-method-class` | original body class name string | Cache key used instead of the wrapper's own class name |

## Frame classification

Frames are classified in three passes:

1. **IRecord** — any class that implements `clojure.lang.IRecord`. Infrastructure
   methods (`hashCode`, `equals`, `valAt`, `getLookupThunk`, etc.) are excluded.
   Yields `:record-type`.

2. **Method cache hit** — class name found in the lazy method cache built from
   all currently-loaded namespaces. Covers defmethod bodies, anonymous dispatch
   fns, and extend-type/extend-protocol impls. Yields `:multimethod-dispatch-val`,
   `:multimethod-dispatch-fn?`, or `:record-type` depending on entry type.

3. **Named top-level fn** — class name contains exactly one `$`
   (`ns$fn_name`). Multi-segment names not in the cache are runtime artefacts
   (eval frames, protocol dispatch wrappers) and are silently dropped.

## License

Copyright © 2026 Crinklywrappr

Distributed under the Eclipse Public License version 1.0.
