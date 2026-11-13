import com.alya.gamespace.*
import java.io.File

var failures = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) failures++
}

fun main() {
    val A = "/home/claude/GameSpace/app/src/main/assets/"
    // ── 1. sesi shell persisten
    val a = Sh.exec("echo hello; echo err 1>&2; for i in 1 2 3; do echo n\$i; done")
    check("sesi: stdout+stderr tergabung, multi-baris", a.contains("hello") && a.contains("err") && a.contains("n3"), a)
    Sh.exec("ZZ=42")
    check("sesi: satu proses hidup terus (variabel bertahan)", Sh.exec("echo \$ZZ").trim() == "42")
    val t0 = System.currentTimeMillis()
    val c = Sh.exec("sleep 3; echo late", 800)
    check("sesi: timeout mengembalikan kosong < 2 dtk", c.isEmpty() && System.currentTimeMillis() - t0 < 2000, "took ${System.currentTimeMillis() - t0}")
    check("sesi: pulih otomatis setelah timeout", Sh.exec("echo after").trim() == "after")
    check("sesi: output tanpa newline di akhir tidak merusak penanda", Sh.exec("printf abc").contains("abc"))

    // ── 2. skrip asset: sintaks + format keluaran
    for (f in listOf("discover.sh", "stats.sh", "boost.sh")) {
        val p = ProcessBuilder("sh", "-n", A + f).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText(); p.waitFor()
        check("sintaks $f", p.exitValue() == 0, o)
    }
    val disc = Sh.exec(File(A + "discover.sh").readText(), 20000)
    check("discover.sh jalan sampai akhir", disc.contains("uid=") && disc.contains("end=1"), disc.take(200))
    val st = Sh.exec(File(A + "stats.sh").readText().replace("@GFREQ@", "/dev/null").replace("@GLOAD@", "/dev/null").replace("@CTEMP@", "/dev/null"), 6000)
    check("stats.sh menghasilkan semua seksi", listOf("@C", "@S", "@GF", "@GL", "@T", "@E").all { st.contains(it) }, st.take(200))
    val parsedLocal = Stats.parse(st, Hw.parse(disc))
    println("      (lokal sandbox) cpuMax=${parsedLocal.cpuMax} load=${parsedLocal.load}")

    // ── 3. Hw.parse: contoh MediaTek (gpufreq v2) seperti perangkat pengguna
    val mtk = """
uid=2000
model=X6873
brand=Infinix
plat=mt6878
soc=MT6878
sdk=36
rel=16
cpu=/sys/devices/system/cpu/cpufreq/policy0|2000000|500000|500000|2000000|walt|0|0|500000,600000,2000000,
cpu=/sys/devices/system/cpu/cpufreq/policy4|2500000|700000|700000|2500000|walt|1|0|700000,2500000,
gfreq=/sys/kernel/ged/hal/current_freqency
gload=/sys/kernel/ged/hal/gpu_utilization
gkind=mtk2
gopp=[00] freq: 1150000, volt: 825000, vsram: 825000;[01] freq: 1000000, volt: 775000;[02] freq: 800000, volt: 700000;
gw=0
ged=0
tz=/sys/class/thermal/thermal_zone0|mtktsbattery
tz=/sys/class/thermal/thermal_zone1|mtktscpu
end=1
""".trimIndent()
    val h = Hw.parse(mtk)
    check("parse: uid/model/platform", h.uid == 2000 && h.model == "X6873" && h.plat == "mt6878" && h.sdk == 36)
    check("parse: 2 cluster CPU, flag canMin benar", h.cpus.size == 2 && !h.cpus[0].canMin && h.cpus[1].canMin && h.cpus[1].maxKhz == 2500000L)
    check("parse: OPP MediaTek -> frekuensi naik & index", h.gFreqs == listOf(800000L, 1000000L, 1150000L) && h.oppIdx == listOf(0, 1, 2) && h.oppKhz[0] == 1150000L, h.gFreqs.toString())
    check("parse: node suhu CPU dipilih dari tipe 'cpu'", h.cpuTempNode == "/sys/class/thermal/thermal_zone1/temp", h.cpuTempNode)
    check("parse: gCanLock=false (sysfs read-only)", !h.gCanLock && h.gKind == "mtk2")

    val kg = Hw.parse("gkind=kgsl\ngdev=/sys/class/kgsl/kgsl-3d0\ngfreqs=1100000000,900000000,700000000\ngw=1\nsu=1\ncpu=/x/policy0|1|1|1|1|a|1|1|1,\n")
    check("parse: kgsl Hz -> kHz", kg.gFreqs == listOf(700000L, 900000L, 1100000L) && kg.gCanLock && kg.hasSu, kg.gFreqs.toString())
    check("toKhz: Hz/kHz/MHz", Hw.toKhz(1612000000L) == 1612000L && Hw.toKhz(850000L) == 850000L && Hw.toKhz(850L) == 850000L)

    // ── 4. Stats.parse (beban CPU dari delta /proc/stat, GPU, suhu)
    Stats.reset()
    val hp = Hw.parse("gload=/sys/class/kgsl/kgsl-3d0/gpubusy\n")
    val s1 = Stats.parse("@C\n1800000\n2400000\n@S\ncpu  1000 0 500 8000 100 0 0 0 0 0\n@GF\n850000\n@GL\n45 100\n@T\n41000\n@E\n", hp)
    check("stats#1: CPU max 2.4GHz, GPU 850MHz, beban GPU 45%, suhu 41°", s1.cpuMax == 2400000L && s1.gKhz == 850000L && s1.gLoad == 45 && s1.cTemp == 41f && s1.load == -1, "${s1.cpuMax} ${s1.gKhz} ${s1.gLoad} ${s1.cTemp} ${s1.load}")
    val s2 = Stats.parse("@C\n2000000\n@S\ncpu  1500 0 700 8500 100 0 0 0 0 0\n@E\n", hp)
    // delta total = 1500+700+8500+100 - (1000+500+8000+100) = 10800-9600=1200 ; idle delta = 8600-8100=500 -> beban 58%
    check("stats#2: beban CPU dari delta = 58%", s2.load == 58, "load=${s2.load}")
    val s3 = Stats.parse("@GL\n57 0 0\n", Hw.parse("gload=/sys/kernel/ged/hal/gpu_utilization\n"))
    check("stats: format GED 'util 3d ta' -> 57%", s3.gLoad == 57, "${s3.gLoad}")
    val s4 = Stats.parse("@GF\n3 850000\n@T\n38\n", hp)
    check("stats: baris 'idx freq' ambil frekuensi; suhu langsung derajat", s4.gKhz == 850000L && s4.cTemp == 38f)

    // ── 5. Records.stats (rumus sama dengan laporan referensi)
    val fps = floatArrayOf(60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f, 40f, 20f)
    val r = Rec("1", "g", "Game", 0L, 10, "p", "m", 36, 3, fps, floatArrayOf(34f, 35f, 36f, 35.2f, 0f, 0f, 0f, 0f, 0f, 0f), floatArrayOf(5f, 5.04f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val x = Records.stats(r)
    check("rekaman: maks/min/rata-rata", x.max == 60f && x.min == 20f && Math.abs(x.avg - 54f) < 0.01f, "${x.max} ${x.min} ${x.avg}")
    check("rekaman: kehalusan >=45FPS = 80%", Math.abs(x.smooth - 80f) < 0.01f, "${x.smooth}")
    check("rekaman: 5% rendah = frame terendah (20)", Math.abs(x.low5 - 20f) < 0.01f, "${x.low5}")
    check("rekaman: varians populasi = 164", Math.abs(x.variance - 164f) < 0.01f, "${x.variance}")
    check("rekaman: suhu maks 36 & daya rata-rata ~5.02W", x.maxTemp == 36f && Math.abs(x.avgW - 5.02f) < 0.01f, "${x.maxTemp} ${x.avgW}")
    val e = Records.stats(Rec("2", "g", "G", 0L, 0, "", "", 0, 1, FloatArray(0), FloatArray(0), FloatArray(0)))
    check("rekaman kosong tidak crash", e.avg == 0f && e.max == 0f)

    println(if (failures == 0) "\nSEMUA TES LULUS" else "\n$failures TES GAGAL")
    System.exit(if (failures == 0) 0 else 1)
}
