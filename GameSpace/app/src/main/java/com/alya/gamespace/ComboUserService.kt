package com.alya.gamespace

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Berjalan di proses Shizuku (uid shell). Menyuntikkan sentuhan lewat InputManager (API tersembunyi),
 * sebagai perangkat terpisah sehingga tidak membatalkan sentuhan asli pemain (joystick dll).
 */
class ComboUserService : IComboService.Stub() {
    private class Target(val obj: Any, val m: Method, val argc: Int, val name: String)

    private var target: Target? = null
    private var error = ""

    init { resolve() }

    private fun pick(o: Any, label: String): Target? {
        for (m in o.javaClass.methods) {
            if (!m.name.startsWith("injectInputEvent")) continue
            val t = m.parameterTypes
            val int = Int::class.javaPrimitiveType
            if (t.size >= 2 && InputEvent::class.java.isAssignableFrom(t[0]) && t[1] == int) {
                if (t.size == 2 || (t.size == 3 && t[2] == int)) return Target(o, m, t.size, label + "." + m.name)
            }
        }
        return null
    }

    private fun resolve() {
        val errs = StringBuilder()
        val tries = ArrayList<Pair<String, () -> Any?>>()
        tries.add(Pair("InputManager") { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null) })
        tries.add(Pair("InputManagerGlobal") { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) })
        tries.add(Pair("IInputManager") {
            val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            Class.forName("android.hardware.input.IInputManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)
        })
        for (t in tries) {
            try {
                val o = t.second() ?: continue
                val x = pick(o, t.first)
                if (x != null) { target = x; return }
                errs.append(t.first).append(": tanpa injectInputEvent; ")
            } catch (e: Throwable) {
                errs.append(t.first).append(": ").append(e.javaClass.simpleName).append("; ")
            }
        }
        error = errs.toString()
    }

    private fun send(ev: InputEvent): Boolean {
        val t = target ?: return false
        return try {
            val r = if (t.argc == 2) t.m.invoke(t.obj, ev, 0) else t.m.invoke(t.obj, ev, 0, -1)
            if (r is Boolean) r else true
        } catch (_: Throwable) { false }
    }

    private fun ev(downTime: Long, action: Int, count: Int,
                   props: Array<MotionEvent.PointerProperties>, coords: Array<MotionEvent.PointerCoords>): Boolean {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
            props.copyOfRange(0, count), coords.copyOfRange(0, count), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val r = send(e)
        e.recycle()
        return r
    }

    /** Tap satu atau beberapa titik sekaligus (maks 4 jari), lalu lepas setelah downMs. */
    override fun tap(xs: IntArray, ys: IntArray, downMs: Int): Boolean {
        val n = minOf(xs.size, ys.size, 4)
        if (n <= 0 || target == null) return false
        val props = Array(n) { i -> MotionEvent.PointerProperties().also { it.id = i; it.toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().also { it.x = xs[i].toFloat(); it.y = ys[i].toFloat(); it.pressure = 1f; it.size = 1f }
        }
        val downTime = SystemClock.uptimeMillis()
        var ok = true
        for (k in 0 until n) {
            val action = if (k == 0) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or (k shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            ok = ev(downTime, action, k + 1, props, coords) && ok
        }
        try { Thread.sleep(downMs.toLong()) } catch (_: InterruptedException) {}
        for (k in n - 1 downTo 0) {
            val action = if (k == 0) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (k shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            ok = ev(downTime, action, k + 1, props, coords) && ok
        }
        return ok
    }

    override fun info(): String =
        "uid=" + android.os.Process.myUid() + " via " + (target?.name ?: ("TIDAK ADA (" + error + ")"))

    override fun destroy() { System.exit(0) }
}
