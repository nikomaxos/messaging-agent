package com.messagingagent.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messagingagent.android.data.GroupSummary
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.data.RegistrationRepository
import com.messagingagent.android.data.RegistrationState
import com.messagingagent.android.service.MessagingAgentService
import com.messagingagent.android.service.WebSocketRelayClient
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository,
    private val registrationRepo: RegistrationRepository,
    val wsClient: WebSocketRelayClient
) : ViewModel() {

    val registrationState = prefs.registrationFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            RegistrationState(null, null, null, null, null, null))

    // Keep URL and name in ViewModel so they survive step transitions
    // without relying on DataStore timing
    var pendingUrl  by mutableStateOf("")
    var pendingName by mutableStateOf("")

    var groups   by mutableStateOf<List<GroupSummary>>(emptyList())
    var loading  by mutableStateOf(false)
    var error    by mutableStateOf<String?>(null)
    var step     by mutableStateOf(Step.URL)

    enum class Step { URL, GROUP_PICK, DONE }

    fun fetchGroups(url: String, name: String) {
        viewModelScope.launch {
            loading = true
            error = null
            pendingUrl  = url.trimEnd('/')
            pendingName = name.trim()
            registrationRepo.fetchGroups(url)
                .onSuccess {
                    groups = it
                    step = Step.GROUP_PICK
                }
                .onFailure { error = "Could not reach backend at ${url.trimEnd('/')}/api/devices/register/groups\n${it.message}" }
            loading = false
        }
    }

    fun register(selectedGroup: GroupSummary) {
        viewModelScope.launch {
            loading = true
            error = null
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            registrationRepo.registerDevice(pendingUrl, pendingName, selectedGroup.id, androidId)
                .onSuccess { step = Step.DONE }
                .onFailure { error = "Registration failed at ${pendingUrl}/api/devices/register\n${it.message}" }
            loading = false
        }
    }
}

@AndroidEntryPoint
class SetupActivity : ComponentActivity() {

    private val vm: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MessagingAgentTheme {
                SetupScreen(vm = vm, onStart = {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this, Intent(this, MessagingAgentService::class.java)
                    )
                })
            }
        }
    }
}

@Composable
fun SetupScreen(vm: SetupViewModel, onStart: () -> Unit) {
    val regState by vm.registrationState.collectAsState()

    LaunchedEffect(regState.isRegistered) {
        if (regState.isRegistered) {
            vm.pendingUrl  = regState.backendUrl ?: ""
            vm.pendingName = regState.deviceName ?: ""
            vm.step = SetupViewModel.Step.DONE
            onStart() // Automatically start the service when opening the app if already registered
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D0D1A), Color(0xFF1A1A2E))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Messaging Agent", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Device Registration", fontSize = 14.sp, color = Color(0xFF8899AA))
            Spacer(Modifier.height(4.dp))

            vm.error?.let {
                Surface(color = Color(0xFF4A1010), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("⚠ $it", color = Color(0xFFFF6B6B), fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }

            when (vm.step) {
                SetupViewModel.Step.URL        -> UrlStep(vm)
                SetupViewModel.Step.GROUP_PICK -> GroupPickStep(vm)
                SetupViewModel.Step.DONE       -> DoneStep(regState, onStart, vm)
            }
        }
    }
}

