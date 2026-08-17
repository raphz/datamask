# datamask-benchmarks

**Measures what masking costs, so the claim that the logging integrations are safe at production
volume is a number rather than an opinion.** It is never published: it applies
`datamask.java-base-conventions`, exactly like `datamask-architecture-tests`, so it has no route to
Maven Central, and it is excluded from the coverage aggregate for the same reason.

```bash
./gradlew :datamask-benchmarks:jmh                                        # the full set, publication settings
./gradlew :datamask-benchmarks:jmh -Pjmh.args="-f 1 -wi 2 -i 3"           # a quick look
./gradlew :datamask-benchmarks:jmh -Pjmh.args="LogbackAppender -f 1"      # one class
./gradlew :datamask-benchmarks:jmh -Pjmh.args="-prof stack"               # where the time goes
```

Everything after `-Pjmh.args=` goes to the JMH harness unchanged, so `-rf json -rff results.json`,
`-prof gc` and a benchmark-name regex all work.

## In CI

**`./gradlew build` never runs these.** The `jmh` task is deliberately not wired into `check`: a full
set is tens of minutes, and a shared runner's numbers are too noisy to gate a merge on — a benchmark
that fails a pull request for reasons nobody can reproduce is a benchmark somebody deletes. The
module is still *compiled* by every build, so a broken benchmark fails a PR like any other
compilation error.

Running them is the **Benchmarks** workflow (`.github/workflows/benchmarks.yml`): on demand from the
Actions tab, with the harness arguments and a note as inputs, and once a month to catch drift nobody
was looking for — a dependency bump, a JDK change, a detector added without anyone thinking about the
scan cost. Each run writes its table into the run summary and keeps the raw JMH JSON for 90 days.

Those numbers are comparable to **other runs of that workflow**, and not to the table below: a shared
runner has neighbours. A figure worth quoting comes from a quiet machine.

## Where the baseline lives

`docs/IMPROVEMENTS.md` carries the **2026-08-17 baseline** — the same table as below, taken before
any of the performance work in that document was done, alongside the ranked list of what to improve
and what each change is expected to be worth. That is the copy to compare against and the one to add
a dated row to; this README describes what the benchmarks *are*.

## The headline

**A clean `INFO` line — one carrying no PII at all — through `datamask-logback`'s masking appender,
against the same event through the appender underneath it.** That is
`LogbackAppenderBenchmark.plainAppenderCleanLine` and
`LogbackAppenderBenchmark.maskingAppenderCleanLine`, and the two are in the benchmark set side by
side so the comparison is read rather than computed.

Both append a pre-built event to the same sink. The only difference is a `MaskingAppender` in front
of it, which is exactly the change an application makes when it adopts this module.

## What each benchmark isolates

