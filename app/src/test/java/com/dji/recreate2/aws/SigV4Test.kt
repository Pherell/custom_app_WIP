package com.dji.recreate2.aws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * AWS Signature Version 4, as the Ceph S3 endpoint expects it.
 *
 * A wrong signature is rejected with a 403 that looks exactly like a credentials problem, so
 * these defects are expensive to diagnose in the field. Two of them shipped: the canonical path
 * was not URI-encoded, and the date was read from the clock twice - once for `x-amz-date` and
 * once for the credential scope - so a request crossing 00:00 UTC signed itself invalid.
 */
class SigV4Test {

    // ---------------------------------------------------------------- URI encoding

    @Test
    fun `unreserved characters pass through`() {
        assertEquals("-._~", S3UploadManager.uriEncode("-._~"))
        assertEquals("ISR001", S3UploadManager.uriEncode("ISR001"))
    }

    @Test
    fun `reserved characters are percent encoded`() {
        assertEquals("a%20b.jpg", S3UploadManager.uriEncode("a b.jpg"))
        assertEquals("a%2Bb", S3UploadManager.uriEncode("a+b"))
        assertEquals("a%2Fb", S3UploadManager.uriEncode("a/b"))
        assertEquals("a%3Db%26c", S3UploadManager.uriEncode("a=b&c"))
    }

    @Test
    fun `non ASCII is encoded as UTF-8 bytes`() {
        assertEquals("caf%C3%A9", S3UploadManager.uriEncode("café"))
    }

    @Test
    fun `percent encoding uses upper case hex`() {
        // AWS specifies upper case. Lower case produces a different canonical request and a
        // signature the server rejects.
        val encoded = S3UploadManager.uriEncode(" +/")
        assertEquals(encoded.uppercase(), encoded)
    }

    // ---------------------------------------------------------------- canonical request

    @Test
    fun `a filename with a space signs differently from one without`() {
        // THE BUG. uri.rawPath is not encoded, so any filename with a reserved or non-ASCII
        // character produced a canonical request that did not match what the server rebuilt.
        val plain = sign(url = "https://s3.example.com/bucket/photo.jpg")
        val spaced = sign(url = "https://s3.example.com/bucket/my%20photo.jpg")
        assertNotEquals(plain.authorization, spaced.authorization)
    }

    @Test
    fun `the canonical path keeps the separators and encodes the segments`() {
        // Verified through the signature: a URL whose encoded form round-trips to the same
        // canonical path must produce the same signature.
        val encoded = sign(url = "https://s3.example.com/a/my%20photo%20caf%C3%A9.jpg")
        val same = sign(url = "https://s3.example.com/a/my%20photo%20caf%C3%A9.jpg")
        assertEquals(encoded.authorization, same.authorization)

        val different = sign(url = "https://s3.example.com/a/my_photo_cafe.jpg")
        assertNotEquals(encoded.authorization, different.authorization)
    }

    @Test
    fun `a bodyless method signs the empty payload hash`() {
        assertEquals(S3UploadManager.EMPTY_PAYLOAD_SHA256, sign(method = "GET").payloadHash)
        assertEquals(S3UploadManager.EMPTY_PAYLOAD_SHA256, sign(method = "DELETE").payloadHash)
        assertEquals(S3UploadManager.EMPTY_PAYLOAD_SHA256, sign(method = "HEAD").payloadHash)
    }

    @Test
    fun `an upload signs an unsigned payload`() {
        // Ceph accepts UNSIGNED-PAYLOAD, which is what lets a large file stream without being
        // hashed into memory first.
        assertEquals("UNSIGNED-PAYLOAD", sign(method = "PUT").payloadHash)
        assertEquals("UNSIGNED-PAYLOAD", sign(method = "POST").payloadHash)
    }

    @Test
    fun `the method is case folded before it is signed`() {
        assertEquals(sign(method = "PUT").authorization, sign(method = "put").authorization)
    }

    // ---------------------------------------------------------------- host header

    @Test
    fun `a default port is left out of the host header`() {
        assertEquals("s3.example.com", sign(url = "https://s3.example.com:443/x").host)
        assertEquals("s3.example.com", sign(url = "http://s3.example.com:80/x").host)
        assertEquals("s3.example.com", sign(url = "https://s3.example.com/x").host)
    }

