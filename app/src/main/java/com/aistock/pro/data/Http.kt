package com.aistock.pro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 외부 라이브러리 없이 HttpURLConnection 으로 구현한 네트워크 도우미.
 * - 연결/응답 시간 제한
 * - 429(호출 과다)·5xx·네트워크 오류 시 자동 재시도 (지수 대기)
 * - 사용자에게 보여줄 한국어 오류 메시지 변환
 */
object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    class HttpError(val code: Int, msg: String) : IOException(msg)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), retries: Int = 2): String =
        request("GET", url, null, headers, retries)

    suspend fun post(url: String, body: String, headers: Map<String, String>, retries: Int = 1): String =
        request("POST", url, body, headers, retries)

    private suspend fun request(
        method: String, url: String, body: String?, headers: Map<String, String>, retries: Int,
    ): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0..retries) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = if (method == "POST") 60_000 else 15_000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
                    setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    return@withContext conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val err = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(300)
                } catch (_: Exception) { null }
                val e = HttpError(code, "HTTP $code ${err ?: ""}")
                if (code == 429 || code >= 500) {
                    last = e
                } else {
                    throw e
                }
            } catch (e: HttpError) {
                if (e.code == 429 || e.code >= 500) last = e else throw e
            } catch (e: IOException) {
                last = e
            } finally {
                conn?.disconnect()
            }
            if (attempt < retries) delay(800L * (attempt + 1) * (attempt + 1))
        }
        throw last ?: IOException("알 수 없는 네트워크 오류")
    }

    /** 예외를 사용자용 한국어 문장으로 */
    fun friendly(e: Throwable): String = when (e) {
        is DataException -> e.message ?: "데이터 오류"
        is UnknownHostException -> "인터넷에 연결되어 있지 않습니다. 와이파이/데이터 연결을 확인한 뒤 다시 시도하세요."
        is SocketTimeoutException -> "서버 응답이 늦습니다. 잠시 후 다시 시도하세요."
        is HttpError -> when {
            e.code == 429 -> "데이터 제공처의 무료 호출 한도에 잠시 걸렸습니다. 1~2분 뒤 다시 시도하세요."
            e.code == 401 || e.code == 403 -> "데이터 제공처가 요청을 거부했습니다 (API 키 확인 필요 또는 일시 차단). 잠시 후 다시 시도하세요."
            e.code == 404 -> "해당 종목 데이터를 찾을 수 없습니다. 종목코드를 확인하세요."
            else -> "데이터 서버 오류(${e.code}). 잠시 후 다시 시도하세요."
        }
        is IOException -> "네트워크 오류가 발생했습니다. 연결 상태를 확인하고 다시 시도하세요."
        else -> "처리 중 오류가 발생했습니다: ${e.message ?: e.javaClass.simpleName}"
    }
}
