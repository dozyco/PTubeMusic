/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.automotive

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 차량용 설정 액티비티
 * 쿠키 직접 입력 방식으로 로그인을 처리한다.
 */
@AndroidEntryPoint
class AutomotiveSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetrolistTheme {
                SettingsScreen(
                    onCloseClick = { finish() },
                    onSaveCookie = { cookie -> saveCookie(cookie) },
                    onLogoutClick = { performLogout() }
                )
            }
        }
    }

    /**
     * 쿠키 문자열을 표준 형식으로 정규화한다.
     * 입력으로 들어오는 쿠키는 다양한 형식을 가질 수 있다:
     * - "key1=val1;key2=val2"          (공백 없음)
     * - "key1=val1; key2=val2"         (표준 형식)
     * - "key1=val1\nkey2=val2"         (줄바꿈)
     * - 따옴표가 포함된 경우
     * 이를 모두 표준 "key1=val1; key2=val2" 형식으로 변환한다.
     */
    private fun normalizeCookie(cookie: String): String {
        return cookie
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("\r", "")
            .replace("\n", ";")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .joinToString("; ")
    }

    private fun saveCookie(cookie: String) {
        val normalizedCookie = normalizeCookie(cookie)
        if (normalizedCookie.isEmpty()) {
            Toast.makeText(this, "쿠키를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        if ("SAPISID" !in parseCookieString(normalizedCookie)) {
            Toast.makeText(this, "올바른 쿠키가 아닙니다. SAPISID가 포함되어야 합니다.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[InnerTubeCookieKey] = normalizedCookie
                }

                YouTube.cookie = normalizedCookie

                val accountInfoResult = withContext(Dispatchers.IO) {
                    YouTube.accountInfo()
                }

                accountInfoResult.onSuccess { accountInfo ->
                    dataStore.edit { prefs ->
                        accountInfo.name?.let { prefs[AccountNameKey] = it }
                        accountInfo.email?.let { prefs[AccountEmailKey] = it }
                        accountInfo.channelHandle?.let { prefs[AccountChannelHandleKey] = it }
                    }
                    Toast.makeText(
                        this@AutomotiveSettingsActivity,
                        "로그인 성공: ${accountInfo.name ?: accountInfo.email ?: "계정 확인됨"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@AutomotiveSettingsActivity,
                        "계정 정보 실패: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                val visitorDataResult = withContext(Dispatchers.IO) {
                    YouTube.visitorData()
                }

                visitorDataResult.onSuccess { visitorData ->
                    dataStore.edit { prefs ->
                        prefs[VisitorDataKey] = visitorData
                    }
                }

                recreate()
            } catch (e: Exception) {
                Toast.makeText(
                    this@AutomotiveSettingsActivity,
                    "로그인 실패: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun performLogout() {
        lifecycleScope.launch {
            dataStore.edit { prefs ->
                prefs.remove(InnerTubeCookieKey)
                prefs.remove(AccountNameKey)
                prefs.remove(AccountEmailKey)
                prefs.remove(AccountChannelHandleKey)
                prefs.remove(VisitorDataKey)
            }
            YouTube.cookie = null
            Toast.makeText(this@AutomotiveSettingsActivity, "로그아웃 완료", Toast.LENGTH_SHORT).show()
            recreate()
        }
    }
}

@Composable
private fun SettingsScreen(
    onCloseClick: () -> Unit,
    onSaveCookie: (String) -> Unit,
    onLogoutClick: () -> Unit,
) {
    val context = LocalContext.current

    val isLoggedIn = remember {
        "SAPISID" in parseCookieString(
            context.dataStore.get(InnerTubeCookieKey, "")
        )
    }

    val accountName = remember {
        context.dataStore.get(AccountNameKey, "")
    }
    val accountEmail = remember {
        context.dataStore.get(AccountEmailKey, "")
    }

    var cookieInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PTubeMusic 설정",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "계정",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isLoggedIn) {
                                    accountName.ifEmpty { accountEmail.ifEmpty { "로그인됨" } }
                                } else {
                                    "로그인되지 않음"
                                },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isLoggedIn) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onLogoutClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(
                                text = "로그아웃",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (!isLoggedIn) {
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "쿠키로 로그인",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "PC 브라우저에서 music.youtube.com 로그인 후, " +
                                    "F12 → Application → Cookies 에서 쿠키를 복사해 붙여넣으세요. " +
                                    "공백 없는 형식도 자동으로 인식됩니다.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = { cookieInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = {
                                Text(
                                    text = "예: SAPISID=xxxxx; HSID=xxxxx; ...",
                                    fontSize = 13.sp
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onSaveCookie(cookieInput) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = "저장하고 로그인",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "앱 정보",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    InfoRow(label = "앱 이름", value = "PTubeMusic")
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "버전", value = BuildConfig.VERSION_NAME)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "더 많은 설정은 휴대폰 앱에서 가능합니다",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
