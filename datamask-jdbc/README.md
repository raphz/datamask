# datamask-jdbc

**Keeps row values out of what the database tells the application.**

```java
DataSource dataSource = new MaskingDataSource(hikariDataSource, dataMask);
```

Wrap the pool, do not replace it. This is a thin proxy with no pooling behaviour of its own, so it
goes outside HikariCP — or whatever is already configured — and leaves that pool untouched.

## The leak

Annotations cover the data you knew about. A unique-constraint violation covers you in data you did
not: PostgreSQL answers one by echoing the offending row.

```
ERROR: duplicate key value violates unique constraint "customer_email_key"
  Detail: Key (email)=(john@x.com) already exists.
```

That string *is* the exception's message. It reaches the stack trace, every log line that renders the
exception, and wherever the logs are shipped. There is no field to annotate and no obvious place to
look, which is why it survives in systems that mask everything else.

With the wrapper in place the same error reads:

```
ERROR: duplicate key value violates unique constraint "customer_email_key"
  Detail: Key (email)=(****) already exists.
```

The constraint, the table and the column names survive. So do the SQL state and the stack trace.
Only the value goes.

## Why it matches punctuation, not words

PostgreSQL errors are localised twice over: the server translates its own message text according to
`lc_messages`, and the driver translates the `Detail:` and `Hint:` labels it wraps them in. A rule
keyed on the English word `Key` would quietly stop redacting the day someone deploys against a German
server — which is exactly the class of failure this library exists to prevent, because nothing about
it is visible in testing.

So what gets matched is the structure `(columns)=(values)`: the parentheses and the equals sign are
emitted by the server unchanged in every locale.

The match is deliberately **greedy to the last closing parenthesis**. A value may contain `)` —
PostgreSQL does not escape it — and a lazy match would stop early and leave the tail of the value in
the message. Being greedy can swallow trailing prose such as `is not present in table "customer"`,
which costs a little detail; being lazy can leak.

## What happens to each part of the error

| Part | Treatment | Why |
|---|---|---|
| primary message | structure masked, then content-scanned; **kept** | The diagnostic core: the operation and the constraint. PostgreSQL's own message style guide puts data in `DETAIL`, and pgjdbc's `logServerErrorDetail=false` reduces an error to exactly severity plus message and calls that non-sensitive. |
| `Detail` | structure masked; **dropped whole** if no structure matched | This part exists to quote the offending row, so an unrecognised shape is far likelier to be a row than prose worth keeping. `Failing row contains (3, bad@x.com, x).` is that case. |
| `Hint` | content-scanned; kept | Generated advice about the schema. |
| `Where` | double-quoted spans redacted, then scanned | In a context stack a quoted span is SQL statement text carrying literals — the opposite of the primary message, where a quoted span is an identifier worth keeping. The function and line survive. |
| `Internal Query` | **dropped** | The SQL text of a statement inside a function, literals and all, with no structure to mask. |
| schema, table, column, constraint, datatype, file, line, routine, position | kept | Identifiers and source locations, not data. They are what makes a sanitised error still worth reading. |

Content scanning is what catches a value no structural rule would see — an IBAN in
`invalid input syntax for type integer: "CH93…"`. It is also where `MaskingObserver.onUnannotatedPii`
fires, and a database error is the most valuable place for that signal: it means production data
reached a log line.

## What you get back

**The same exception object** when there was nothing to remove — which is the common case. An
ordinary `relation "customre" does not exist` reaches the application exactly as the driver threw it,
same instance, same type.

**A genuine `PSQLException`** when the driver is on the classpath and something had to go. Not a
rewritten message string: `ServerErrorMessage` parses the server's `\0`-delimited ErrorResponse and
that constructor is public, so a sanitised error is assembled in the format the driver itself
consumes. The consequence is that everything keeps working — `instanceof PSQLException` still holds,
`getSQLState()` is unchanged, `getServerErrorMessage().getDetail()` returns the **masked** detail
rather than the raw one, and `getMessage()` is composed by the driver, so the `Detail:` label is
still translated for the user's locale.

Rewriting only the composed message would have been the wrong fix twice over: the labels in it are
localised, and the raw value would still be sitting in `getServerErrorMessage()` for anyone who asks.

**The standard JDBC subclass** for any other driver — SQL state class `23` becomes
`SQLIntegrityConstraintViolationException`, `22` `SQLDataException`, `42` `SQLSyntaxErrorException`,
and so on — so `catch (SQLIntegrityConstraintViolationException e)`, Spring's exception translation
and Hibernate's dialect all keep classifying the error the same way.

The original exception is **never kept as a cause**: it holds the raw text, and a cause is printed
with the exception that wraps it. The stack trace is copied across instead, because it says where the
failure happened and never what it happened to.

All three chains are walked — `getCause()`, the JDBC-specific `getNextException()` (easy to forget,
just as visible once anything iterates the exception) and suppressed exceptions, which
`printStackTrace` renders like any other.

## Statement logging

Set `ch.raph.datamask.jdbc.statement` to DEBUG:

```
insert into customer (email, iban, id) values (?, ?, ?) [1=****, 2=CH93 **** **** **** *295 7, 3=<Integer>]
```

Every parameter is masked, with no exception for values that look harmless. There is no annotation to
consult at the JDBC layer — the column a parameter is destined for is a name in a SQL string — and
guessing which values are safe is how a logger ends up printing an account number.

What is left still reads as a query: which parameters were bound, in what order, of what type. Where
a detector recognises a value outright, the category's own masking is used, so an IBAN reads as an
IBAN and stays correlatable between log lines without being disclosed. A card number shows its last
four digits and no more. Anything unrecognised becomes the placeholder. Numbers, dates and booleans
render as their type alone: a customer id, a balance and a date of birth are all PII and none has a
partial form that is safe by default.

Below DEBUG **no parameter is examined at all** — masking one means running the detectors over it, and
that cost is only paid by someone who asked for the log.

## Using it without a DataSource

`SqlExceptionSanitizer` is the same logic on a single exception, for code that catches one elsewhere —
an `@ExceptionHandler`, a Hibernate listener, a batch job:

```java
SqlExceptionSanitizer sanitizer = new SqlExceptionSanitizer(dataMask);

try {
    repository.save(customer);
} catch (SQLException e) {
    throw sanitizer.sanitize(e);
}
```

## Two behaviours worth knowing about

**`unwrap` returns the real object, unproxied**, as JDBC intends. Code reaching for `PGConnection` to
run a `COPY` needs the driver's own connection and refusing would break it. Exceptions from an object
obtained that way are nobody's to sanitise.

**Result sets are proxied too.** An error can surface during a fetch rather than at execution — a cast
failing on a stored value, a statement timeout — and in cursor mode that arrives from `next()`.
Leaving result sets unwrapped would put a hole exactly in the path that reads data. The cost is one
reflective dispatch per call, the same as p6spy or datasource-proxy.

## The PostgreSQL driver is optional

`compileOnly`. With the driver present the module works on the error's structured parts, which is
locale-independent; without it, every `SQLException` is still sanitised as text. Nothing here requires
PostgreSQL.

## Tests

`MaskingDataSourcePostgresTest` provokes a genuine unique-constraint violation against a real
PostgreSQL server via Testcontainers, and its first test asserts that the value **does** leak without
the wrapper — a test that something is absent proves nothing unless the same query demonstrably
leaks it otherwise. It is skipped when Docker is unavailable; CI has Docker.

Everything else runs without a server. `SqlExceptionSanitizerTest` builds its exceptions from the
driver's own wire format, so they are the driver's parsing of the driver's input rather than stubs.
