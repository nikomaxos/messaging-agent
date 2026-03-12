package com.messagingagent.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, RegistrationState(null, null, null, null, null, null))

    var groups    by mutableStateOf<List<GroupSummary>>(emptyList())
    var loading   by mutableStateOf(false)
    var error     by mutableStateOf<String?>(null)
    var step      by mutableStateOf(Step.URL)

    enum class Step { URL, GROUP_PICK, DONE }

    fun fetchGroups(url: String) {
        viewModelScope.launch {
            loading = true
            error = null
            prefs.setBackendUrl(url.trimEnd('/'))
            registrationRepo.fetchGroups(url)
                .onSuccess {
                    groups = it
                    step = Step.GROUP_PICK
                }
                .onFailure { error = it.message }
            loading = false
        }
    }

    fun register(url: String, name: String, selectedGroup: GroupSummary) {
        viewModelScope.launch {
            loading = true
            error = null
            registrationRepo.registerDevice(url, name, selectedGroup.id)
                .onSuccess { step = Step.DONE }
                .onFailure { error = it.message }
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

    // If already registered, go straight to DONE step
    LaunchedEffect(regState.isRegistered) {
        if (regState.isRegistered && vm.step == SetupViewModel.Step.URL) {
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
            // ── Header ─────────────────────────────────────────────────────
            Text("Messaging Agent", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Device Registration", fontSize = 14.sp, color = Color(0xFF8899AA))

            Spacer(Modifier.height(4.dp))

            // ── Error banner ────────────────────────────────────────────────
            vm.error?.let {
                Surface(color = Color(0xFF4A1010), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("⚠ $it", color = Color(0xFFFF6B6B), fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }

            // ── Step content ────────────────────────────────────────────────
            when (vm.step) {
                SetupViewModel.Step.URL     -> UrlStep(vm, regState)
                SetupViewModel.Step.GROUP_PICK -> GroupPickStep(vm, regState)
                SetupViewModel.Step.DONE   -> DoneStep(regState, onStart)
            }
        }
    }
}

@Composable
fun UrlStep(vm: SetupViewModel, state: RegistrationState) {
    var url  by remember { mutableStateOf(state.backendUrl ?: "") }
    var name by remember { mutableStateOf(state.deviceName ?: "") }

    AgentTextField("Device Name", name, { name = it })
    AgentTextField("Backend URL (http://host:9090)", url, { url = it })

    Button(
        onClick = { vm.fetchGroups(url) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
        enabled = url.isNotBlank() && name.isNotBlank() && !vm.loading
    ) {
        if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
        else Text("Fetch Groups →", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GroupPickStep(vm: SetupViewModel, state: RegistrationState) {
    var url  by remember { mutableStateOf(state.backendUrl ?: "") }
    var name by remember { mutableStateOf(state.deviceName ?: "") }
    var selected by remember { mutableStateOf<GroupSummary?>(null) }

    Text("Select your Virtual SMSC Group:", fontSize = 13.sp, color = Color(0xFF8899AA))

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.groups) { grp ->
            val isSelected = selected?.id == grp.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = grp }
                    .border(
                        1.dp,
                        if (isSelected) Color(0xFF6366F1) else Color(0xFF2D2D4A),
                        RoundedCornerShape(12.dp)
                    ),
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
            onClick = { selected?.let { vm.register(url, name, it) } },
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            enabled = selected != null && !vm.loading
        ) {
            if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
            else Text("Register Device", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DoneStep(state: RegistrationState, onStart: () -> Unit) {
    Surface(color = Color(0xFF0D2D1A), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("✓ Device Registered", color = Color(0xFF4ADE80),
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Group: ${state.groupName ?: "—"}", color = Color(0xFF8899AA), fontSize = 13.sp)
            Text("Backend: ${state.backendUrl ?: "—"}", color = Color(0xFF8899AA), fontSize = 12.sp)
            Text("Device ID: ${state.deviceId ?: "—"}", color = Color(0xFF8899AA), fontSize = 12.sp)
        }
    }

    Button(
        onClick = { onStart() },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
    ) { Text("Start Service", fontWeight = FontWeight.SemiBold) }

    TextButton(onClick = { /* re-register */ }) {
        Text("Re-register with different group", color = Color(0xFF6366F1), fontSize = 12.sp)
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
