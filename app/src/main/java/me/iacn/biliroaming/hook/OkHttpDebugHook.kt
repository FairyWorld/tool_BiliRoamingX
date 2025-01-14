package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import java.io.Closeable
import java.io.EOFException
import java.lang.reflect.Proxy

class OkHttpDebugHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    private val gzipSourceClass by Weak { "okio.GzipSource" from mClassLoader }
    private val bufferClass by Weak { "okio.Buffer" from mClassLoader }
    private val callbackClass by Weak { "okhttp3.Callback" from mClassLoader }

    override fun startHook() {
        instance.realCallClass?.hookAfterMethod("execute") { param ->
            val response = param.result
            logResponse(response, false)
        }
        instance.realCallClass?.hookBeforeMethod("enqueue", callbackClass) { param ->
            val callback = param.args[0]
            param.args[0] = Proxy.newProxyInstance(
                callback.javaClass.classLoader,
                arrayOf(callbackClass)
            ) { _, m, args ->
                if (m.name == "onResponse") {
                    val response = args[1]
                    logResponse(response, true)
                }
                m(callback, *args)
            }
        }
    }

    private fun logResponse(response: Any?, async: Boolean) {
        response ?: return
        val gzipSourceClass = gzipSourceClass ?: return
        val bufferClass = bufferClass ?: return
        val request = response.callMethod("request")
        val method = request?.callMethod("method")
        val url = request?.callMethod("url")?.toString()
        val protocol = response.callMethod("protocol")
        Log.d("############################## ${if (async) "async" else "blocking"}")
        Log.d("--> $method $url $protocol")
        if (bodyHasUnknownEncoding(response.callMethod("headers"))) {
            Log.d("<-- END HTTP (encoded body omitted)")
            return
        }
        val responseBody = response.callMethod("body")
        val source = responseBody?.callMethod("source")
        source?.callMethod("request", Long.MAX_VALUE)
        var buffer = source?.callMethod("buffer") ?: return
        var gzippedLength: Long? = null
        val contentEncoding = response.callMethod("header", "Content-Encoding")?.toString() ?: ""
        if ("gzip".equals(contentEncoding, ignoreCase = true)) {
            gzippedLength = buffer.callMethodAs<Long>("size")
            (gzipSourceClass.new(buffer.callMethod("clone")) as Closeable).use { gzippedResponseBody ->
                buffer = bufferClass.new()
                buffer.callMethod("writeAll", gzippedResponseBody)
            }
        }
        val size = buffer.callMethodAs<Long>("size")
        if (!buffer.isProbablyUtf8()) {
            Log.d("")
            Log.d("<-- END HTTP (binary $size-byte body omitted)")
            return
        }
        Log.d("")
        val responseString = buffer.callMethod("clone")
            ?.callMethod("readString", Charsets.UTF_8)
        Log.d(responseString)

        if (gzippedLength != null) {
            Log.d("<-- END HTTP ($size-byte, $gzippedLength-gzipped-byte body)")
        } else {
            Log.d("<-- END HTTP ($size-byte body)")
        }
        Log.d("##############################")
    }

    private fun bodyHasUnknownEncoding(headers: Any?): Boolean {
        val contentEncoding =
            headers?.callMethodAs<String?>("get", "Content-Encoding") ?: return false
        return !contentEncoding.equals("identity", ignoreCase = true) &&
                !contentEncoding.equals("gzip", ignoreCase = true)
    }

    private fun Any.isProbablyUtf8(): Boolean {
        try {
            val prefix = bufferClass?.new() ?: return false
            val byteCount = callMethodAs<Long>("size").coerceAtMost(64)
            callMethod("copyTo", prefix, 0, byteCount)
            for (i in 0 until 16) {
                if (prefix.callMethodAs("exhausted")) {
                    break
                }
                val codePoint = prefix.callMethodAs<Int>("readUtf8CodePoint")
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false
                }
            }
            return true
        } catch (_: EOFException) {
            return false // Truncated UTF-8 sequence.
        }
    }
}
