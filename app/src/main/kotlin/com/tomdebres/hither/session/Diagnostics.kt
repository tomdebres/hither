package com.tomdebres.hither.session

import com.tomdebres.hither.core.FailureLog

/**
 * The failures every Seerr client of this process recovered from, for Settings › Diagnostics (M6, DECISIONS
 * 107). Kept in memory, since Hither started: nothing is written to the device.
 */
object Diagnostics {
    val log = FailureLog(now = System::currentTimeMillis)
}
