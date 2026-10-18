#!/usr/bin/env python3
"""Fail CI on unsuccessful instrumentation XML, including silently skipped Couch tests."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

COUCH_SUITE = "com.ethran.notable.sync.couch.CouchEndToEndTest"


def check_reports(directory: Path) -> list[str]:
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        return [f"No instrumentation XML reports found in {directory}"]
    problems = []
    couch_cases = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            problems.append(f"{report}: unreadable report: {error}")
            continue
        if root.tag not in ("testsuite", "testsuites"):
            problems.append(f"{report}: unexpected report root {root.tag}")
            continue
        # Check both aggregate counters and actual result elements: neither alone is
        # sufficient when a runner produces incomplete or inconsistent reports.
        for node in root.iter():
            if node.tag in ("failure", "error"):
                problems.append(f"{report}: contains <{node.tag}>")
            if node.tag in ("testsuite", "testsuites"):
                counters = ["failures", "errors"]
                if node.get("name") == COUCH_SUITE:
                    counters += ["skipped", "disabled"]
                for counter in counters:
                    value = node.get(counter, "0")
                    try:
                        count = int(value)
                    except ValueError:
                        count = -1
                    if count != 0:
                        problems.append(f"{report}: {node.get('name', node.tag)} {counter}={value}")
        # Handle both class-per-suite and mixed-class suite formats. Inspect every
        # matching case, so one successful Couch test cannot conceal skipped peers.
        for suite in root.iter("testsuite"):
            for case in suite.findall("testcase"):
                if case.get("classname", suite.get("name")) != COUCH_SUITE:
                    continue
                couch_cases += 1
                if any(child.tag in ("skipped", "failure", "error") for child in case):
                    problems.append(f"{report}: Couch test did not pass: {case.get('name')}")
                if case.get("status", "").lower() in ("notrun", "skipped", "disabled"):
                    problems.append(f"{report}: Couch test did not run: {case.get('name')}")
    if not couch_cases:
        problems.append(f"Required suite {COUCH_SUITE} has no test cases")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    problems = check_reports(args.directory)
    if problems:
        for problem in problems:
            print(f"::error::{problem}", file=sys.stderr)
        return 1
    print("Instrumentation reports passed; CouchEndToEndTest executed without skips.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
