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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.OutlinedButton
import com.metrolist.music.automotive.LogBuffer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.extensions.toEnum
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
                    onLogoutClick = { performLogout() },
                    onSelectAudioQuality = { quality -> saveAudioQuality(quality) }
                )
            }
        }
    }

    /**
     * 쿠키 문자열을 표준 형식으로 정규화한다.
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

    /**
     * 음질 설정을 저장한다. 저장된 값은 재생 시 MusicService 가 사용한다.
     */
    private fun saveAudioQuality(quality: AudioQuality) {
        lifecycleScope.launch {
            dataStore.edit { prefs ->
                prefs[AudioQualityKey] = quality.name
            }
            Toast.makeText(
                this@AutomotiveSettingsActivity,
                "음질 설정: ${quality.name}",
                Toast.LENGTH_SHORT
            ).show()
            recreate()
        }
    }
}

/**
 * 쿠키 상태를 나타내는 값.
 */
private enum class CookieStatus {
    CHECKING,   // 확인 중
    VALID,      // 정상 (쿠키 유효)
    EXPIRED,    // 만료됨 (쿠키는 있으나 인증 실패)
    NONE        // 쿠키 없음 (로그인 안 함)
}

@Composable
private fun SettingsScreen(
    onCloseClick: () -> Unit,
    onSaveCookie: (String) -> Unit,
    onLogoutClick: () -> Unit,
    onSelectAudioQuality: (AudioQuality) -> Unit,
) {
    val context = LocalContext.current

    val savedCookie = remember {
        context.dataStore.get(InnerTubeCookieKey, "")
    }
    val hasCookie = remember {
        "SAPISID" in parseCookieString(savedCookie)
    }

    val accountName = remember {
        context.dataStore.get(AccountNameKey, "")
    }
    val accountEmail = remember {
        context.dataStore.get(AccountEmailKey, "")
    }

    val currentAudioQuality = remember {
        context.dataStore.get(AudioQualityKey, AudioQuality.AUTO.name)
            .toEnum(AudioQuality.AUTO)
    }

    var cookieInput by remember { mutableStateOf("") }

    // 쿠키 상태: 처음엔 쿠키 유무에 따라 CHECKING 또는 NONE
    var cookieStatus by remember {
        mutableStateOf(if (hasCookie) CookieStatus.CHECKING else CookieStatus.NONE)
    }

    // 화면이 뜰 때 쿠키가 있으면 실제로 YouTube 에 요청을 보내 유효한지 검사한다.
    LaunchedEffect(hasCookie) {
        if (hasCookie) {
            cookieStatus = CookieStatus.CHECKING
            val result = withContext(Dispatchers.IO) {
                try {
                    YouTube.cookie = savedCookie
                    YouTube.accountInfo()
                } catch (e: Exception) {
                    Result.failure<Any>(e)
                }
            }
            cookieStatus = if (result.isSuccess) {
                CookieStatus.VALID
            } else {
                // 실패 원인 구분: 진짜 인증 에러 (401/403) 일 때만 EXPIRED 로 판정.
                // 네트워크 에러, 타임아웃, 기타는 VALID 유지 (쿠키 자체는 멀쩡할 가능성 높음).
                val error = result.exceptionOrNull()
                val errorMessage = error?.message?.lowercase() ?: ""
                val isAuthError = errorMessage.contains("401") ||
                        errorMessage.contains("403") ||
                        errorMessage.contains("unauthorized") ||
                        errorMessage.contains("forbidden")
                LogBuffer.log("쿠키 검증 실패: isAuthError=$isAuthError, message=${error?.message}")
                if (isAuthError) {
                    CookieStatus.EXPIRED
                } else {
                    // 네트워크 에러 등 → 일단 VALID 로 유지 (쿠키 만료라고 잘못 표시 안 함)
                    CookieStatus.VALID
                }
            }
        } else {
            cookieStatus = CookieStatus.NONE
        }
    }

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
                                text = if (hasCookie) {
                                    accountName.ifEmpty { accountEmail.ifEmpty { "로그인됨" } }
                                } else {
                                    "로그인되지 않음"
                                },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 쿠키 상태 표시 영역
                    when (cookieStatus) {
                        CookieStatus.CHECKING -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "로그인 상태 확인 중...",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        CookieStatus.VALID -> {
                            Text(
                                text = "✓ 로그인 정상 (쿠키 유효)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF2E7D32) // 초록
                            )
                        }
                        CookieStatus.EXPIRED -> {
                            Column {
                                Text(
                                    text = "⚠ 쿠키 만료됨 — 재로그인 필요",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC62828) // 빨강
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "라이브러리/추천이 안 보이면 아래에서 새 쿠키로 다시 로그인하세요.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        CookieStatus.NONE -> {
                            Text(
                                text = "로그인되지 않음",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (hasCookie) {
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

            // 쿠키 입력 카드: 로그인 안 됐거나 만료됐을 때 노출
            if (!hasCookie || cookieStatus == CookieStatus.EXPIRED) {
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
                            text = if (cookieStatus == CookieStatus.EXPIRED) "쿠키 다시 입력" else "쿠키로 로그인",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "PC 브라우저 시크릿창에서 music.youtube.com 로그인 후, " +
                                    "F12 → Network → browse 요청의 Cookie 값을 복사해 붙여넣으세요. " +
                                    "쿠키를 뽑은 시크릿창은 로그아웃하지 말고 그냥 닫으면 오래 유지됩니다.",
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

            // 음질 설정 카드
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
                        text = "음질",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "현재: ${audioQualityLabel(currentAudioQuality)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val qualities = listOf(
                        AudioQuality.AUTO,
                        AudioQuality.LOW,
                        AudioQuality.HIGH,
                        AudioQuality.VERY_HIGH,
                    )

                    qualities.forEach { quality ->
                        val selected = quality == currentAudioQuality
                        Button(
                            onClick = { onSelectAudioQuality(quality) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = if (selected) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        ) {
                            Text(
                                text = audioQualityLabel(quality) + if (selected) "  ✓" else "",
                                fontSize = 16.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // 로그 카드 (차량에서 logcat 대신 사용)
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
                        text = "디버그 로그",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "차량에서 발생한 내부 로그입니다. 아래 '새로고침'을 눌러 최신 상태를 가져오세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 로그 라인들을 보관하는 상태. 새로고침 버튼이 LogBuffer 에서 다시 읽어온다.
                    val logLines = remember { mutableStateListOf<String>().apply { addAll(LogBuffer.getAll()) } }

                    Text(
                        text = "총 ${logLines.size}줄",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 로그 영역: 스크롤 가능한 작은 박스. 최근 줄이 아래로 가도록 그대로 표시.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            if (logLines.isEmpty()) {
                                Text(
                                    text = "아직 기록된 로그가 없습니다.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                logLines.forEach { line ->
                                    Text(
                                        text = line,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 새로고침: 메모리에서 다시 읽어와 화면 갱신
                        Button(
                            onClick = {
                                logLines.clear()
                                logLines.addAll(LogBuffer.getAll())
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "새로고침", fontSize = 14.sp)
                        }

                        // 비우기: 버퍼와 화면 둘 다 비움
                        OutlinedButton(
                            onClick = {
                                LogBuffer.clear()
                                logLines.clear()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "비우기", fontSize = 14.sp)
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

                    InfoRow(label = "버전", value = BuildConfig.VERSION_NAME)

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "오픈소스 정보",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    InfoRow(label = "기반 프로젝트", value = "Metrolist")
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "원본 저장소", value = "github.com/mostafaalagamy/Metrolist")
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "라이선스", value = "GPL-3.0")

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "이 앱은 폴 오픈소스 프로젝트 Metrolist를 폴스타4 환경에 맞게 수정한 비공식 버전입니다. 수정된 소스 코드는 요청 시 제공됩니다. Claude AI를 이용하여 수정하였으며 언제든지 앱 작동이 안될 가능성이 있습니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "한국 폴스타4 오너를 위하여 만들었습니다. 네이버 폴스타 동호회 폴스타 클루부 by염발 ",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 음질 enum 을 사람이 읽기 좋은 라벨로 변환한다.
 */
private fun audioQualityLabel(quality: AudioQuality): String = when (quality) {
    AudioQuality.AUTO -> "자동 (Auto)"
    AudioQuality.LOW -> "낮음 (Low)"
    AudioQuality.HIGH -> "높음 (High)"
    AudioQuality.VERY_HIGH -> "매우 높음 (Very High)"
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