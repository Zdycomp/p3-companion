package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Random

/** Protocol tests driven by the byte vectors in src/test/resources/vectors (see its README). */
class XkVectorsTest {

    private fun text(name: String): String =
        javaClass.classLoader!!.getResource("vectors/$name")!!.readText()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Non-comment lines as bytes. */
    private fun lines(name: String): List<ByteArray> =
        text(name).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { hex(it) }

    private fun frames(name: String): List<Xk.Frame> {
        val p = Xk.Parser()
        val out = lines(name).flatMap { p.feed(it) }
        assertEquals("every line of $name is one valid frame", lines(name).size, out.size)
        return out
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---------------- encoding ----------------

    @Test fun everyCapturedRequestEncodesByteForByte() {
        val raw = text("requests_app_captured.hex").lines().map { it.trim() }
        val re = Regex("order=0x(\\w+) node=(\\w{4}) action=(\\d) req=0x(\\w+) data=(\\S+)")
        var checked = 0
        for ((i, line) in raw.withIndex()) {
            val m = re.find(line) ?: continue
            val bytes = hex(raw.drop(i + 1).first { it.isNotEmpty() && !it.startsWith("#") })
            val (order, node, action, req, data) = m.destructured
            val payload = when {
                data == "-" -> ByteArray(0)
                data.startsWith("Xk.userBindData") -> Xk.userBindData("0123456789abcdef0123456789abcdef")
                else -> hex(data)
            }
            assertArrayEquals("$node", bytes, Xk.control(order.toInt(16), node, action.toInt(), req.toInt(16), payload).encode())
            checked++
        }
        assertEquals(8, checked)
        assertArrayEquals(hex("306D04000000020000000000F84E00000106"), Xk.ack(0x6D, 0x06).encode())
    }

    @Test fun dangerousBindTypesAreRefused() {
        for (t in 3..5) {
            try { Xk.userBindData("0123456789abcdef0123456789abcdef", t); error("bind type $t was allowed") }
            catch (e: IllegalArgumentException) { }
        }
    }

    // ---------------- parser ----------------

    @Test fun noiseIsSkippedAndFramesComeOutInOrder() {
        val p = Xk.Parser()
        val perChunk = lines("noise_resync.hex").map { p.feed(it).map { f -> f.node } }
        assertEquals(listOf(0, 1, 2), perChunk.map { it.size })
        assertEquals(listOf("1003", "5713", "7320"), perChunk.flatten())
    }

    @Test fun aFalseHeaderNeverStallsTheParser() {
        val p = Xk.Parser()
        val out = lines("noise_false_header.hex").flatMap { p.feed(it) }.map { it.node }
        assertEquals(listOf("1003", "5713"), out)
    }

    @Test fun hugeDeclaredLengthIsReleasedBySkipStalled() {
        val p = Xk.Parser()
        val header = Xk.Frame(Xk.HEAD_MEDIA, 1, 0x0001, 1, ByteArray(0)).encode().also { it[6] = 0xFF.toByte(); it[7] = 0xFF.toByte() }
        assertTrue(p.feed(header + ByteArray(100) { 7 }).isEmpty())
        assertEquals(116, p.pending)
        val good = lines("reply_1003_battery.hex")[0]
        assertTrue(p.feed(good).isEmpty()) // still waiting behind the fake header
        val freed = p.skipStalled()
        assertEquals(listOf("1003"), freed.map { it.node })
        assertEquals(0, p.pending)
        assertEquals(listOf("1003"), p.feed(good).map { it.node })
    }

    @Test fun resetDropsAHalfFrame() {
        val p = Xk.Parser()
        val f = lines("reply_1003_battery.hex")[1]
        p.feed(f.copyOfRange(0, 10))
        p.reset()
        assertEquals(1, p.feed(f).size)
    }

    @Test fun randomChunkingNoiseAndCorruptionNeverLoseOtherFrames() {
        val all = frames("reply_1003_battery.hex") + frames("reply_5713_media_counts.hex") + frames("push_7320_count.hex") +
            frames("photo_download.hex")
        for (seed in 1..200) {
            val r = Random(seed.toLong())
            val picked = (1..12).map { all[r.nextInt(all.size)] }
            val bad = r.nextInt(picked.size)
            val stream = ByteArrayOutputStream()
            picked.forEachIndexed { i, f ->
                repeat(r.nextInt(20)) { stream.write(r.nextInt(256)) }
                val enc = f.encode()
                if (i == bad) enc[16 + r.nextInt(maxOf(1, enc.size - 16))] = (r.nextInt(255) + 1).toByte()
                stream.write(enc)
            }
            val bytes = stream.toByteArray()
            val p = Xk.Parser()
            val got = mutableListOf<Xk.Frame>()
            var pos = 0
            while (pos < bytes.size) {
                val n = minOf(bytes.size - pos, 1 + r.nextInt(300))
                got += p.feed(bytes.copyOfRange(pos, pos + n))
                pos += n
            }
            got += p.skipStalled()
            val want = picked.filterIndexed { i, _ -> i != bad }.map { it.payload.toList() }
            val have = got.map { it.payload.toList() }
            // Every intact frame comes out, in order; nothing invented.
            var k = 0
            for (w in want) { while (k < have.size && have[k] != w) k++; assertTrue("seed $seed lost a frame", k < have.size); k++ }
            assertTrue(have.all { h -> picked.any { it.payload.toList() == h } })
        }
    }

    @Test fun lengthHintInDivideBits() {
        val f = Xk.Frame(Xk.HEAD_MEDIA, 0x2A, 0x8001, 0x6913, ByteArray(42))
        assertEquals(3, f.fragment)
        assertEquals(617, f.logicalLength)
        assertTrue(f.fromDevice)
        assertFalse(f.isAck)
        assertFalse(Xk.Frame(Xk.HEAD_CUSTOM, 1, 0x0004, 0, byteArrayOf(1, 0, 5)).isAck)
    }

    // ---------------- replies and pushes ----------------

    @Test fun batteryReplies() {
        val got = frames("reply_1003_battery.hex").map { Xk.battery(it) }
        assertEquals(listOf(true to 100, false to 100, false to 57), got)
    }

    @Test fun deviceInfoJson() {
        val f = frames("reply_1001_device_info.hex").single()
        assertEquals("1001", f.node)
        assertEquals(Xk.FMT_JSON, f.replyType)
        assertEquals(411, f.replyLen)
        val j = Xk.jsonObject(f)!!
        assertEquals("xk one Pro_0001", j["dev_name"])
        assertEquals("1.0.2", j["soft_ver"])
        assertEquals("E13C-1", j["prod_mode"])
        assertEquals("100", j["battery_main"])
    }

    @Test fun mediaCountsAreStrings() {
        val counts = frames("reply_5713_media_counts.hex").map { f ->
            val j = Xk.jsonObject(f)!!
            listOf("photo_num", "video_num", "record_num").map { (j[it] as String).toInt() }
        }
        assertEquals(listOf(listOf(0, 0, 0), listOf(12, 3, 5)), counts)
    }

    @Test fun jsonIsReadFromTheReplyDataOnly() {
        // A request id of 0x7B ('{') must not confuse the JSON reader.
        val f = frames("reply_5713_media_counts.hex")[1]
        val p = f.payload.copyOf().also { it[0] = 0x7B }
        assertEquals("12", Xk.jsonObject(Xk.Frame(f.head, f.order, f.cmd, f.divide, p))!!["photo_num"])
    }

    @Test fun photoCountIsTheFirstDataByteOrLittleEndian() {
        assertEquals(listOf(6, 1), frames("push_7320_count.hex").map { Xk.elementCount(it) })
        assertEquals(listOf(6, 1), frames("push_7320_count_u16le.hex").map { Xk.elementCount(it) })
    }

    @Test fun touchPushesAreSeen() {
        assertEquals(listOf("C101", "C107"), frames("push_C101_touch.hex").map { it.node })
        assertEquals("57B1", frames("push_57B1_capture.hex").single().node)
    }

    @Test fun bindReplies() {
        val f = frames("reply_102E_bind.hex")
        assertEquals(listOf(Xk.Bind.REFUSED, Xk.Bind.BOUND, Xk.Bind.BOUND), f.map { Xk.bindOutcome(it) })
        assertEquals(Xk.Bind.UNKNOWN, Xk.bindOutcome(null))
        assertTrue(f.all { it.requestId == 0x98 })
    }

    @Test fun capturedMiscReplies() {
        val f = frames("replies_captured_misc.hex")
        assertEquals(listOf("C101", "57A0", "1017", "57A0"), f.map { it.node })
        assertEquals(4, f[0].errCode)
        assertEquals(3, f[1].errCode)
        assertArrayEquals(hex("1F000000"), f[2].data)
        assertNull(f[2].errCode)
        assertArrayEquals(hex("00"), f[3].data)
        assertNull("57A0 must never be read as battery", Xk.battery(f[3]))
    }

    // ---------------- photo download ----------------

    @Test fun photoDownloadRebuildsTheExpectedJpeg() {
        val fs = frames("photo_download.hex")
        var count = 0
        var asm: Xk.PhotoAssembler? = null
        var index = 0
        var mediaAcks = 0
        var controlAcks = 0
        var done = 0
        val demux = Xk.MediaDemux(onElement = { type, e ->
            assertEquals(Xk.MEDIA_PHOTO, type)
            assertEquals(e.size.toLong(), Xk.le32(e, 1))
            if (asm!!.offer(e) == Xk.PhotoAssembler.Result.DONE) done++
        }, onBroken = { _, why -> error("broken: $why") })
        for (f in fs) {
            when (f.head) {
                Xk.HEAD_CONTROL -> {
                    if (f.isAck) continue
                    controlAcks++
                    when (f.node) {
                        "7320" -> { count = Xk.elementCount(f)!!; asm = Xk.PhotoAssembler(count) }
                        "7300" -> asm!!.start(++index)
                    }
                }
                Xk.HEAD_MEDIA -> {
                    if (f.fragment == 3) mediaAcks++
                    demux.feed(f)
                }
            }
        }
        assertEquals(3, count)
        assertEquals(3, done)
        assertEquals(2, mediaAcks)
        assertEquals(8, controlAcks)
        val jpeg = asm!!.jpeg()
        assertEquals(653, jpeg.size)
        assertArrayEquals(lines("photo_expected_jpeg.hex").single(), jpeg)
        assertEquals("e6a5d4a899019014239ff34556a7685b860222f8a9be69de4a80a592caaffba5", sha256(jpeg))
    }

    @Test fun aLostFragmentFailsTheElementOnceAndRetryWorks() {
        val media = frames("photo_download.hex").filter { it.head == Xk.HEAD_MEDIA }
        val element2 = media.subList(1, 4) // divide 1, 2, 3
        val asm = Xk.PhotoAssembler(3)
        asm.start(2)
        assertEquals(Xk.PhotoAssembler.Result.MORE, asm.feed(element2[0]))
        assertEquals(Xk.PhotoAssembler.Result.FAILED, asm.feed(element2[2])) // middle one lost
        assertNotNull(asm.failReason)
        asm.start(2)
        val results = element2.map { asm.feed(it) }
        assertEquals(Xk.PhotoAssembler.Result.DONE, results.last())
        assertTrue(asm.has(2))
    }

    @Test fun aCutOffPhotoIsRejected() {
        val body = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(40) { 1 }
        val e = byteArrayOf(0, (body.size + 5).toByte(), 0, 0, 0) + body
        val asm = Xk.PhotoAssembler(1)
        asm.start(1)
        assertEquals(Xk.PhotoAssembler.Result.DONE, asm.offer(e))
        try { asm.jpeg(); error("a photo with no end marker was accepted") } catch (ex: IllegalStateException) {
            assertTrue(ex.message!!.contains("cut off"))
        }
    }

    // ---------------- live video ----------------

    private fun videoUnits(name: String): Pair<List<VideoAu>, List<Xk.Frame>> {
        val aus = mutableListOf<VideoAu>()
        val control = mutableListOf<Xk.Frame>()
        val demux = Xk.MediaDemux(onElement = { type, e ->
            assertEquals(Xk.MEDIA_H264, type)
            assertEquals(e.size - 5L, Xk.le32(e, 1))
            aus += VideoAu.fromElement(e, 0L)!!
        }, onBroken = { _, why -> error("broken: $why") })
        for (f in frames(name)) if (f.head == Xk.HEAD_MEDIA) demux.feed(f) else control += f
        return aus to control
    }

    @Test fun preview320x240() {
        val (aus, control) = videoUnits("preview_h264_320x240.hex")
        assertEquals(3, aus.size)
        for (au in aus) {
            assertEquals(listOf(7, 8, 6, 5), au.nalTypes)
            assertTrue(au.key)
            assertEquals(6005, au.annexB.size)
            assertEquals("e17cc9f3b5215d47f50679cf15208d645cdd0fa359535f2368582a7297d8b430", sha256(au.annexB))
            assertEquals(320 to 240, H264.spsSize(au.sps!!))
            assertNotNull(au.pps)
            assertTrue(au.sample().size < au.annexB.size + 8)
        }
        assertEquals(listOf(false to 100), control.map { Xk.battery(it) })
    }

    @Test fun preview640x360HighProfileCropped() {
        val (aus, _) = videoUnits("preview_h264_640x360.hex")
        assertEquals(1, aus.size)
        assertEquals(10049, aus[0].annexB.size)
        assertEquals("1786f718ad96a6d83dcfde785a90f6f435211f7d5f8d56e7a73266911cd17941", sha256(aus[0].annexB))
        assertEquals(640 to 360, H264.spsSize(aus[0].sps!!))
    }

    @Test fun videoNeverReachesThePhotoAssembler() {
        val asm = Xk.PhotoAssembler(1)
        asm.start(1)
        val media = frames("preview_h264_320x240.hex").filter { it.head == Xk.HEAD_MEDIA }
        assertTrue(media.map { asm.feed(it) }.all { it == Xk.PhotoAssembler.Result.MORE })
        assertFalse(asm.has(1))
    }

    @Test fun knownSpsGivesItsSize() {
        val sps = hex("6742C00BD90141FB0110000003001000000300A0F142A480")
        assertEquals(320 to 240, H264.spsSize(sps))
    }

    // ---------------- key finder ----------------

    private fun snoop(records: List<Pair<ByteArray, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write("btsnoop\u0000".toByteArray(Charsets.ISO_8859_1)); d.writeInt(1); d.writeInt(1002)
        for ((pkt, orig) in records) {
            d.writeInt(orig); d.writeInt(pkt.size); d.writeInt(0); d.writeInt(0); d.writeLong(0)
            d.write(pkt)
        }
        return out.toByteArray()
    }

    private fun acl(handleFlags: Int, body: ByteArray) =
        byteArrayOf(0x02, (handleFlags and 0xFF).toByte(), (handleFlags shr 8).toByte(), (body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + body

    @Test fun keyFinderJoinsACommandSplitBetweenTwoPackets() {
        val id = "fedcba9876543210fedcba9876543210"
        val frame = Xk.control(0x6E, "102E", Xk.ACTION_EXECUTE, 0x98, Xk.userBindData(id)).encode()
        val l2cap = ByteArray(8) { 9 }
        val first = acl(0x2001, l2cap + frame.copyOfRange(0, 50))
        val rest = acl(0x1001, frame.copyOfRange(50, frame.size))
        val counts = HashMap<String, Int>()
        val stats = KeyFinder.Stats()
        KeyFinder.scanAny(java.io.BufferedInputStream(snoop(listOf(first to first.size, rest to rest.size)).inputStream()), counts, stats, 0)
        assertEquals(id, counts.maxByOrNull { it.value }?.key)
        assertEquals(1, stats.snoopFiles)
        assertEquals(2L, stats.aclPackets)
    }

    @Test fun keyFinderSpotsAFilteredLog() {
        val recs = (1..40).map { acl(0x2001, ByteArray(20)) to 300 }
        val counts = HashMap<String, Int>()
        val stats = KeyFinder.Stats()
        KeyFinder.scanAny(java.io.BufferedInputStream(snoop(recs).inputStream()), counts, stats, 0)
        val r = KeyFinder.result(counts, stats)
        assertNull(r.key)
        assertTrue(r.filtered)
        assertTrue(KeyFinder.explainMiss(r).contains("Filtered"))
    }
}
