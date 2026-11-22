package io.github.o7py.margin.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * M0 input debug view (SPEC §13 M0). Shows, live, every pointer's tool type, action, buttonState,
 * pressure, touchMajor/Minor and hover events. Everything is also logged with tag "Margin" and
 * prefix "M0" so values can be collected via `adb logcat -s Margin`.
 */
class InputDebugView(context: Context) : View(context) {

    private data class Contact(
        val tool: Int,
        val downAt: Long,
        var maxMajor: Float = 0f,
        var maxMinor: Float = 0f,
        var maxPressure: Float = 0f,
        var buttons: Int = 0,
        var x: Float = 0f,
        var y: Float = 0f,
        var major: Float = 0f,
        var minor: Float = 0f,
        var pressure: Float = 0f,
    )

    private val density = resources.displayMetrics.density
    private val contacts = HashMap<Int, Contact>()
    private val lines = ArrayDeque<String>()
    private var hover: String = "none"
    private var hoverX = -1f
    private var hoverY = -1f
    private var moveCounter = 0
    private var lastStylusUp = 0L

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * density; color = Color.BLACK; typeface = android.graphics.Typeface.MONOSPACE }
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 61, 90, 254) }

    init {
        setBackgroundColor(Color.rgb(251, 246, 233))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun push(s: String) {
        Log.i(TAG, "M0 $s")
        lines.addFirst(s)
        while (lines.size > 28) lines.removeLast()
    }

    private fun dp(px: Float) = px / density

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val am = e.actionMasked
        val idx = e.actionIndex
        when (am) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx)
                contacts[id] = Contact(e.getToolType(idx), SystemClock.uptimeMillis())
                val sinceStylus = SystemClock.uptimeMillis() - lastStylusUp
                push("${actionName(am)} id=$id ${describe(e, idx)} ptrs=${e.pointerCount} sinceStylusUp=${sinceStylus}ms hover=$hover")
            }
            MotionEvent.ACTION_MOVE -> {
                moveCounter++
                if (moveCounter % 6 == 0) {
                    for (i in 0 until e.pointerCount) push("MOVE id=${e.getPointerId(i)} ${describe(e, i)} hist=${e.historySize}")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(idx)
                val c = contacts.remove(id)
                if (c != null) {
                    if (c.tool == MotionEvent.TOOL_TYPE_STYLUS || c.tool == MotionEvent.TOOL_TYPE_ERASER) lastStylusUp = SystemClock.uptimeMillis()
                    push(
                        "SUMMARY ${actionName(am)} id=$id tool=${toolName(c.tool)} dur=${SystemClock.uptimeMillis() - c.downAt}ms " +
                            "maxMajor=%.1fdp maxMinor=%.1fdp maxP=%.2f buttonsSeen=0x%x".format(dp(c.maxMajor), dp(c.maxMinor), c.maxPressure, c.buttons)
                    )
                } else push("${actionName(am)} id=$id")
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> push("${actionName(am)} actionButton=0x%x buttonState=0x%x tool=%s".format(e.actionButton, e.buttonState, toolName(e.getToolType(0))))
            else -> push("${actionName(am)} ${describe(e, 0)}")
        }
        for (i in 0 until e.pointerCount) {
            val c = contacts[e.getPointerId(i)] ?: continue
            c.x = e.getX(i); c.y = e.getY(i)
            c.major = e.getTouchMajor(i); c.minor = e.getTouchMinor(i); c.pressure = e.getPressure(i)
            c.maxMajor = maxOf(c.maxMajor, c.major); c.maxMinor = maxOf(c.maxMinor, c.minor)
            c.maxPressure = maxOf(c.maxPressure, c.pressure)
            c.buttons = c.buttons or e.buttonState
        }
        invalidate()
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val am = e.actionMasked
        hover = if (am == MotionEvent.ACTION_HOVER_EXIT) "none" else toolName(e.getToolType(0))
        hoverX = if (am == MotionEvent.ACTION_HOVER_EXIT) -1f else e.x
        hoverY = e.y
        if (am != MotionEvent.ACTION_HOVER_MOVE || (++moveCounter % 10 == 0)) push("${actionName(am)} ${describe(e, 0)}")
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val am = e.actionMasked
        if (am == MotionEvent.ACTION_BUTTON_PRESS || am == MotionEvent.ACTION_BUTTON_RELEASE) {
            push("GENERIC ${actionName(am)} actionButton=0x%x buttonState=0x%x tool=%s".format(e.actionButton, e.buttonState, toolName(e.getToolType(0))))
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    private fun describe(e: MotionEvent, i: Int) =
        "tool=%s btn=0x%x p=%.2f maj=%.1fdp min=%.1fdp size=%.3f x=%.0f y=%.0f src=0x%x".format(
            toolName(e.getToolType(i)), e.buttonState, e.getPressure(i), dp(e.getTouchMajor(i)), dp(e.getTouchMinor(i)),
            e.getSize(i), e.getX(i), e.getY(i), e.source
        )

    override fun onDraw(canvas: Canvas) {
        for (c in contacts.values) {
            circle.color = when (c.tool) {
                MotionEvent.TOOL_TYPE_STYLUS -> Color.rgb(26, 115, 232)
                MotionEvent.TOOL_TYPE_ERASER -> Color.rgb(217, 48, 37)
                else -> Color.rgb(24, 128, 56)
            }
            canvas.drawCircle(c.x, c.y, maxOf(c.major / 2f, 6f * density), circle)
            canvas.drawText(
                "%s maj %.0fdp p %.2f".format(toolName(c.tool), dp(c.major), c.pressure),
                c.x + 30 * density, c.y - 30 * density, text
            )
        }
        if (hoverX >= 0) canvas.drawCircle(hoverX, hoverY, 10f * density, dot)
        var y = 28f * density
        canvas.drawText("M0 input debug — hover: $hover — contacts: ${contacts.size}  (back = exit)", 16 * density, y, text)
        y += 8 * density
        for (l in lines) {
            y += 20f * density
            canvas.drawText(l, 16 * density, y, text)
        }
    }

    companion object {
        const val TAG = "Margin"
        fun toolName(t: Int) = when (t) {
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "UNKNOWN"
        }
        fun actionName(a: Int) = when (a) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "PTR_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "PTR_UP"
            MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
            MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
            MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
            MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
            MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
            else -> "ACT$a"
        }
    }
}
