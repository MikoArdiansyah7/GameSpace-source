#!/bin/bash
# Membangun ulang toolchain di /opt/tc (versi persis yang dipakai untuk GameSpace). Jalankan sebagai root, di FOREGROUND.
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq || true
apt-get install -y -qq openjdk-21-jdk-headless aidl apksigner zipalign dalvik-exchange libandroid-json-java
mkdir -p /opt/tc && cd /opt/tc
curl -sL -o sdk-tools.zip https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-x86_64.zip   # 14196861 byte
unzip -q -o sdk-tools.zip -d sdk
curl -sL -o kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip                       # 85822011 byte
unzip -q -o kotlin.zip -d kt
rm -rf /tmp/sap; git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms /tmp/sap
(cd /tmp/sap && git sparse-checkout set android-36)
cp /tmp/sap/android-36/android.jar /opt/tc/android-36.jar                                                                              # 27768026 byte
rm -rf shz && git clone -q https://github.com/RikkaApps/Shizuku-API shz && (cd shz && git checkout -q a27f6e4)
echo "toolchain siap"
