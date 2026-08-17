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
| `TextSanitizerBenchmark.sanitizeNoMatch` | The regex fan-out on a normal log line that matches nothing — the cost the improvements document calls out as unfiltered |
| `TextSanitizerBenchmark.sanitizeLongNoMatch` | The same answer on ~2 KB, which says whether that cost is per-call or per-character |
| `TextSanitizerBenchmark.sanitizeWithIban` | One match in the same short line: detection, masking, a rebuilt string |
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

| Benchmark | ns/op | ± |
|---|---:|---:|
| `LogbackAppenderBenchmark.plainAppenderCleanLine` | 2.6 | 0.1 |
| `LogbackAppenderBenchmark.maskingAppenderCleanLine` | 11 081 | 330 |
| `LogbackAppenderBenchmark.maskingAppenderIbanLine` | 14 819 | 450 |
| `LogbackAppenderBenchmark.maskingAppenderCardLine` | 14 089 | 1 903 |
| `LogbackAppenderBenchmark.plainAppenderMdcAndException` | 2.3 | 0.03 |
| `LogbackAppenderBenchmark.maskingAppenderMdcAndException` | 48 485 | 314 |
| `Log4j2RewriteBenchmark.identityRewriteCleanLine` | 0.43 | 0.02 |
| `Log4j2RewriteBenchmark.maskingRewriteCleanLine` | 12 194 | 1 438 |
| `Log4j2RewriteBenchmark.maskingRewriteIbanLine` | 15 513 | 1 975 |
| `Log4j2RewriteBenchmark.maskingRewriteCardLine` | 14 105 | 1 469 |
| `Log4j2RewriteBenchmark.identityRewriteContextAndException` | 0.43 | 0.01 |
| `Log4j2RewriteBenchmark.maskingRewriteContextAndException` | 53 235 | 4 267 |
| `MaskingEngineBenchmark.maskCleanGraph` | 12 466 | 104 |
| `MaskingEngineBenchmark.maskCleanGraphWithoutTextScan` | 620 | 2 |
| `MaskingEngineBenchmark.maskGraphWithPii` | 2 532 | 130 |
| `TextSanitizerBenchmark.sanitizeNoMatch` | 10 860 | 142 |
| `TextSanitizerBenchmark.sanitizeLongNoMatch` | 328 557 | 22 055 |
| `TextSanitizerBenchmark.sanitizeWithIban` | 14 449 | 335 |
| `PlanCompilerBenchmark.compilePlansReflectively` | 30 521 | 2 610 |
| `PlanCompilerBenchmark.compilePlansFromGeneratedCode` | 9 198 | 9 969 |
| `PlanCompilerBenchmark.maskWithReflectivePlans` | 2 520 | 130 |
| `PlanCompilerBenchmark.maskWithGeneratedPlans` | 2 731 | 657 |

## What a reader should conclude

**A clean line through the masking appender costs about 11 µs, which is roughly 90 000 lines per
second per core.** That is enough for an ordinary service and it is not enough to put in front of an
unbounded log volume without thinking about it. The honest headline is not a ratio against 2.6 ns —
the sink does nothing, so that ratio only says "masking is not free" — it is the absolute number and
what it is made of.

**Almost all of it is the regex fan-out, and none of it is the engine.**
`TextSanitizerBenchmark.sanitizeNoMatch` is 10.9 µs of the 11.1 µs a clean logback line costs: 98%
of a clean line is twelve detectors reading a string that contains nothing. The same shows up on the
engine side, where switching content scanning off takes a clean object graph from 12.5 µs to 620 ns —
a factor of twenty. A `-prof stack` run puts essentially all of the time in
`java.util.regex.Pattern`, mostly in character-class predicates. **Improvement item 1 in
`docs/IMPROVEMENTS.md` — a single-pass character-class gate before the detector set — is not a
micro-optimisation. It is the difference between this module being cheap and this module being the
dominant cost of logging.**

**Scanning is per character, so a long line is a long scan.** 70 characters cost 10.9 µs and 2 000
characters cost 329 µs, both about 160 ns per character. A `maxTextLength` on `MaskingPolicy`
(improvement item 6) has a real number behind it, and the pre-filter above would move this cost too.

**A line with PII costs barely more than a line without.** 14.8 µs against 11.1 µs: detecting an
IBAN, pseudonymising it and rebuilding the event adds about 3.7 µs on top of a scan that had to
happen anyway. Masking is not what is expensive here — looking is.

**The same holds, more sharply, for object graphs: PII-free data costs *more* than data with PII in
it.** 12.5 µs for the clean graph against 2.5 µs for the annotated one. Nothing is wrong with that
number: an annotated member is masked from its declaration and never scanned, while an unannotated
string has to be offered to every detector. A domain that declares what its fields are is on the
fast path; one that relies on content detection is not.

**MDC and exceptions are the expensive part of an event, because they multiply the scans.** Three
MDC entries plus an exception with a cause take a logback event from 11 µs to 48 µs — six strings
scanned instead of one. Anything that trims the fan-out helps here four times over.

**logback and log4j2 cost the same.** 11.1 µs against 12.2 µs on the clean line, and the same shape
everywhere else, which is what should happen: both integrations are thin, and the cost is the engine
underneath them.

**`datamask-build-processor` pays off at startup, not in steady state.** Three types cost 30.5 µs to
plan by reflection and 9.2 µs from generated code — about three times cheaper, once per class per
process. Masking through warm plans is indistinguishable between the two (2.5 µs against 2.7 µs,
inside the noise), which is expected: once a plan is compiled, the work is masking values, not
reaching members. So the processor's case is startup time and native-image compatibility, and it
should not be sold as a throughput feature.

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
