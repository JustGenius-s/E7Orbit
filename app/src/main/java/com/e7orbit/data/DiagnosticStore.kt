package com.e7orbit.data

import android.content.Context
import android.graphics.Bitmap
import com.e7orbit.model.ScreenFrame
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticStore(
    context: Context,
) {
    private val directory = File(context.applicationContext.filesDir, "diagnostics")
    private val formatter = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss_SSS")
        .withZone(ZoneOffset.UTC)

    suspend fun save(
        frame: ScreenFrame,
        reason: String,
    ): File = withContext(Dispatchers.IO) {
        val bitmap = requireNotNull(frame.bitmap) { "截图不包含 Bitmap" }
        check(directory.exists() || directory.mkdirs()) { "无法创建诊断目录" }
        val safeReason = reason.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
        val output = File(
            directory,
            "${formatter.format(Instant.now())}_${safeReason}.png",
        )
        output.outputStream().buffered().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "诊断截图写入失败"
            }
        }
        output
    }

}
