package com.metrolist.music.automotive

import java.io.PrintWriter
import java.io.StringWriter

/**
 * 앱 어디서든 잡히지 않은 예외(crash)가 나면 LogBuffer 에 스택 트레이스를 기록한다.
 * 차량에서 logcat 을 볼 수 없을 때, 차량 설정 화면의 로그 카드에서 크래시 원인을 직접 확인할 수 있게 한다.
 *
 * 사용법: Application.onCreate() 에서 CrashLogger.install() 한 번만 호출.
 */
object CrashLogger {

    private var installed = false

    fun install() {
        if (installed) return
        installed = true

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                LogBuffer.log("=== CRASH on thread '${thread.name}' ===")
                LogBuffer.log(sw.toString())
                LogBuffer.log("=== END CRASH ===")
            } catch (_: Throwable) {
                // 크래시 핸들러 안에서 또 터지면 안 되니까 조용히 무시
            }

            // 안드로이드 기본 동작(앱 종료) 은 그대로 진행
            previousHandler?.uncaughtException(thread, throwable)
        }

        LogBuffer.log("CrashLogger installed")
    }
}