#!/bin/sh
# Runs each test class from run_tests.sh on its own and records wall time, CPU time and peak memory.
# Usage (from the repo root, after build.sh): sh benchmark/time_tests.sh [OUT.tsv]
# Output: tab-separated: test, result, wall_s, user_cpu_s, sys_cpu_s, peak_rss_mb
set -e
OUT="${1:-benchmark/results/tests.tsv}"
mkdir -p "$(dirname "$OUT")"

LIBCP=""
for f in lib/*.jar; do [ -e "$f" ] && LIBCP="$LIBCP:$f"; done

# Compile exactly what run_tests.sh compiles (its javac block), quietly.
sed -n '/^javac /,/^$/p' run_tests.sh > /tmp/lynx_compile_tests.$$
sh /tmp/lynx_compile_tests.$$ >/dev/null 2>&1 || { echo "test compile failed"; rm -f /tmp/lynx_compile_tests.$$; exit 1; }
rm -f /tmp/lynx_compile_tests.$$

printf 'test\tresult\twall_s\tuser_cpu_s\tsys_cpu_s\tpeak_rss_mb\n' > "$OUT"
for t in $(grep -oE 'java -cp "bin\$LIBCP" [A-Za-z]+Test' run_tests.sh | awk '{print $NF}'); do
  if /usr/bin/time -f '%e\t%U\t%S\t%M' -o /tmp/lynx_time.$$ java -cp "bin$LIBCP" "$t" >/dev/null 2>&1; then r=PASS; else r=FAIL; fi
  awk -v t="$t" -v r="$r" -F'\t' 'END{printf "%s\t%s\t%s\t%s\t%s\t%d\n", t, r, $1, $2, $3, $4/1024}' /tmp/lynx_time.$$ >> "$OUT"
  tail -1 "$OUT"
done
rm -f /tmp/lynx_time.$$
