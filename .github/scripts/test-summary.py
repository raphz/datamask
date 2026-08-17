#!/usr/bin/env python3
"""Write the JUnit results into the GitHub Actions run summary.

Gradle already failed the build if a test failed, so no verdict is repeated here. What this
adds is the part a reader otherwise has to download an artifact for: which module, which
test, and what it actually asserted. On a red run that is the whole point — the failure
detail goes into the run summary, so a reviewer reads it in the browser instead of
reproducing it locally.

Reads every `*/build/test-results/*/*.xml` rather than only the `test` suite, so a module
that adds an integration-test suite later is picked up without editing this.

One thing to be deliberate about in this repository: an assertion message goes into the run
summary verbatim, and the run summary of a public repository is public. Many tests here
assert `.doesNotContain(rawValue)`, so a failure prints the fixture it was hiding. That is
fine because fixtures are invented — `CH9300762011623852957` is the IBAN from the ISO
examples — and it is the reason a fixture must never be a real value copied out of an
environment. If that rule is ever broken, this file is one of the places it escapes through.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ElementTree

RESULTS = "*/build/test-results/*/*.xml"

# Enough to identify the failure and usually enough to fix it. The full trace is in the
# artifact; pasting all of it here buries the message that matters under framework frames.
TRACE_LINES = 12


def module_of(path):
    """The Gradle module a result file belongs to — the first path segment."""
    return path.split(os.sep, 1)[0]


def failures_in(suite):
    """Every failed or errored case in one suite, as (classname, name, kind, detail)."""
    for case in suite.findall("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is None:
                continue
            message = (node.get("message") or "").strip()
            trace = (node.text or "").strip()
            detail = message or trace.split("\n", 1)[0]
            yield case.get("classname", "?"), case.get("name", "?"), kind, detail, trace


def main():
    files = sorted(glob.glob(RESULTS))
    if not files:
        # A build that failed before any test ran has nothing to report, and saying so is
        # more useful than failing this step on top of the real failure.
        print(f"No JUnit results matching {RESULTS}; skipping the summary.")
        return 0

    per_module = {}
    failed = []
    for path in files:
        try:
            root = ElementTree.parse(path).getroot()
        except ElementTree.ParseError as unreadable:
            # A truncated result file means the JVM died mid-suite. Worth saying, not worth
            # failing the reporting step for.
            print(f"Could not parse {path}: {unreadable}")
            continue

        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            counts = per_module.setdefault(module_of(path), [0, 0, 0, 0.0])
            counts[0] += int(suite.get("tests", 0))
            counts[1] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            counts[2] += int(suite.get("skipped", 0))
            counts[3] += float(suite.get("time", 0) or 0)
            failed.extend(failures_in(suite))

    tests = sum(counts[0] for counts in per_module.values())
    bad = sum(counts[1] for counts in per_module.values())
    skipped = sum(counts[2] for counts in per_module.values())
    seconds = sum(counts[3] for counts in per_module.values())

    verdict = f"{bad} failed" if bad else "all green"
    lines = [
        f"### Tests — {tests} run, {verdict}",
        "",
        f"{tests} tests across {len(per_module)} modules in {seconds:.1f}s"
        + (f", {skipped} skipped" if skipped else "")
        + ".",
        "",
        "| Module | Tests | Failed | Skipped | Time |",
        "|---|---:|---:|---:|---:|",
    ]
    # Failing modules first: on a red run that is what the reader came for.
    for module in sorted(per_module, key=lambda m: (-per_module[m][1], m)):
        count, wrong, skip, time = per_module[module]
        lines.append(f"| `{module}` | {count} | {wrong or ''} | {skip or ''} | {time:.1f}s |")

    if failed:
        lines += ["", f"### Failures ({len(failed)})", ""]
        for classname, name, kind, detail, trace in failed:
            lines += [
                f"<details><summary><code>{classname}</code> — {name} ({kind})</summary>",
                "",
                "```",
                detail or "(no message)",
                "",
                "\n".join(trace.split("\n")[:TRACE_LINES]),
                "```",
                "",
                "</details>",
                "",
            ]

    summary = "\n".join(lines) + "\n"
    print(summary)

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as handle:
            handle.write(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
