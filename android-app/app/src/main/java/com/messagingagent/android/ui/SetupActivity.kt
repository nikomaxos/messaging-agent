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
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val registrationRepo: RegistrationRepository
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
            registrationRepo.registerDevice(pendingUrl, pendingName, selectedGroup.id)
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
                    startService(Intent(this, MessagingAgentService::class.java))
                })
            }
        }
    }
}

@Composable
fun SetupScreen(vm: SetupViewModel, onStart: () -> Unit) {
    val regState by vm.registrationState.collectAsState()

    LaunchedEffect(regState.isRegistered) {
        if (regState.isRegistered && vm.step == SetupViewModel.Step.URL) {
            vm.pendingUrl  = regState.backendUrl ?: ""
            vm.pendingName = regState.deviceName ?: ""
            vm.step = SetupViewModel.Step.DONE
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
    val scope = rememberCoroutineScope()

    // Live status: poll GET /api/devices/{id} every 10 seconds
    var liveStatus by remember { mutableStateOf("Checking…") }
    var isOnline   by remember { mutableStateOf(false) }
    var lastSeen   by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.deviceToken) {
        val token = state.deviceToken ?: return@LaunchedEffect
        val url   = state.backendUrl ?: vm.pendingUrl
        if (url.isBlank()) return@LaunchedEffect

        while (true) {
            try {
                val reqUrl = "${url.trimEnd('/')}/api/devices/register/status/$token"
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url(reqUrl).build()
                    ).execute()
                }
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    isOnline   = body.contains("\"ONLINE\"")
                    lastSeen   = if (body.contains("\"lastHeartbeat\":null")) null else "Has heartbeat"
                    liveStatus = if (isOnline) "Online" else "Offline"
                } else {
                    liveStatus = "Cannot reach backend (${resp.code})"
                    isOnline   = false
                }
            } catch (e: Exception) {
                liveStatus = "Cannot reach backend"
                isOnline   = false
            }
            kotlinx.coroutines.delay(10_000)
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
                Text(
                    if (isOnline) "● Connected to backend" else "○ $liveStatus",
                    color    = if (isOnline) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
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
                    Text("Device ID", color = Color(0xFF6B7280), fontSize = 11.sp)
                    Text("#${state.deviceId ?: "—"}", color = Color(0xFF6B7280), fontSize = 12.sp,
                         fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
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
