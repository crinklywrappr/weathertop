# Bookstore Demo

A small REST API that demonstrates [Weathertop](../..) live call-stack heatmap
instrumentation in a realistic app.

## What it shows

Weathertop wraps every function in every `bookstore.*` namespace and records which
call paths are hot.  The `/books/:id/similar` route has the deepest stack (handler
→ service → two DB calls → ranking → formatting) and will glow hottest when
hammered by the stress-test script.

## Running

```bash
# from this directory
clj -M:run
```

```
Bookstore running on  http://localhost:8080
Weathertop heatmap at http://localhost:7777
```

Open `http://localhost:7777` to see the live heatmap update as traffic flows.

Weathertop is started with `{:ns-prefixes ["bookstore"]}`, which restricts
instrumentation to namespaces whose name begins with `bookstore`.  Only pass
prefixes that belong to your own code — transitive library namespaces are
excluded automatically because they don't match.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/books` | List all books (optional `?tag=<tag>` filter) |
| `POST` | `/books` | Create a book |
| `GET` | `/books/:id` | Get a book by ID |
| `PUT` | `/books/:id` | Update a book |
| `DELETE` | `/books/:id` | Delete a book |
| `GET` | `/books/:id/similar` | Books sharing the most tags with this one |

### Book shape

```json
{
  "id":     "uuid",
  "title":  "string",
  "author": "string",
  "tags":   ["string"],
  "price":  0.00
}
```

`title`, `author`, and `price` are required on create.  `tags` defaults to `[]`.

### Example requests

```bash
# list all books
curl localhost:8080/books

# filter by tag
curl "localhost:8080/books?tag=programming"

# get one book
curl localhost:8080/books/a1b2c3d4-0001-0000-0000-000000000001

# create
curl -X POST localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"title":"SICP","author":"Abelson","tags":["lisp"],"price":0.00}'

# update price
curl -X PUT localhost:8080/books/<id> \
  -H 'Content-Type: application/json' \
  -d '{"price":12.99}'

# delete
curl -X DELETE localhost:8080/books/<id>

# similar books
curl localhost:8080/books/a1b2c3d4-0001-0000-0000-000000000001/similar
```

## Stress testing

```bash
# default: 10 req/s against http://localhost:8080
./stress_test.sh

# custom rate / base URL
./stress_test.sh http://localhost:8080 20
```

The script seeds 5 books, then runs a weighted random mix of requests
(50% GET, 20% similar, 15% create, 10% update, 5% delete).  It prints
one line per request with method, path, HTTP status, and response time.
The pool is replenished automatically if it drops below 3 books.

Press `Ctrl-C` to stop.

## Running tests

```bash
clj -M:test -m cognitect.test-runner --dir test
```

## Project layout

```
src/bookstore/
├── core.clj      -main: starts http-kit on :8080, then Weathertop on :7777
├── handler.clj   compojure routes
├── service.clj   business logic
├── db.clj        in-memory atom store, 10 seed books
├── validate.clj  required-field checks and type coercion
└── format.clj    Ring response builders, JSON serialization

test/bookstore/
└── core_test.clj  integration tests against handler/app
```
