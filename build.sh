#!/bin/bash
# Build manual APK Game Space (tanpa Gradle): aapt2 + kotlinc + dx + zipalign + apksigner
set -e
P=/home/claude/GameSpace
W=/home/claude/build
T=/opt/tc
AJ=$T/android-36.jar
AAPT2=$T/sdk/build-tools/aapt2
AIDL=$T/sdk/build-tools/aidl
KOTLINC=$T/kt/kotlinc/bin/kotlinc
STD=$T/kt/kotlinc/lib/kotlin-stdlib.jar
DXJAR=/usr/lib/android-sdk/build-tools/debian/lib/dx.jar
SHZ=$T/shz
VCODE=8
VNAME=1.7
OUT=/mnt/user-data/outputs/GameSpace-v$VNAME.apk
stage=${1:-all}

stage_shizuku() {
  echo "### [1] Shizuku API (aidl + javac)"
  rm -rf $W/stubs $W/aidlgen $W/shz; mkdir -p $W/stubs/androidx/annotation $W/aidlgen $W/shz
  S=$W/stubs/androidx/annotation
  cat > $S/NonNull.java <<'J'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD,ElementType.PARAMETER,ElementType.FIELD,ElementType.LOCAL_VARIABLE,ElementType.ANNOTATION_TYPE,ElementType.PACKAGE})
public @interface NonNull {}
J
  sed 's/NonNull/Nullable/' $S/NonNull.java > $S/Nullable.java
  cat > $S/RestrictTo.java <<'J'
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({ElementType.ANNOTATION_TYPE,ElementType.TYPE,ElementType.METHOD,ElementType.CONSTRUCTOR,ElementType.FIELD,ElementType.PACKAGE})
public @interface RestrictTo { Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES } }
J
  cat > $W/framework.aidl <<'J'
parcelable android.os.Bundle;
parcelable android.os.ParcelFileDescriptor;
parcelable android.content.Intent;
J
  for f in $(find $SHZ/aidl -name '*.aidl'); do
    $AIDL -p$W/framework.aidl -I$SHZ/aidl/src/main/aidl -o$W/aidlgen $f
  done
  rm -rf $W/shzsrc; mkdir -p $W/shzsrc
  cp -r $SHZ/shared/src/main $W/shzsrc/shared; cp -r $SHZ/api/src/main $W/shzsrc/api; cp -r $SHZ/provider/src/main $W/shzsrc/provider
  python3 /home/claude/patch_shizuku.py $W/shzsrc
  javac -nowarn -Xlint:-options --release 8 -cp $AJ -d $W/shz \
    $(find $W/stubs $W/aidlgen $W/shzsrc -name '*.java')
  rm -rf $W/shz/androidx
  echo "shizuku classes: $(find $W/shz -name '*.class' | wc -l)"
}

stage_aidl() {
  echo "### [1b] AIDL aplikasi"
  rm -rf $W/aidlapp $W/aidlcls; mkdir -p $W/aidlapp $W/aidlcls
  for f in $(find $P/app/src/main/aidl -name '*.aidl'); do $AIDL -I$P/app/src/main/aidl -o$W/aidlapp $f; done
  javac -nowarn -Xlint:-options --release 8 -cp $AJ -d $W/aidlcls $(find $W/aidlapp -name '*.java')
  echo "aidl classes: $(find $W/aidlcls -name '*.class' | wc -l)"
}

stage_res() {
  echo "### [2] Resource (aapt2)"
  rm -rf $W/gen $W/rcls $W/res.zip $W/base.apk $W/AndroidManifest.xml; mkdir -p $W/gen $W/rcls
  $AAPT2 compile --dir $P/app/src/main/res -o $W/res.zip
  sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.alya.gamespace"|' \
    $P/app/src/main/AndroidManifest.xml > $W/AndroidManifest.xml
  $AAPT2 link -o $W/base.apk -I $AJ --manifest $W/AndroidManifest.xml -R $W/res.zip --java $W/gen \
    -A $P/app/src/main/assets --min-sdk-version 26 --target-sdk-version 36 --version-code $VCODE --version-name $VNAME --auto-add-overlay
  javac -nowarn -Xlint:-options --release 8 -cp $AJ -d $W/rcls $(find $W/gen -name R.java)
  echo "R classes: $(find $W/rcls -name '*.class' | wc -l)"
}