| Benchmark | What it answers |
|---|---|
| `LogbackAppenderBenchmark.plainAppenderCleanLine` | **Baseline.** The sink alone. It stores a reference, so its cost does not depend on what the event carries — which makes it the baseline for the IBAN and card lines too |
| `LogbackAppenderBenchmark.maskingAppenderCleanLine` | **Headline.** The same event with masking in front: what a line with nothing to hide pays |
| `LogbackAppenderBenchmark.maskingAppenderIbanLine` | The same line with an IBAN in the message: detection, pseudonymisation, a rebuilt event |
| `LogbackAppenderBenchmark.maskingAppenderCardLine` | The same with a card number, which is masked whole rather than partially revealed |
| `LogbackAppenderBenchmark.plainAppenderMdcAndException` | **Baseline** for the event below |
| `LogbackAppenderBenchmark.maskingAppenderMdcAndException` | Three MDC entries and an exception with a cause — the per-event costs the improvements document names, all of which are paid on every line rather than only on the interesting ones |
| `Log4j2RewriteBenchmark.identityRewrite*` | **Baselines.** A rewrite policy that returns its argument, which is the floor a `Rewrite` appender costs empty |
| `Log4j2RewriteBenchmark.maskingRewrite*` | The same four shapes through `MaskingRewritePolicy`, so logback and log4j2 are comparable in one run |
| `MaskingEngineBenchmark.maskCleanGraph` | `MaskingEngine.mask` on a PII-free object graph: the no-change short-circuit, which returns the same instance and allocates no copy |
| `MaskingEngineBenchmark.maskCleanGraphWithoutTextScan` | The same graph with content scanning switched off — the walk and nothing else, so the gap to the line above is what scanning costs a graph that had nothing to find |
| `MaskingEngineBenchmark.maskGraphWithPii` | The same shape, six declared members, all masked and the graph rebuilt |
| `TextSanitizerBenchmark.sanitizeNoMatch` | The detector set on a normal log line of prose that matches nothing — the path the gates were built for |
| `TextSanitizerBenchmark.sanitizeNoMatchWithDigits` | **The honest gate case.** The same answer on a line with an order number and a timestamp in it, which opens four of the twelve gates. The number to quote for a real log line |
| `TextSanitizerBenchmark.sanitizeLongNoMatch` | The same answer on ~2 KB, which says whether that cost is per-call or per-character |
| `TextSanitizerBenchmark.sanitizeWithIban` | One match in the same short line: detection, masking, a rebuilt string |
| `TextSanitizerBenchmark.sanitizeOversizedCapped` | 64 KB scanned as far as `MaskingPolicy.maxTextLength` allows, with the rest redacted unread |
| `TextSanitizerBenchmark.sanitizeOversizedUncapped` | The same 64 KB with the cap removed — the pair is what the cap is worth, measured rather than extrapolated |
| `JdbcProxyBenchmark.rawResultSetRow` | **Baseline.** Ten columns from an unwrapped stub result set, so what is left between it and the next row is the forwarding |
| `JdbcProxyBenchmark.proxiedResultSetRow` | The same row through `MaskingDataSource` — one `Method.invoke` per call, on the one path whose cost scales with the size of a result |
| `JdbcProxyBenchmark.unwrappedResultSetRow` | And through `withoutResultSetWrapping()`, which is how the escape hatch proves it does what it says |
| `PlanCompilerBenchmark.compilePlansReflectively` | What three types cost the first time they are seen, derived by reflection |
| `PlanCompilerBenchmark.compilePlansFromGeneratedCode` | The same three from the plans `datamask-build-processor` wrote beside them |
| `PlanCompilerBenchmark.maskWithReflectivePlans` | Steady state: masking through warm reflective plans (`MethodHandle` per member) |
| `PlanCompilerBenchmark.maskWithGeneratedPlans` | Steady state through warm generated plans (direct calls, no `setAccessible`) |

## Measured numbers

> **Indicative, not publication-grade.** One fork, three warmup and five measurement iterations of
> one second each — enough to see the shape and rank the paths, not enough to defend a 10%
> difference. A number worth quoting comes from `./gradlew :datamask-benchmarks:jmh` with the
> defaults in the code (2 forks, 5×1 s warmup, 5×1 s measurement) on an idle machine.

Apple M2 Pro (10 cores), macOS 26.6.1, Temurin OpenJDK 25.0.4+7, `-f 1 -wi 3 -i 5 -r 1s -w 1s`,
2026-08-17. Average time per operation, lower is better.

The `before` column is the same run taken earlier the same day, on the same machine and the same
fixtures, before the detector gates, the text length cap and the traversal work landed. It is kept
here because the shape of the change is more useful than either column alone —
[`docs/IMPROVEMENTS.md`](../docs/IMPROVEMENTS.md) records what each item was worth.

