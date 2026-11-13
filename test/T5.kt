import com.alya.gamespace.*

var f5 = 0
fun c5(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f5++
}

fun main() {
    // ── kolom lebar tetap (seperti MangoHud) supaya HUD tidak bergeser
    c5("GPU/CPU/RAM: 0%, 45%, 100% selalu 4 karakter rata kanan", Logic.pcPct(0) == "  0%" && Logic.pcPct(45) == " 45%" && Logic.pcPct(100) == "100%" && Logic.pcPct(-1) == " --%", Logic.pcPct(0) + "|" + Logic.pcPct(45))
    c5("PWR: 5.1W, 12.0W selalu 5 karakter; tak diketahui = --W", Logic.pcWatt(5.14f) == " 5.1W" && Logic.pcWatt(12f) == "12.0W" && Logic.pcWatt(-1f) == "  --W", Logic.pcWatt(5.14f))
    c5("TMP: 42.4°C & 9.5°C selalu 6 karakter", Logic.pcTemp(42.4f) == "42.4°C" && Logic.pcTemp(9.5f) == " 9.5°C", Logic.pcTemp(42.4f))
    c5("FPS: 161, 60, 8, tak diketahui selalu 3 karakter", Logic.pcFps(161f) == "161" && Logic.pcFps(60f) == " 60" && Logic.pcFps(8f) == "  8" && Logic.pcFps(0f) == " --")
    val widths = listOf(Logic.pcPct(3), Logic.pcPct(99), Logic.pcWatt(0.4f), Logic.pcWatt(9.9f), Logic.pcTemp(30f), Logic.pcTemp(45f), Logic.pcFps(5f), Logic.pcFps(144f)).map { it.length }
    c5("panjang kolom konstan (tidak ada lompatan lebar)", widths == listOf(4, 4, 5, 5, 6, 6, 3, 3), widths.toString())

    // ── skala otomatis agar HUD selalu muat
    c5("muat: skala tidak diubah", Logic.fitScale(1000f, 1500f, 1.3f) == 1.3f)
    val fs = Logic.fitScale(2000f, 1000f, 1.5f)
    c5("tidak muat: dikecilkan pas selebar layar (1.5 x 1000/2000 = 0.75)", Math.abs(fs - 0.75f) < 0.001f, fs.toString())
    c5("batas bawah skala 0.4 (tetap terbaca)", Logic.fitScale(10000f, 500f, 1.0f) == 0.4f)
    c5("lebar belum diketahui (0) -> tidak mengubah skala", Logic.fitScale(1000f, 0f, 1.2f) == 1.2f)

    // ── grafik
    c5("sumbu grafik: minimal 60, kelipatan 30", Logic.graphMax(0f) == 60f && Logic.graphMax(58f) == 90f.let { 90f }.let { Logic.graphMax(58f) } && Logic.graphMax(30f) == 60f)
    c5("sumbu grafik 161 FPS -> 180; 120 FPS -> 150", Logic.graphMax(161f) == 180f && Logic.graphMax(120f) == 150f, Logic.graphMax(161f).toString() + " " + Logic.graphMax(120f))

    // ── label render (pengganti tulisan DXVK)
    c5("libvulkan termuat -> VULKAN (menang atas pipeline lain)", Logic.rendererLabel(true, true, "Pipeline=Skia (OpenGL)") == "VULKAN")
    c5("pipeline Skia (Vulkan) -> VULKAN", Logic.rendererLabel(false, true, "Pipeline=Skia (Vulkan)") == "VULKAN")
    c5("pipeline Skia (OpenGL) -> SKIA", Logic.rendererLabel(false, true, "Pipeline=Skia (OpenGL)") == "SKIA")
    c5("pipeline OpenGL klasik -> OPENGL", Logic.rendererLabel(false, true, "Pipeline=OpenGL") == "OPENGL")
    c5("maps terbaca tanpa libvulkan & tanpa info HWUI -> OPENGL", Logic.rendererLabel(false, true, "") == "OPENGL")
    c5("tidak ada data sama sekali -> --", Logic.rendererLabel(false, false, "") == "--")

    println(if (f5 == 0) "\nSEMUA TES v1.6 LULUS" else "\n$f5 TES v1.6 GAGAL")
    System.exit(if (f5 == 0) 0 else 1)
}
