package com.metrolist.music.automotive

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AutomotiveLoginActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 검은 배경 컨테이너
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }

            // 쿠키 활성화
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false  // 모든 URL을 WebView에서 처리
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("AUTO_LOGIN", "Page loaded: $url")

                    // YouTube Music에 도착하면 로그인 완료로 판단
                    if (url?.startsWith("https://music.youtube.com") == true ||
                        url?.contains("youtube.com") == true) {
                        checkAndSaveLogin(url)
                    }
                }
            }

            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")
        }

        container.addView(webView)
        setContentView(container)
    }

    private fun checkAndSaveLogin(url: String) {
        val cookies = CookieManager.getInstance().getCookie(url) ?: return
        Log.d("AUTO_LOGIN", "Cookies received, length: ${cookies.length}")

        // SAPISID가 포함되어 있으면 로그인 성공
        if ("SAPISID" in parseCookieString(cookies)) {
            Log.d("AUTO_LOGIN", "Login successful, saving cookies")
            saveCookiesAndFinish(cookies)
        }
    }

    private fun saveCookiesAndFinish(cookies: String) {
        lifecycleScope.launch {
            try {
                // 쿠키 저장
                dataStore.edit { prefs ->
                    prefs[InnerTubeCookieKey] = cookies
                }
                YouTube.cookie = cookies

                // 계정 정보 추가로 가져오기
                YouTube.accountInfo().onSuccess { accountInfo ->
                    dataStore.edit { prefs ->
                        prefs[AccountNameKey] = accountInfo.name
                        accountInfo.email?.let { prefs[AccountEmailKey] = it }
                        accountInfo.channelHandle?.let { prefs[AccountChannelHandleKey] = it }
                    }
                    Log.d("AUTO_LOGIN", "Account info saved: ${accountInfo.name}")
                }

                // VisitorData 가져오기
                YouTube.visitorData().onSuccess { visitorData ->
                    dataStore.edit { prefs ->
                        prefs[VisitorDataKey] = visitorData
                    }
                    YouTube.visitorData = visitorData
                }

                Log.d("AUTO_LOGIN", "All data saved, finishing activity")
                finish()
            } catch (e: Exception) {
                Log.e("AUTO_LOGIN", "Failed to save login info", e)
                finish()
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    }
}