| Benchmark | before | after | ± |
|---|---:|---:|---:|
| `LogbackAppenderBenchmark.plainAppenderCleanLine` | 2.6 | 2.3 | 0.04 |
| `LogbackAppenderBenchmark.maskingAppenderCleanLine` | 11 081 | **547** | 10 |
| `LogbackAppenderBenchmark.maskingAppenderIbanLine` | 14 819 | 3 608 | 781 |
| `LogbackAppenderBenchmark.maskingAppenderCardLine` | 14 089 | 1 145 | 29 |
| `LogbackAppenderBenchmark.plainAppenderMdcAndException` | 2.3 | 2.6 | 0.08 |
| `LogbackAppenderBenchmark.maskingAppenderMdcAndException` | 48 485 | 8 829 | 277 |
| `Log4j2RewriteBenchmark.identityRewriteCleanLine` | 0.43 | 0.50 | 0.14 |
| `Log4j2RewriteBenchmark.maskingRewriteCleanLine` | 12 194 | 594 | 20 |
| `Log4j2RewriteBenchmark.maskingRewriteIbanLine` | 15 513 | 3 364 | 117 |
| `Log4j2RewriteBenchmark.maskingRewriteCardLine` | 14 105 | 1 106 | 215 |
| `Log4j2RewriteBenchmark.identityRewriteContextAndException` | 0.43 | 0.44 | 0.12 |
| `Log4j2RewriteBenchmark.maskingRewriteContextAndException` | 53 235 | 10 403 | 723 |
| `MaskingEngineBenchmark.maskCleanGraph` | 12 466 | 1 143 | 45 |
| `MaskingEngineBenchmark.maskCleanGraphWithoutTextScan` | 620 | 536 | 6 |
| `MaskingEngineBenchmark.maskGraphWithPii` | 2 532 | 1 213 | 53 |
| `TextSanitizerBenchmark.sanitizeNoMatch` | 10 860 | 543 | 36 |
| `TextSanitizerBenchmark.sanitizeNoMatchWithDigits` | — | 3 435 | 299 |
| `TextSanitizerBenchmark.sanitizeLongNoMatch` | 328 557 | 17 272 | 57 |
| `TextSanitizerBenchmark.sanitizeWithIban` | 14 449 | 3 059 | 88 |
| `TextSanitizerBenchmark.sanitizeOversizedCapped` | — | 73 980 | 7 142 |
| `TextSanitizerBenchmark.sanitizeOversizedUncapped` | — | 571 221 | 31 294 |
| `JdbcProxyBenchmark.rawResultSetRow` | — | 3.6 | 0.07 |
| `JdbcProxyBenchmark.proxiedResultSetRow` | — | 65.8 | 3.9 |
| `JdbcProxyBenchmark.unwrappedResultSetRow` | — | 3.8 | 0.5 |
| `PlanCompilerBenchmark.compilePlansReflectively` | 30 521 | 30 230 | 2 477 |
| `PlanCompilerBenchmark.compilePlansFromGeneratedCode` | 9 198 | 8 826 | 10 524 |
| `PlanCompilerBenchmark.maskWithReflectivePlans` | 2 520 | 1 122 | 120 |
| `PlanCompilerBenchmark.maskWithGeneratedPlans` | 2 731 | 1 140 | 22 |

## What a reader should conclude

**A clean line through the masking appender costs about 0.55 µs, which is on the order of two
million lines per second per core.** It was 11 µs before the detector gates landed. The honest
headline is not a ratio against 2.3 ns — the sink does nothing, so that ratio only says "masking is
not free" — it is the absolute number and what it is made of.

**It used to be almost entirely the regex fan-out, and that is what changed.** `sanitizeNoMatch` was
10.9 µs of the 11.1 µs a clean logback line cost: 98% of a clean line was twelve detectors reading a
string that contains nothing. Each detector now declares a cheap necessary condition — an `@`, twelve
digits, six consecutive capitals — checked once against a one-pass summary of the text, and a pattern
that cannot match does not run. On a line of prose that takes eleven of the twelve off the path, and
the line costs 543 ns instead of 10 860.

**But quote 3.4 µs, not 543 ns, when someone asks what a real log line costs.**
`sanitizeNoMatchWithDigits` exists to keep this section honest: give a clean line an order number and
a timestamp — `order 8891273 accepted at 12:04:33 by node 7` — and the digits and colons open four of
the twelve gates, so it costs 3 435 ns. Still a third of what it was, and a long way from the number
the prose fixture produces. A filter measured only on prose flatters itself.

**Scanning is still per character, and now it is bounded.** 2 KB of clean text costs 17.3 µs, down
from 329 µs, because the gates apply to a long string exactly as they do to a short one. The tail
risk is capped rather than removed: `MaskingPolicy.maxTextLength` stops the scan at 8 192 characters
and redacts the rest, which on 64 KB is 74 µs against 571 µs uncapped.

**A line with PII costs about six times a line without** — 3 608 ns against 547 for logback. Before
the gates it was 14.8 µs against 11.1, a difference of a third, because the scan dominated both.
Now that finding nothing is cheap, the cost of a hit is visible for what it is: detection, then
pseudonymisation, then rebuilding the event.

**PII-free data no longer costs more than data with PII in it.** It used to, sharply — 12.5 µs for a
clean graph against 2.5 µs for an annotated one — because an annotated member is masked from its
declaration while an unannotated string was offered to every detector. That asymmetry is gone: the
two are 1 143 and 1 213 ns, inside each other's error bars. **Annotating is no longer a throughput
argument.** It is a correctness argument, which is what it always should have been.

