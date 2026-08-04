package com.mkread.app.feature.reader

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class PaginationSpec(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val fontFamilyId: String,
    val fontSizeSp: Float,
    val lineSpacingMultiplier: Float,
    val horizontalMarginPx: Int,
)

@Serializable
data class PageRange(
    val index: Int,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(index >= 0) { "Page index must be non-negative" }
        require(start >= 0) { "Page start must be non-negative" }
        require(endExclusive > start) { "Page range must be non-empty" }
    }
}

@Serializable
data class PaginationKey(
    val chapterId: String,
    val contentSha256: String,
    val spec: PaginationSpec,
    val algorithmVersion: Int = 1,
)

fun PaginationKey.cacheId(): String {
    val bytes = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(algorithmVersion)
            data.writeUtf8(chapterId)
            data.writeUtf8(contentSha256)
            data.writeInt(spec.widthPx)
            data.writeInt(spec.heightPx)
            data.writeInt(spec.densityDpi)
            data.writeUtf8(spec.fontFamilyId)
            data.writeInt(spec.fontSizeSp.toRawBits())
            data.writeInt(spec.lineSpacingMultiplier.toRawBits())
            data.writeInt(spec.horizontalMarginPx)
        }
        output.toByteArray()
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}
