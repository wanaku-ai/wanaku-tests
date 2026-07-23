#!/bin/bash
set -euo pipefail

echo "## Test Results" >> "$GITHUB_STEP_SUMMARY"
echo "" >> "$GITHUB_STEP_SUMMARY"
echo "| Module | Tests | Passed | Failed | Errors | Skipped | Time |" >> "$GITHUB_STEP_SUMMARY"
echo "|--------|------:|-------:|-------:|-------:|--------:|-----:|" >> "$GITHUB_STEP_SUMMARY"

total_tests=0 total_fail=0 total_err=0 total_skip=0 total_time=0

# Extract test modules from parent POM (excludes test-common which has no tests)
modules=$(grep '<module>' pom.xml | sed 's|.*<module>\(.*\)</module>.*|\1|' | grep -v '^test-common$')

for module in $modules; do
  dir="${module}/target/failsafe-reports"
  if [ ! -d "$dir" ]; then
    echo "| ${module} | - | - | - | - | - | - |" >> "$GITHUB_STEP_SUMMARY"
    continue
  fi

  mod_tests=0 mod_fail=0 mod_err=0 mod_skip=0 mod_time=0
  for f in "$dir"/TEST-*.xml; do
    [ -f "$f" ] || continue
    tests=$(grep -oP 'tests="\K[0-9]+' "$f" | head -1)
    failures=$(grep -oP 'failures="\K[0-9]+' "$f" | head -1)
    errors=$(grep -oP 'errors="\K[0-9]+' "$f" | head -1)
    skipped=$(grep -oP 'skipped="\K[0-9]+' "$f" | head -1)
    time=$(grep -oP 'time="\K[0-9.]+' "$f" | head -1)
    mod_tests=$((mod_tests + ${tests:-0}))
    mod_fail=$((mod_fail + ${failures:-0}))
    mod_err=$((mod_err + ${errors:-0}))
    mod_skip=$((mod_skip + ${skipped:-0}))
    mod_time=$(echo "$mod_time + ${time:-0}" | bc)
  done

  passed=$((mod_tests - mod_fail - mod_err - mod_skip))
  echo "| ${module} | ${mod_tests} | ${passed} | ${mod_fail} | ${mod_err} | ${mod_skip} | ${mod_time}s |" >> "$GITHUB_STEP_SUMMARY"

  total_tests=$((total_tests + mod_tests))
  total_fail=$((total_fail + mod_fail))
  total_err=$((total_err + mod_err))
  total_skip=$((total_skip + mod_skip))
  total_time=$(echo "$total_time + $mod_time" | bc)
done

total_passed=$((total_tests - total_fail - total_err - total_skip))
echo "| **Total** | **${total_tests}** | **${total_passed}** | **${total_fail}** | **${total_err}** | **${total_skip}** | **${total_time}s** |" >> "$GITHUB_STEP_SUMMARY"

if [ "$total_fail" -gt 0 ] || [ "$total_err" -gt 0 ]; then
  echo "" >> "$GITHUB_STEP_SUMMARY"
  echo "### Failed Tests" >> "$GITHUB_STEP_SUMMARY"
  echo "" >> "$GITHUB_STEP_SUMMARY"
  for f in **/target/failsafe-reports/TEST-*.xml; do
    [ -f "$f" ] || continue
    grep -l 'failures="[1-9]\|errors="[1-9]' "$f" > /dev/null 2>&1 || continue
    classname=$(grep -oP 'name="\K[^"]+' "$f" | head -1)
    grep -oP '<testcase name="\K[^"]+' "$f" | while read -r tc; do
      block=$(sed -n "/<testcase name=\"${tc}\"/,/<\/testcase>/p" "$f")
      if echo "$block" | grep -q '<failure\|<error'; then
        echo "- \`${classname}#${tc}\`" >> "$GITHUB_STEP_SUMMARY"
        msg=$(echo "$block" | grep -oP '<failure message="\K[^"]*' | head -1)
        type=$(echo "$block" | grep -oP '<failure[^>]* type="\K[^"]*' | head -1)
        if [ -z "$msg" ]; then
          msg=$(echo "$block" | grep -oP '<error message="\K[^"]*' | head -1)
          type=$(echo "$block" | grep -oP '<error[^>]* type="\K[^"]*' | head -1)
        fi
        if [ -n "$msg" ]; then
          msg=$(echo "$msg" | sed 's/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g; s/&apos;/'"'"'/g')
          if [ ${#msg} -gt 300 ]; then
            msg="${msg:0:300}..."
          fi
          short_type=""
          if [ -n "$type" ]; then
            short_type=$(echo "$type" | sed 's/.*\.//')
            echo "  > **${short_type}**: ${msg}" >> "$GITHUB_STEP_SUMMARY"
          else
            echo "  > ${msg}" >> "$GITHUB_STEP_SUMMARY"
          fi
        fi
      fi
    done
  done
fi