**MDC and exceptions are still the expensive part of an event, because they multiply the scans.**
Three MDC entries plus an exception with a cause take a logback event from 547 ns to 8 829 — sixteen
times, and it is the shape a production error actually takes. Six strings scanned instead of one,
and the largest number here that is not a deliberate stress case.

**logback and log4j2 cost the same.** 547 ns against 594 on the clean line, and the same shape
everywhere else, which is what should happen: both integrations are thin, and the cost is the engine
underneath them.

**The JDBC result-set proxy is cheap, and the suspicion about it was wrong.** A ten-column row costs
3.6 ns unwrapped and 65.8 ns through the proxy — 18× against a stub that does nothing at all, which
is about 5.6 ns per forwarded call. A thousand-row fetch of ten columns therefore pays roughly 62 µs
in total, next to a real driver doing parsing, sockets and a network round trip.
`MaskingDataSource.withoutResultSetWrapping()` exists and works — `unwrappedResultSetRow` lands on
the raw figure at 3.8 ns — but the number says leave it shut, because opening it gives up the
sanitising of every error that surfaces during a fetch.

**`datamask-build-processor` pays off at startup, not in steady state.** Three types cost 30.2 µs to
plan by reflection and 8.8 µs from generated code — about three times cheaper, once per class per
process. Masking through warm plans is indistinguishable between the two (1 122 ns against 1 140,
inside the noise), which is expected: once a plan is compiled, the work is masking values, not
reaching members. So the processor's case is startup time and native-image compatibility, and it
should not be sold as a throughput feature. One caveat on the cold pair: its error bar is larger than
its own score, because most iterations measure a class that has already been planned. Trust the
ratio, not the digits.

## How the benchmarks are set up, and why

**Fork and iteration counts.** Two forks, five warmup and five measurement iterations of one second
each, declared on every benchmark class. Two forks rather than one because JIT compilation and
profile pollution differ between JVM launches and a single fork cannot see that; two is the smallest
number that can. Five one-second iterations because the paths here converge quickly — the warmup
traces in a longer run are flat by the second iteration — and because a set of twenty-two benchmarks
at higher settings stops being something anyone runs.

**A fixed, non-ephemeral secret.** `Fixtures.SECRET` is a constant. Deriving a key is HKDF work; it
happens once when the `DataMask` is built, which is in `@Setup`, and it must never appear in a
measured invocation. A constant also makes a `HASH` pseudonym the same string on every run, so two
runs measure the same work.

**Every state is `@State(Scope.Benchmark)`**, built once per trial, and every benchmark either
returns a value or writes to a `Blackhole`, so nothing here can be eliminated as dead code.

**Setup checks that each benchmark measures the path it claims.** A clean event has to come back as
the *same instance* — the short-circuit is the whole reason a clean line is cheap — and an event
carrying PII has to come back changed. `PlanCompilerBenchmark` additionally refuses to run if
`generatedPlanCount()` is zero, because `GeneratedMaskPlanCompiler` falls back to reflection
silently, and without that check a build with the processor missing would produce two arms measuring
the same thing and looking like a result.

## Deliberate non-goals and known biases, stated rather than hidden

- **Events are built once and reused.** What is measured is the appender path, not logback's event
  construction. A consequence is that the clean event's `formattedMessage` is cached before the
  first measured invocation, so the masking arm gets that formatting free. In production the encoder
  formats the message anyway, so the bias is small — but it is a bias, and it flatters masking.
- **The sink does nothing.** Every real appender — console, file, encoder — costs more than storing
  a reference, so the *relative* overhead of masking in a real pipeline is lower than the ratio here.
  The absolute microseconds are the number to plan with.
- **Single-threaded.** The engine and both maskers are thread-safe and hold no per-event state, so
  there is nothing here that would contend; a threads sweep would measure the JVM's regex
  implementation, not this library. `-t 4` is one flag away if that assumption ever needs testing.
- **No allocation numbers yet.** `-prof gc` works and is worth running when the clean path is
  optimised; it is not baked into the benchmark set, because the question right now is where the
  time goes and the answer is not allocation.
- **No JDBC benchmark.** Improvement item 7 asks for one, and it needs a `ResultSet` fixture rather
  than an in-memory object, which is a different kind of setup. It belongs here later.
