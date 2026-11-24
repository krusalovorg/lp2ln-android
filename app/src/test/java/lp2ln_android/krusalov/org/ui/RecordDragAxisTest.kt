package lp2ln_android.krusalov.org.ui

import org.junit.Assert.*
import org.junit.Test

class RecordDragAxisTest {
    @Test fun keepsChosenAxisUntilFingerReturns() {
        assertEquals(RecordDragAxis.LEFT, recordDragAxis(null, -30f, -10f, 8f))
        assertEquals(RecordDragAxis.LEFT, recordDragAxis(RecordDragAxis.LEFT, -28f, -90f, 8f))
        assertNull(recordDragAxis(RecordDragAxis.LEFT, -3f, -1f, 8f))
        assertEquals(RecordDragAxis.UP, recordDragAxis(null, -10f, -30f, 8f))
        assertNull(recordDragAxis(null, 30f, 10f, 8f))
    }
}
