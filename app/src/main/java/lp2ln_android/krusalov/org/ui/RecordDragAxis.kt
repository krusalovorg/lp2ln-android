package lp2ln_android.krusalov.org.ui

internal enum class RecordDragAxis { LEFT, UP }

internal fun recordDragAxis(current: RecordDragAxis?, dx: Float, dy: Float, slop: Float): RecordDragAxis? {
    if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) return null
    if (current != null) return current
    if (-dx < slop && -dy < slop) return null
    return if (-dx >= -dy) RecordDragAxis.LEFT else RecordDragAxis.UP
}
