#!/usr/bin/env python3
"""Write the aggregated JaCoCo totals into the GitHub Actions run summary.

The verdict is not repeated here: the 80% gate lives in build.gradle and Gradle already
fails the build with the exact rule it violated. This only makes the numbers readable
without downloading the HTML report, including on a run that failed the gate.
"""

import os
import sys
import xml.etree.ElementTree as ElementTree

REPORT = "build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml"

# Ordered as a reader wants them, not as JaCoCo emits them.
COUNTERS = ["INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS", "COMPLEXITY"]


def totals(report):
    """Bundle-level counters, which are the report root's direct children."""
    return {
        counter.get("type"): (int(counter.get("covered")), int(counter.get("missed")))
        for counter in report.findall("counter")
    }


def main():
    if not os.path.exists(REPORT):
        # A build that failed before the report was written has nothing to summarise, and
        # saying so is more useful than failing the step on top of the real failure.
        print(f"No coverage report at {REPORT}; skipping the summary.")
        return 0

    counters = totals(ElementTree.parse(REPORT).getroot())

    lines = ["### Coverage", "", "| Counter | Covered | Missed | Ratio |", "|---|---:|---:|---:|"]
    for name in COUNTERS:
        if name not in counters:
            continue
        covered, missed = counters[name]
        total = covered + missed
        ratio = f"{covered / total * 100:.1f}%" if total else "n/a"
        lines.append(f"| {name.capitalize()} | {covered} | {missed} | {ratio} |")

    summary = "\n".join(lines) + "\n"
    print(summary)

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as handle:
            handle.write(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
