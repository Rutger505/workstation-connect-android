// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.logs

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (header auth, signed payload), just enough for an S3 PUT. Pure JVM, mirrors
 * the laptop's sigv4.rs. https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html
 */
object SigV4 {
    data class Credentials(val access: String, val secret: String)

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** S3 path encoding: every byte except unreserved and '/'. */
    fun encodePath(path: String): String = buildString {
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~/") append(ch)
            else append("%%%02X".format(c))
        }
    }

    /**
     * `Authorization` header value. [headers]: lowercase names, trimmed values, incl. `host` and
     * `x-amz-date`; [uri] already encoded ([encodePath]); [amzDate] YYYYMMDDTHHMMSSZ.
     */
    fun authorization(
        creds: Credentials, region: String, service: String, amzDate: String,
        method: String, uri: String, query: String, headers: List<Pair<String, String>>, payloadSha256: String,
    ): String {
        val sorted = headers.sortedBy { it.first }
        val canonicalHeaders = sorted.joinToString("") { "${it.first}:${it.second}\n" }
        val signed = sorted.joinToString(";") { it.first }
        val canonical = "$method\n$uri\n$query\n$canonicalHeaders\n$signed\n$payloadSha256"
        val date = amzDate.substring(0, 8)
        val scope = "$date/$region/$service/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        var key = hmac("AWS4${creds.secret}".toByteArray(), date)
        for (part in listOf(region, service, "aws4_request")) key = hmac(key, part)
        val signature = hex(hmac(key, toSign))
        return "AWS4-HMAC-SHA256 Credential=${creds.access}/$scope, SignedHeaders=$signed, Signature=$signature"
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data.toByteArray()) }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