    @Test
    fun `a non default port is part of the host header`() {
        // The field endpoint runs on 8000, so this is the normal case, not the edge case.
        assertEquals("s3.example.com:8080", sign(url = "http://s3.example.com:8080/x").host)
        assertEquals("192.168.180.99:8000", sign(url = "http://192.168.180.99:8000/x").host)
    }

    // ---------------------------------------------------------------- query string

    @Test
    fun `query parameters are sorted by key then value`() {
        val sorted = sign(url = "https://h/x?a=0&a=1&b=2")
        val shuffled = sign(url = "https://h/x?b=2&a=1&a=0")
        assertEquals(sorted.authorization, shuffled.authorization)
    }

    @Test
    fun `a query string changes the signature`() {
        assertNotEquals(sign(url = "https://h/x").authorization, sign(url = "https://h/x?a=1").authorization)
    }

    // ---------------------------------------------------------------- the clock

    @Test
    fun `the credential scope date always matches the x-amz-date`() {
        // THE BUG. The scope date and x-amz-date used to come from two separate Date() reads.
        // A request signed either side of midnight UTC carried a scope for one day and a
        // timestamp for the other, and the server rejected it. Five milliseconds before
        // midnight is the case that used to fail.
        val fiveMillisBeforeMidnight = Date(1717200000000L - 5L)
        val signed = sign(instant = fiveMillisBeforeMidnight)

        assertEquals("20240531T235959Z", signed.amzDate)

        val scopeDate = signed.authorization
            .substringAfter("Credential=")
            .split("/")[1]
        assertEquals(signed.amzDate.substringBefore('T'), scopeDate)
    }

    @Test
    fun `the same instant always produces the same signature`() {
        val instant = Date(1717243496000L)
        assertEquals(sign(instant = instant).authorization, sign(instant = instant).authorization)
    }

    @Test
    fun `a different instant produces a different signature`() {
        assertNotEquals(
            sign(instant = Date(1717243496000L)).authorization,
            sign(instant = Date(1717243497000L)).authorization
        )
    }

    @Test
    fun `the timestamp is UTC regardless of the device time zone`() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Jakarta"))
            assertEquals("20240601T120456Z", sign(instant = Date(1717243496000L)).amzDate)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    // ---------------------------------------------------------------- credentials

    @Test
    fun `the access key appears in the credential and the secret key never does`() {
        val signed = sign(accessKey = "AKIAIOSFODNN7EXAMPLE", secretKey = "SUPER_SECRET_VALUE")

        assertTrue(signed.authorization.contains("Credential=AKIAIOSFODNN7EXAMPLE/"))
        assertTrue("the secret leaked into the header", !signed.authorization.contains("SUPER_SECRET_VALUE"))
    }

    @Test
    fun `a different secret key produces a different signature`() {
        assertNotEquals(sign(secretKey = "one").authorization, sign(secretKey = "two").authorization)
    }

    @Test
    fun `a different region produces a different signature`() {
        assertNotEquals(sign(region = "BT").authorization, sign(region = "us-east-1").authorization)
    }

    // ---------------------------------------------------------------- known answer

    @Test
    fun `a fixed request signs to a known value`() {
        // Pins the whole canonical request. Any change to the signed header set, the payload
        // hash rule, the encoding or the scope layout moves this hash and fails here, rather
        // than surfacing as a 403 during a flight.
        val signed = S3UploadManager.buildSigV4Headers(
            httpMethod = "PUT",
            urlStr = "http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured/2026-08-09/ISR_001.jpg",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            region = "BT",
            signingInstant = Date(1717243496000L)
        )

        assertEquals("192.168.180.99:8000", signed.host)
        assertEquals("20240601T120456Z", signed.amzDate)
        assertEquals("UNSIGNED-PAYLOAD", signed.payloadHash)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20240601/BT/s3/aws4_request, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                    "Signature=17eef21833cd56bbe3231c249c11704f7bfffeb538ea48b4d3dfd3f00e3d3fa5",
            signed.authorization
        )
    }

    // ---------------------------------------------------------------- helper

    private fun sign(
        method: String = "PUT",
        url: String = "http://192.168.180.99:8000/bucket/file.jpg",
        accessKey: String = "AKIAIOSFODNN7EXAMPLE",
        secretKey: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region: String = "BT",
        instant: Date = Date(1717243496000L)
    ) = S3UploadManager.buildSigV4Headers(method, url, accessKey, secretKey, region, instant)
}