@Composable
fun UrlStep(vm: SetupViewModel) {
    // Pre-fill with what's already in the ViewModel (survives recompose)
    var url  by remember { mutableStateOf(vm.pendingUrl) }
    var name by remember { mutableStateOf(vm.pendingName) }

    AgentTextField("Device Name", name, { name = it })
    Spacer(Modifier.height(4.dp))
    AgentTextField("Backend URL  (e.g. http://192.168.1.10:9090)", url, { url = it })

    Button(
        onClick = { vm.fetchGroups(url, name) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
        enabled = url.isNotBlank() && name.isNotBlank() && !vm.loading
    ) {
        if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text("Fetch Groups →", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GroupPickStep(vm: SetupViewModel) {
    var selected by remember { mutableStateOf<GroupSummary?>(null) }

    Text("Select your Virtual SMSC Group:", fontSize = 13.sp, color = Color(0xFF8899AA))
    Text("Registering as: ${vm.pendingName}  •  ${vm.pendingUrl}",
        fontSize = 11.sp, color = Color(0xFF4A5568))

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
               modifier = Modifier.heightIn(max = 300.dp)) {
        items(vm.groups) { grp ->
            val isSelected = selected?.id == grp.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = grp }
                    .border(1.dp,
                        if (isSelected) Color(0xFF6366F1) else Color(0xFF2D2D4A),
                        RoundedCornerShape(12.dp)),
                color = if (isSelected) Color(0xFF1A1A4A) else Color(0xFF111126),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(grp.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    grp.description?.let {
                        Text(it, color = Color(0xFF8899AA), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { vm.step = SetupViewModel.Step.URL; vm.error = null },
            shape = RoundedCornerShape(12.dp)
        ) { Text("← Back", color = Color(0xFF8899AA)) }

        Button(
            onClick = { selected?.let { vm.register(it) } },
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            enabled = selected != null && !vm.loading
        ) {
            if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Register Device", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DoneStep(state: RegistrationState, onStart: () -> Unit, vm: SetupViewModel) {
    // Shared HTTP client — do NOT create a new one on every poll
    val httpClient = remember {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // Live status: poll GET /api/devices/register/status/{token} every 10 s.
    // Keep last known status visible between polls (no "Checking" flash loop).
    var isOnline    by remember { mutableStateOf(false) }
    var isChecking  by remember { mutableStateOf(true) }   // only true on very first poll
    var liveDetail  by remember { mutableStateOf("") }

    LaunchedEffect(state.deviceToken) {
        val token = state.deviceToken ?: return@LaunchedEffect
        val url   = state.backendUrl ?: vm.pendingUrl
        if (url.isBlank()) return@LaunchedEffect

        while (true) {
            try {
                val reqUrl = "${url.trim()}/api/devices/register/status/$token"
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    httpClient.newCall(
                        okhttp3.Request.Builder().url(reqUrl).build()
                    ).execute()
                }
                resp.use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string() ?: ""
                        isOnline  = body.contains("\"ONLINE\"")
                        liveDetail = if (isOnline) "Connected to backend" else "Backend reachable — offline"
                    } else {
                        isOnline  = false
                        liveDetail = "Backend returned HTTP ${r.code}"
                    }
                }
            } catch (e: Exception) {
                isOnline  = false
                liveDetail = "Cannot reach backend: ${e.message}"
            }
            isChecking = false          // first poll done — status is now known
            kotlinx.coroutines.delay(10_000)
        }
    }

    // Live WebSocket connection precise uptime from the RelayClient
    val connectionStartTime by vm.wsClient.connectionStartTime.collectAsState()
    var uptimeString by remember { mutableStateOf("") }

    LaunchedEffect(connectionStartTime) {
        while (connectionStartTime != null) {
            val diffSeconds = (System.currentTimeMillis() - connectionStartTime!!) / 1000
            if (diffSeconds < 0) {
                uptimeString = "0s"
            } else {
                val days = diffSeconds / 86400
                val hours = (diffSeconds % 86400) / 3600
                val mins = (diffSeconds % 3600) / 60
                val secs = diffSeconds % 60
                uptimeString = buildString {
                    if (days > 0) append("${days}d ")
                    if (hours > 0) append("${hours}h ")
                    if (mins > 0) append("${mins}m ")
                    append("${secs}s")
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // ── Status Card ────────────────────────────────────────────────────────
    Surface(
        color  = if (isOnline) Color(0xFF0D2D1A) else Color(0xFF1A1010),
        shape  = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Pulsing status dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (isOnline) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
                Column {
                    Text(
                        when {
                            isChecking -> "● Checking…"
                            isOnline   -> "● Connected to backend"
                            else       -> "○ $liveDetail"
                        },
                        color    = when {
                            isChecking -> Color(0xFF94A3B8)
                            isOnline   -> Color(0xFF4ADE80)
                            else       -> Color(0xFF94A3B8)
                        },
                        fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                    if (connectionStartTime != null) {
                        Text("Uptime: $uptimeString", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Uptime: Offline", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF1F3A2A))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Device", color = Color(0xFF6B7280), fontSize = 11.sp)
                    Text(state.deviceName ?: vm.pendingName, color = Color.White, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Group", color = Color(0xFF6B7280), fontSize = 11.sp)
                    Text(state.groupName ?: "—", color = Color.White, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("APK Version", color = Color(0xFF6B7280), fontSize = 11.sp)
                    Text(com.messagingagent.android.BuildConfig.VERSION_NAME, color = Color(0xFF4ADE80), fontSize = 12.sp,
                         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Device ID", color = Color(0xFF6B7280), fontSize = 11.sp)
                    Text("#${state.deviceId ?: "—"}", color = Color(0xFF6B7280), fontSize = 12.sp,
                         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Backend", color = Color(0xFF6B7280), fontSize = 11.sp)
                    Text(state.backendUrl ?: vm.pendingUrl, color = Color(0xFF6B7280), fontSize = 11.sp,
                         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // ── Start/Stop Service ────────────────────────────────────────────────
    Button(
        onClick = { onStart() },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isOnline) Color(0xFF166534) else Color(0xFF6366F1)
        )
    ) {
        Text(
            if (isOnline) "▶  Service Running — Tap to Restart" else "▶  Start Relay Service",
            fontWeight = FontWeight.SemiBold
        )
    }

    // ── Re-register ───────────────────────────────────────────────────────
    OutlinedButton(
        onClick = { vm.step = SetupViewModel.Step.URL; vm.error = null },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151))
    ) {
        Text("Change Device Name / Group", color = Color(0xFF9CA3AF), fontSize = 13.sp)
    }

    // ── Connection Log ────────────────────────────────────────────────────
    val wsLog by vm.wsClient.log.collectAsState()
    var showLog by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showLog = !showLog },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2937))
    ) {
        Text(
            if (showLog) "▲ Hide Connection Log" else "▼ Show Connection Log (${wsLog.size} events)",
            color = Color(0xFF6B7280), fontSize = 12.sp
        )
    }

    if (showLog && wsLog.isNotEmpty()) {
        Surface(
            color = Color(0xFF0A0A12),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.padding(10.dp),
                reverseLayout = true
            ) {
                items(wsLog.reversed()) { entry ->
                    val color = when (entry.level) {
                        "ERROR" -> Color(0xFFEF4444)
                        "WARN"  -> Color(0xFFFBBF24)
                        "MSG"   -> Color(0xFF60A5FA)
                        else    -> Color(0xFF6B7280)
                    }
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("[${entry.time}]", color = Color(0xFF374151), fontSize = 10.sp,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             modifier = Modifier.width(72.dp))
                        Text("${entry.level.take(4).padEnd(4)} ", color = color, fontSize = 10.sp,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text(entry.message, color = Color(0xFF9CA3AF), fontSize = 10.sp,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun AgentTextField(label: String, value: String, onValue: (String) -> Unit,
                   isPassword: Boolean = false) {
    Column {
        Text(label, fontSize = 12.sp, color = Color(0xFF8899AA),
            modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value, onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Color(0xFF6366F1),
                unfocusedBorderColor = Color(0xFF2D2D4A),
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
                cursorColor          = Color(0xFF6366F1)
            ),
            placeholder = { Text(label, color = Color(0xFF4A5568)) },
            visualTransformation = if (isPassword) PasswordVisualTransformation()
                                   else androidx.compose.ui.text.input.VisualTransformation.None
        )
    }
}

@Composable
fun MessagingAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary    = Color(0xFF6366F1),
            background = Color(0xFF0D0D1A),
            surface    = Color(0xFF1A1A2E)
        ),
        content = content
    )
}
