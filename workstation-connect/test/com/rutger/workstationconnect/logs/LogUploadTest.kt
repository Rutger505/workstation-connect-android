// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.logs

import com.rutger.workstationconnect.logs.LogUploadLogic.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigV4Test {
    private val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** AWS SigV4 test suite: get-vanilla. */
    @Test
    fun awsTestSuiteGetVanilla() {
        val auth = SigV4.authorization(
            SigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
            "us-east-1", "service", "20150830T123600Z",
            method = "GET", uri = "/", query = "",
            headers = listOf("host" to "example.amazonaws.com", "x-amz-date" to "20150830T123600Z"),
            payloadSha256 = empty,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            auth,
        )
    }

    /** S3 docs example "PUT Object" (sig-v4-header-based-auth). */
    @Test
    fun s3DocsPutObject() {
        val hash = SigV4.sha256Hex("Welcome to Amazon S3.".toByteArray())
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val auth = SigV4.authorization(
            SigV4.Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            "us-east-1", "s3", "20130524T000000Z",
            method = "PUT", uri = SigV4.encodePath("/test\$file.text"), query = "",
            headers = listOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "date" to "Fri, 24 May 2013 00:00:00 GMT",
                "x-amz-date" to "20130524T000000Z",
                "x-amz-storage-class" to "REDUCED_REDUNDANCY",
                "x-amz-content-sha256" to hash,
            ),
            payloadSha256 = hash,
        )
        assertTrue(auth, auth.endsWith(
            "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, " +
                "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"))
    }

    @Test
    fun encodesPaths() {
        assertEquals("/b/phone/my%20host/x%2By.log", SigV4.encodePath("/b/phone/my host/x+y.log"))
        assertEquals("/%C3%A9", SigV4.encodePath("/é"))
    }
}

class LogUploadLogicTest {
    // 2026-11-23T21:04:09Z
    private val t = 1795467849_000L

    @Test
    fun objectKeys() {
        assertEquals("phone/Pixel_9/2026-11-23/210409-start.log", LogUploadLogic.objectKey("Pixel 9", Reason.START, t))
        assertEquals("phone/a_b-c.d/2026-11-23/210409-link-lost.log", LogUploadLogic.objectKey("a/b-c.d", Reason.LINK_LOST, t))
        assertEquals("phone/unknown/2026-11-23/210409-manual.log", LogUploadLogic.objectKey("", Reason.MANUAL, t))
    }

    @Test
    fun dates() {
        assertEquals("20261123T210409Z", LogUploadLogic.amzDate(t))
        assertEquals("2026-11-23T21:04:09Z", LogUploadLogic.isoUtc(t))
    }

    @Test
    fun marker() {
        val lines = listOf("a", "b", "c", "b", "d")
        assertEquals(lines to false, LogUploadLogic.since(lines, null))
        assertEquals(lines to false, LogUploadLogic.since(lines, "gone"))
        assertEquals(listOf("d") to true, LogUploadLogic.since(lines, "b"))
        assertEquals(emptyList<String>() to true, LogUploadLogic.since(lines, "d"))
    }

    @Test
    fun newLinesIgnoreOwnUploadLines() {
        val own = "2026-11-23 22:04:09.123 I upload: periodic: phone/x/2026-11-23/210409-periodic.log (12 bytes)"
        val ownFail = "2026-11-23 22:04:09.124 W upload: periodic failed, retried with the next trigger"
        val debug = "2026-11-23 22:04:09.125 D link: frame sent"
        val other = "2026-11-23 22:04:10.000 I link: laptop connected"
        val legacyOwn = "2026-11-23 22:04:09.123 log upload: phone/x/2026-11-23/210409-periodic.log (12 bytes)"
        val legacy = "2026-11-23 22:04:10.000 laptop connected"
        assertFalse(LogUploadLogic.hasNewLines(emptyList()))
        assertFalse(LogUploadLogic.hasNewLines(listOf(own, ownFail, debug, legacyOwn)))
        assertTrue(LogUploadLogic.hasNewLines(listOf(own, other)))
        assertTrue(LogUploadLogic.hasNewLines(listOf(legacyOwn, legacy)))
    }

    @Test
    fun s3ErrorKeepsOnlyCodeAndMessage() {
        val body = "<Error><Code>SignatureDoesNotMatch</Code><Message>nope</Message>" +
            "<AWSAccessKeyId>AKIDSECRET</AWSAccessKeyId><StringToSign>x</StringToSign></Error>"
        assertEquals("SignatureDoesNotMatch: nope", LogUploadLogic.s3Error(body))
        assertEquals("no S3 error code", LogUploadLogic.s3Error("<html>AKIDSECRET</html>"))
    }

    @Test
    fun tailsAtLineStart() {
        assertEquals("abc\ndef\n", LogUploadLogic.tail("abc\ndef\n", 100))
        assertEquals("[… 8 older bytes cut]\nghi\n", LogUploadLogic.tail("abc\ndef\nghi\n", 6))
    }

    @Test
    fun mergesReasons() {
        val p = mutableListOf<Reason>()
        LogUploadLogic.merge(p, Reason.PERIODIC)
        assertEquals(listOf(Reason.PERIODIC), p)
        LogUploadLogic.merge(p, Reason.LINK_LOST)
        LogUploadLogic.merge(p, Reason.LINK_LOST)
        LogUploadLogic.merge(p, Reason.PERIODIC)
        assertEquals(listOf(Reason.LINK_LOST), p)
        LogUploadLogic.merge(p, Reason.START)
        assertEquals(listOf(Reason.LINK_LOST, Reason.START), p)
    }
}
