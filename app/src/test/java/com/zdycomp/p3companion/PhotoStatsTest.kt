package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.8: the Import tab's "Cable photos … ; Bluetooth photos …" line and the numbers behind it. */
class PhotoStatsTest {

    private fun s(w: Int, h: Int, kb: Int) = PhotoStats.Size(w, h, kb * 1024L)

    @Test fun theComparisonLine() {
        val cable = listOf(s(1280, 960, 380), s(1280, 960, 420), s(1280, 960, 400))
        val bt = listOf(s(640, 480, 54), s(640, 480, 75), s(640, 480, 81))
        assertEquals("Cable photos: 1280x960 avg 400 KB; Bluetooth photos: 640x480 avg 70 KB", PhotoStats.summary(cable, bt))
        assertEquals("1280x960 is 4.0x the pixels of 640x480", PhotoStats.ratio(cable, bt))
    }

    @Test fun averagesRoundToTheNearestKb() {
        assertEquals(70L, PhotoStats.averageKb(listOf(PhotoStats.Size(640, 480, 71_000), PhotoStats.Size(640, 480, 72_000))))
        assertEquals(1L, PhotoStats.averageKb(listOf(PhotoStats.Size(0, 0, 600))))
        assertEquals(0L, PhotoStats.averageKb(listOf(PhotoStats.Size(0, 0, 500))))
        assertNull(PhotoStats.averageKb(emptyList()))
    }

    @Test fun oneSideMissing() {
        assertEquals("Cable photos: none yet; Bluetooth photos: 640x480 avg 70 KB", PhotoStats.summary(emptyList(), listOf(s(640, 480, 70))))
        assertEquals("Cable photos: 1280x960 avg 400 KB; Bluetooth photos: none yet", PhotoStats.summary(listOf(s(1280, 960, 400)), emptyList()))
        assertNull(PhotoStats.summary(emptyList(), emptyList()))
        assertNull(PhotoStats.ratio(emptyList(), listOf(s(640, 480, 70))))
    }

    @Test fun mixedAndUnknownSizes() {
        assertEquals("mostly 640x480", PhotoStats.typicalSize(listOf(s(640, 480, 1), s(640, 480, 1), s(320, 240, 1))))
        assertEquals("mixed: 1280x960, 640x480", PhotoStats.typicalSize(listOf(s(640, 480, 1), s(1280, 960, 1))))
        assertEquals("640x480", PhotoStats.typicalSize(listOf(s(640, 480, 1), PhotoStats.Size(0, 0, 9))))
        assertNull(PhotoStats.typicalSize(listOf(PhotoStats.Size(0, 0, 9))))
        assertEquals("size unknown, avg 70 KB", PhotoStats.describe(listOf(PhotoStats.Size(0, 0, 70 * 1024L))))
    }

    @Test fun keptInSettings() {
        val list = listOf(s(640, 480, 70), PhotoStats.Size(0, 0, 1234))
        assertEquals(list, PhotoStats.decode(PhotoStats.encode(list)))
        assertEquals(listOf(s(640, 480, 70)), PhotoStats.decode("640x480x71680;junk;1x2;-1x2x3"))
        assertEquals(emptyList<PhotoStats.Size>(), PhotoStats.decode(null))
        val many = (1..50).map { s(640, 480, it) }
        val kept = PhotoStats.append(emptyList(), many)
        assertEquals(PhotoStats.KEEP, kept.size)
        assertEquals(s(640, 480, 50), kept.last())
        val p = Prefs(FakeSharedPreferences())
        p.bluetoothPhotoSizes = PhotoStats.append(p.bluetoothPhotoSizes, listOf(s(640, 480, 70)))
        p.cablePhotoSizes = listOf(s(1280, 960, 400))
        assertEquals(listOf(s(640, 480, 70)), p.bluetoothPhotoSizes)
        assertEquals(listOf(s(1280, 960, 400)), p.cablePhotoSizes)
    }

    @Test fun importedFileLinesAndTheImportsPhotos() {
        val photo = ImportedFile("IMG_0001.JPG", MediaKind.PHOTO, 400 * 1024L, 1280, 960, sameBytes = true)
        assertEquals("IMG_0001.JPG: 1280x960, 400 KB, byte-for-byte copy", photo.line())
        assertEquals("IMG_0002.JPG: size unknown, 60 KB", ImportedFile("IMG_0002.JPG", MediaKind.PHOTO, 60 * 1024L).line())
        assertEquals("VID_1.MP4: 12.0 MB", ImportedFile("VID_1.MP4", MediaKind.VIDEO, 12L * 1024 * 1024).line())
        assertTrue(ImportedFile("a.jpg", MediaKind.PHOTO, 1024, 1, 1, sameBytes = false).line().contains("reads back different"))
        val r = ImportResult(1, 1, 0, 0, emptyList(), 0, false,
            listOf(photo, ImportedFile("VID_1.MP4", MediaKind.VIDEO, 5_000_000)))
        assertEquals(listOf(PhotoStats.Size(1280, 960, 400 * 1024L)), r.photoSizes())
        assertEquals("Imported 1 photo, 1 video.", r.summary())
    }
}
