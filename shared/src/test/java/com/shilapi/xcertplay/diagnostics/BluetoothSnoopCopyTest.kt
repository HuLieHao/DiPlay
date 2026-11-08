package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*

class BluetoothSnoopCopyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun header() = ByteArrayOutputStream().also { b -> DataOutputStream(b).use {
        it.write(byteArrayOf(98,116,115,110,111,111,112,0)); it.writeInt(1); it.writeInt(1002)
    }}.toByteArray()
    private fun record(payload: ByteArray) = ByteArrayOutputStream().also { b -> DataOutputStream(b).use {
        it.writeInt(payload.size); it.writeInt(payload.size); it.writeInt(0); it.writeInt(0); it.writeLong(1234); it.write(payload)
    }}.toByteArray()
    private fun copier() = BluetoothSnoopCopy { n -> File(temp.root, n) }

    @Test fun statusCountsOnlySuccessfullyCopiedCompleteRecords() {
        val source = temp.newFile("source")
        val copy = copier()
        assertTrue(copy.status().contains("HCI unavailable"))
        source.writeBytes(header())
        copy.copy(source, "inode1") { false }
        assertTrue(copy.status().contains("header_only"))
        source.appendBytes(record(byteArrayOf(1)))
        copy.copy(source, "inode1") { false }
        copy.copy(source, "inode1") { false }
        assertTrue(copy.status().contains("completeRecords=1"))
        assertTrue(copy.status().contains("historical"))
    }

    @Test fun copiesOnlyCompleteRecordsAndAppendsWithoutDuplication() {
        val source = temp.newFile("source")
        val packet = record(byteArrayOf(1,2,3))
        source.writeBytes(header() + packet.copyOf(25))
        val copy = copier()
        copy.copy(source, "inode1") { false }
        val output = temp.root.listFiles()!!.single { it.extension == "btsnoop" }
        assertArrayEquals(header(), output.readBytes())
        source.appendBytes(packet.copyOfRange(25, packet.size))
        copy.copy(source, "inode1") { false }
        copy.copy(source, "inode1") { false }
        assertArrayEquals(header() + packet, output.readBytes())
        copy.close()
    }

    @Test fun replacedOrTruncatedSourceStartsSeparateCompleteFile() {
        val source = temp.newFile("source")
        val a = record(byteArrayOf(1,2,3))
        val b = record(byteArrayOf(4))
        val copy = copier()
        source.writeBytes(header() + a); copy.copy(source, "inode1") { false }
        source.writeBytes(header() + b); copy.copy(source, "inode1") { false }
        source.writeBytes(header() + a); copy.copy(source, "inode2") { false }
        val outputs = temp.root.listFiles()!!.filter { it.extension == "btsnoop" }.sortedBy { it.name }
        assertEquals(3, outputs.size)
        assertArrayEquals(header() + a, outputs[0].readBytes())
        assertArrayEquals(header() + b, outputs[1].readBytes())
        assertArrayEquals(header() + a, outputs[2].readBytes())
        copy.close()
    }

    @Test fun rotationToLastRetainsCursorAndCopiesOnlyUnseenTail() {
        val source = temp.newFile("source")
        val last = File(temp.root, "source.last")
        val copy = copier()
        val a = record(byteArrayOf(1))
        val b = record(byteArrayOf(2))
        source.writeBytes(header() + a); copy.copy(source, "inode1") { false }
        assertTrue(source.renameTo(last)); last.appendBytes(b)
        source.writeBytes(header() + b); copy.copy(source, "inode2") { false }
        copy.copy(last, "inode1") { false }
        val outputs = temp.root.listFiles()!!.filter { it.extension == "btsnoop" }.sortedBy { it.name }
        assertEquals(2, outputs.size)
        assertArrayEquals(header() + a + b, outputs[0].readBytes())
        assertArrayEquals(header() + b, outputs[1].readBytes())
        copy.close()
    }

    @Test fun sameInodeRewrittenAndRegrownFileDoesNotMixOldAndNewPackets() {
        val source = temp.newFile("source")
        val copy = copier()
        val a = record(byteArrayOf(1))
        val b = record(byteArrayOf(2))
        source.writeBytes(header() + a); copy.copy(source, "inode1") { false }
        source.writeBytes(header() + b + b); copy.copy(source, "inode1") { false }
        val outputs = temp.root.listFiles()!!.filter { it.extension == "btsnoop" }.sortedBy { it.name }
        assertEquals(2, outputs.size)
        assertArrayEquals(header() + a, outputs[0].readBytes())
        assertArrayEquals(header() + b + b, outputs[1].readBytes())
        copy.close()
    }

    @Test fun cancellationBetweenPacketsLeavesCompletePrefixAndCanResume() {
        val source = temp.newFile("source")
        val copy = copier()
        val a = record(byteArrayOf(1))
        source.writeBytes(header() + a + a)
        var checks = 0
        copy.copy(source, "inode1") { ++checks >= 3 }
        val output = temp.root.listFiles()!!.single { it.extension == "btsnoop" }
        assertArrayEquals(header() + a, output.readBytes())
        copy.copy(source, "inode1") { false }
        assertArrayEquals(header() + a + a, output.readBytes())
        copy.close()
    }

    @Test fun cancellationAndInvalidHeadersDoNotCreateMisleadingCapture() {
        val source = temp.newFile("source")
        val copy = copier()
        source.writeBytes(header() + record(byteArrayOf(1)))
        copy.copy(source, "inode1") { true }
        assertFalse(temp.root.listFiles()!!.any { it.extension == "btsnoop" })
        source.writeText("this is not a Bluetooth snoop file")
        try { copy.copy(source, "inode1") { false }; fail("must report invalid header") }
        catch (_: IOException) { }
        assertFalse(temp.root.listFiles()!!.any { it.extension == "btsnoop" })
        copy.close()
    }
}
