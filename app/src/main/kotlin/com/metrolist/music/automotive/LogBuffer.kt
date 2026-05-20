package com.metrolist.music.automotive

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 차량에서 logcat을 볼 수 없을 때 사용하는 메모리 로그 버퍼.
 * 앱 어디서든 LogBuffer.log("내용") 으로 기록.
 * AutomotiveSettingsActivity 의 로그 화면에서 LogBuffer.getAll() 로 읽음.
 *
 * 최대 500줄까지만 보관 (오래된 것부터 자동 삭제).
 * 앱 재시작하면 로그는 사라짐 (메모리 기반).
 */
object LogBuffer {
    private const val MAX_LINES = 500
    private val buffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 로그 한 줄 기록. 동시에 안드로이드 logcat 에도 "PTUBE" 태그로 출력. */
    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $message"
        buffer.add(line)
        // 너무 많이 쌓이면 오래된 것부터 버림
        while (buffer.size > MAX_LINES) {
            buffer.pollFirst()
        }
        // 일반 logcat 에도 같이 출력 (에뮬에서 확인 가능)
        Log.d("PTUBE", message)
    }

    /** 지금까지 쌓인 로그 전체를 한 줄씩 리스트로 반환 (오래된 것 → 최신 순서). */
    fun getAll(): List<String> = buffer.toList()

    /** 전체를 하나의 문자열로 (공유/복사 용도). */
    fun getAllAsText(): String = buffer.joinToString("\n")

    /** 로그 다 비우기. */
    fun clear() {
        buffer.clear()
    }

    /** 현재 줄 수. */
    fun size(): Int = buffer.size
}