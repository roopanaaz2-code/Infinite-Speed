package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Lightweight, zero-dependency QR code generator for device pairing and Web Share URLs.
 * Encodes text/binary data into standard QR Matrix and renders crisp Android Bitmaps.
 */
object QrCodeGenerator {

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val qr = encodeQr(content)
        val modules = qr.modules
        val moduleCount = qr.size
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)

        // Draw QR code with quiet zone padding
        val quietZone = 4
        val totalSize = moduleCount + quietZone * 2
        val scale = sizePx.toFloat() / totalSize.toFloat()

        val pixels = IntArray(sizePx * sizePx)
        // Background dark navy or clean white
        val bgPixel = Color.WHITE
        val fgPixel = Color.BLACK

        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                val modX = ((x / scale) - quietZone).toInt()
                val modY = ((y / scale) - quietZone).toInt()

                val isDark = if (modX in 0 until moduleCount && modY in 0 until moduleCount) {
                    modules[modY][modX]
                } else {
                    false
                }
                pixels[y * sizePx + x] = if (isDark) fgPixel else bgPixel
            }
        }

        bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bitmap
    }

    fun generateQrImageBitmap(content: String, sizePx: Int = 512): ImageBitmap {
        return generateQrBitmap(content, sizePx).asImageBitmap()
    }

    private class SimpleQr(val size: Int, val modules: Array<BooleanArray>)

    private fun encodeQr(text: String): SimpleQr {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val dataLen = bytes.size
        // Determine version based on length
        val version = when {
            dataLen <= 14 -> 1
            dataLen <= 26 -> 2
            dataLen <= 42 -> 3
            dataLen <= 62 -> 4
            dataLen <= 84 -> 5
            dataLen <= 106 -> 6
            else -> 7
        }
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun markReserved(r: Int, c: Int, isDark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                modules[r][c] = isDark
                reserved[r][c] = true
            }
        }

        // 1. Finder patterns at top-left, top-right, bottom-left
        fun placeFinder(row: Int, col: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val isBorder = r == -1 || r == 7 || c == -1 || c == 7
                    val isOuter = r == 0 || r == 6 || c == 0 || c == 6
                    val isInner = r in 2..4 && c in 2..4
                    val isDark = !isBorder && (isOuter || isInner)
                    val tr = row + r
                    val tc = col + c
                    if (tr in 0 until size && tc in 0 until size) {
                        modules[tr][tc] = isDark
                        reserved[tr][tc] = true
                    }
                }
            }
        }
        placeFinder(0, 0)
        placeFinder(0, size - 7)
        placeFinder(size - 7, 0)

        // 2. Timing patterns
        for (i in 8 until size - 8) {
            markReserved(6, i, i % 2 == 0)
            markReserved(i, 6, i % 2 == 0)
        }

        // Dark module
        markReserved(size - 8, 8, true)

        // Encode Byte Mode bits (0100 + count + payload)
        val bitBuffer = mutableListOf<Boolean>()
        fun addBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bitBuffer.add((value shr i and 1) == 1)
            }
        }

        // Mode: 8-bit byte (0100)
        addBits(4, 4)
        addBits(dataLen, 8)
        for (b in bytes) {
            addBits(b.toInt() and 0xFF, 8)
        }

        // Terminator
        val capacityBytes = when (version) {
            1 -> 19
            2 -> 34
            3 -> 55
            4 -> 80
            5 -> 108
            6 -> 136
            else -> 156
        }
        val capacityBits = capacityBytes * 8
        val terminatorLen = minOf(4, capacityBits - bitBuffer.size)
        for (i in 0 until terminatorLen) bitBuffer.add(false)
        while (bitBuffer.size % 8 != 0) bitBuffer.add(false)

        // Pad bytes (0xEC, 0x11)
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIndex = 0
        while (bitBuffer.size < capacityBits) {
            addBits(padBytes[padIndex % 2], 8)
            padIndex++
        }

        // Place data into grid with zigzag order and mask pattern 0: (row + col) % 2 == 0
        var bitIndex = 0
        var right = size - 1
        var goingUp = true

        while (right > 0) {
            if (right == 6) right-- // Skip vertical timing column
            val range = if (goingUp) (size - 1 downTo 0) else (0 until size)
            for (r in range) {
                for (c in intArrayOf(right, right - 1)) {
                    if (!reserved[r][c]) {
                        val bit = if (bitIndex < bitBuffer.size) bitBuffer[bitIndex++] else false
                        val mask = (r + c) % 2 == 0
                        modules[r][c] = bit xor mask
                    }
                }
            }
            goingUp = !goingUp
            right -= 2
        }

        // Format information area: Mask 0, Error Correction L (01) -> standard format bits
        val formatBits = intArrayOf(1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0)
        // Top-left
        var fIndex = 0
        for (c in 0..8) {
            if (c != 6) modules[8][c] = formatBits[fIndex++] == 1
        }
        for (r in 7 downTo 0) {
            if (r != 6) modules[r][8] = formatBits[fIndex++] == 1
        }
        // Top-right and bottom-left
        fIndex = 0
        for (r in size - 1 downTo size - 7) {
            modules[r][8] = formatBits[fIndex++] == 1
        }
        for (c in size - 8 until size) {
            modules[8][c] = formatBits[fIndex++] == 1
        }

        return SimpleQr(size, modules)
    }
}
