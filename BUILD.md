# Game Space (Alya AI Service) – cara membangun

Tanpa Gradle / Android Studio. Semua lewat `build.sh`.

1. Ekstrak arsip ini ke `/home/claude` (hasilnya `/home/claude/GameSpace`, `/home/claude/build.sh`, dst).
2. Pasang toolchain (sekali per sesi, `/opt` bisa direset): `./setup_toolchain.sh`
3. Bangun: `./build.sh all`  (tahap: shizuku, aidl, res, kotlin, dex, pack). Hasil: `/mnt/user-data/outputs/GameSpace-v<VNAME>.apk`
4. Tes JVM: `./build.sh kotlin` lalu `./runtests.sh T T2 T3 T4 T5 T6`
5. Versi: ubah `VCODE`/`VNAME` di `build.sh` DAN `versionCode`/`versionName` di `GameSpace/app/build.gradle.kts`.

## Kunci tanda tangan (PENTING)
APK yang diperbarui harus ditandatangani kunci yang SAMA dengan versi terpasang. Kunci disimpan permanen di
`/mnt/user-data/outputs/GameSpace-signing-key.jks` (alias `gs`, sandi `gamespace`); `build.sh` memulihkannya otomatis ke
`/opt/tc/gamespace.jks`. Jangan hapus file itu dan simpan juga salinan sendiri.

Sidik jari sertifikat (SHA-256):
- v1.0 - v1.6 (kunci lama, sudah hilang): b8ab581db8c6018e46643d5dff2b2204e24067254a53feb0f06318af379cd27d
- v1.7 dan seterusnya (kunci saat ini):  ccca159b32c701485acaad6784ed29f707cb468edfcaa25c8a549794f612f076
