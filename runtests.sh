#!/bin/bash
# pemakaian: runtests.sh T T2 T3 ...
J=/usr/share/java/com.android.json.jar; AJ=/opt/tc/android-36.jar; STD=/opt/tc/kt/kotlinc/lib/kotlin-stdlib.jar; W=/home/claude/build
CP=$J:$W/kt:$W/shz:$W/rcls:$W/aidlcls:$STD:$AJ
cd /home/claude/test && export JAVA_OPTS="-Xmx1500m"
for t in "$@"; do
  rm -rf out_$t
  errs=$(/opt/tc/kt/kotlinc/bin/kotlinc -no-stdlib -no-reflect -nowarn -cp $CP -d out_$t $t.kt 2>&1 | grep -E "error:" | head -3)
  if [ -n "$errs" ]; then echo "$t: KOMPILASI GAGAL"; echo "$errs"; continue; fi
  out=$(java -cp out_$t:$CP ${t}Kt 2>&1)
  p=$(echo "$out" | grep -c "^PASS"); f=$(echo "$out" | grep -c -E "^FAIL|GAGAL")
  echo "$t: PASS=$p FAIL=$f | $(echo "$out" | grep -E "LULUS|GAGAL" | tail -1)"
  echo "$out" | grep -E "^FAIL" | head -5
done