stage_kotlin() {
  echo "### [3] Kotlin compile"
  rm -rf $W/kt; mkdir -p $W/kt
  export JAVA_OPTS="-Xmx1800m"
  $KOTLINC -no-jdk -no-stdlib -no-reflect -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
    -cp $AJ:$W/shz:$W/rcls:$W/aidlcls:$STD -d $W/kt $P/app/src/main/java 2>&1 | grep -v '^warning:' || true
  echo "kotlin classes: $(find $W/kt -name '*.class' | wc -l)"
  [ "$(find $W/kt -name '*.class' | wc -l)" -gt 0 ]
}

stage_dex() {
  echo "### [4] Dex (dx)"
  rm -rf $W/stdlib $W/dex; mkdir -p $W/stdlib $W/dex
  (cd $W/stdlib && unzip -q -o $STD && rm -rf META-INF module-info.class)
  java -Xmx2g -jar $DXJAR --dex --min-sdk-version=26 --output=$W/dex/classes.dex $W/kt $W/shz $W/rcls $W/aidlcls $W/stdlib
  ls -la $W/dex
}

stage_pack() {
  echo "### [5] Package + zipalign + sign"
  # pengaman: pastikan tahap resource sudah dijalankan ulang untuk versi ini
  local bv; bv=$($AAPT2 dump badging $W/base.apk | grep -m1 "^package:")
  case "$bv" in *"versionCode='$VCODE'"*"versionName='$VNAME'"*) ;; *) echo "GAGAL: base.apk bukan versi $VNAME ($bv). Jalankan: ./build.sh res atau all"; exit 1;; esac
  rm -f $W/app-unsigned.apk $W/app-aligned.apk
  cp $W/base.apk $W/app-unsigned.apk
  (cd $W/dex && zip -q -X $W/app-unsigned.apk classes.dex)
  $T/sdk/build-tools/zipalign -f -p 4 $W/app-unsigned.apk $W/app-aligned.apk
  # Kunci tanda tangan HARUS sama di semua versi (kalau beda, Android menolak pembaruan di atas versi lama).
  # /opt dan /home bisa direset antar sesi, jadi salinan permanen disimpan di outputs dan dipulihkan otomatis di sini.
  KEYSAVE=/mnt/user-data/outputs/GameSpace-signing-key.jks
  [ -f $T/gamespace.jks ] || { [ -f $KEYSAVE ] && cp $KEYSAVE $T/gamespace.jks && echo "kunci tanda tangan dipulihkan dari $KEYSAVE"; }
  [ -f $T/gamespace.jks ] || { echo "PERINGATAN: membuat kunci tanda tangan BARU. APK ini tidak bisa menimpa versi lama yang memakai kunci berbeda."
    keytool -genkeypair -keystore $T/gamespace.jks -storepass gamespace -keypass gamespace \
      -alias gs -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Game Space, O=Alya AI"; }
  mkdir -p /mnt/user-data/outputs
  [ -f $KEYSAVE ] || cp $T/gamespace.jks $KEYSAVE
  apksigner sign --ks $T/gamespace.jks --ks-pass pass:gamespace --key-pass pass:gamespace \
    --out $OUT $W/app-aligned.apk
  apksigner verify -v --print-certs $OUT | head -12
  ls -la $OUT
}

case $stage in
  shizuku) stage_shizuku;; aidl) stage_aidl;; res) stage_res;; kotlin) stage_kotlin;; dex) stage_dex;; pack) stage_pack;;
  all) stage_shizuku; stage_aidl; stage_res; stage_kotlin; stage_dex; stage_pack;;
esac
