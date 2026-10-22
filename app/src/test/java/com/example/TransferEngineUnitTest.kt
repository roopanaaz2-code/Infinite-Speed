package com.example

import com.example.model.FileCategory
import com.example.model.FileItem
import com.example.model.TransferDirection
import com.example.model.TransferStatus
import com.example.data.TransferEntity
import com.example.security.SecurityUtils
import com.example.util.QrCodeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineUnitTest {

    @Test
    fun testPathTraversalProtection() {
        // Test Unix directory traversal
        val evil1 = "../../../etc/passwd"
        assertEquals("passwd", SecurityUtils.sanitizeFileName(evil1))

        // Test Windows backslash directory traversal
        val evil2 = "..\\..\\windows\\system32\\cmd.exe"
        assertEquals("cmd.exe", SecurityUtils.sanitizeFileName(evil2))

        // Test Absolute paths
        val evil3 = "/root/secret_file.key"
        assertEquals("secret_file.key", SecurityUtils.sanitizeFileName(evil3))

        // Test empty/blank fallback
        val empty = "   "
        val sanitizedEmpty = SecurityUtils.sanitizeFileName(empty)
        assertTrue(sanitizedEmpty.startsWith("received_file_"))
    }

    @Test
    fun testSha256ChecksumCalculation() {
        val testString = "InfiniteSpeedUltraFastFileSharing"
        val input = ByteArrayInputStream(testString.toByteArray(Charsets.UTF_8))
        val hash = SecurityUtils.calculateSha256(input)
        assertNotNull(hash)
        assertEquals(64, hash.length) // 64 hex characters for SHA-256

        // Same content must produce identical hash
        val input2 = ByteArrayInputStream(testString.toByteArray(Charsets.UTF_8))
        val hash2 = SecurityUtils.calculateSha256(input2)
        assertEquals(hash, hash2)
    }

    @Test
    fun testPairingPinGeneration() {
        for (i in 0 until 10) {
            val pin = SecurityUtils.generatePairingPin()
            assertEquals(6, pin.length)
            assertTrue(pin.toIntOrNull() != null)
            assertTrue(pin.toInt() in 100000..999999)
        }
    }

    @Test
    fun testFileItemFormattedSize() {
        val item1 = FileItem("1", "small.txt", "/path", 512L, "text/plain", FileCategory.DOCUMENT)
        assertEquals("512.0 B", item1.formattedSize)

        val item2 = FileItem("2", "photo.jpg", "/path", 2L * 1024L * 1024L, "image/jpeg", FileCategory.PHOTO)
        assertEquals("2.0 MB", item2.formattedSize)

        val item3 = FileItem("3", "movie.mp4", "/path", 1536L * 1024L * 1024L, "video/mp4", FileCategory.VIDEO)
        assertEquals("1.5 GB", item3.formattedSize)
    }

    @Test
    fun testTransferEntityProgress() {
        val entity = TransferEntity(
            transferId = "t1",
            fileName = "archive.zip",
            filePath = "/path/archive.zip",
            fileSize = 1000L,
            mimeType = "application/zip",
            direction = TransferDirection.SEND,
            peerName = "Peer1",
            peerAddress = "192.168.1.50",
            status = TransferStatus.IN_PROGRESS,
            bytesTransferred = 500L,
            speedBytesPerSec = 50L * 1024L * 1024L
        )
        assertEquals(0.5f, entity.progressPercent, 0.001f)
        assertEquals("50.0 MB/s", entity.formattedSpeed)
    }

    @Test
    fun testQrCodeGenerator() {
        val payload = "infinitespeed://pair?ip=192.168.1.10&port=8990&pin=123456"
        val bitmap = QrCodeGenerator.generateQrBitmap(payload, 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }
}
