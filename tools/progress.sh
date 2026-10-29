#!/bin/bash
# Summarize the survival test log: milestones + survival state changes (deduped), last N lines.
L="$TEMP/hatest/gametest.log"
N=${1:-40}
grep -a -E "\[survival\]|MILESTONE|Stuck before|error:|BUILD|PASSED|AssertionError" "$L" | tr -d '\r' \
 | sed -E 's/^\[[0-9:]+\] \[[^]]+\] //; s/\(humanautopilot\) \[survival\] //; s/\(survival-test\) //' \
 | grep -v -E "Reloading|object-builder" | uniq | tail -n "$N"
