package com.messagingagent.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.service.MessagingAgentService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: PreferencesRepository
) : ViewModel() {

    val settings = prefs.settingsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Triple(null, null, null))

    fun save(url: String, token: String, name: String) = viewModelScope.launch {
        prefs.setBackendUrl(url.trimEnd('/'))
        prefs.setDeviceToken(token.trim())
        prefs.setDeviceName(name.trim())
    }
}

@AndroidEntryPoint
class SetupActivity : ComponentActivity() {

    private val vm: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MessagingAgentTheme {
                SetupScreen(vm = vm, onSave = {
                    // Start foreground service
                    startService(Intent(this, MessagingAgentService::class.java))
                })
            }
        }
    }
}

@Composable
fun SetupScreen(vm: SetupViewModel, onSave: () -> Unit) {
    val saved by vm.settings.collectAsState()
    var url   by remember { mutableStateOf(saved.first ?: "") }
    var token by remember { mutableStateOf(saved.second ?: "") }
    var name  by remember { mutableStateOf(saved.third ?: "") }

    LaunchedEffect(saved) {
        if (url.isEmpty()) url = saved.first ?: ""
        if (token.isEmpty()) token = saved.second ?: ""
        if (name.isEmpty()) name = saved.third ?: ""
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D0D1A), Color(0xFF1A1A2E))))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp).align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text("Messaging Agent", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = Color.White)
            Text("Configure connection to backend", fontSize = 14.sp, color = Color(0xFF8899AA))

            Spacer(Modifier.height(8.dp))

            // Inputs
            listOf(
                Triple("Device Name", name, { v: String -> name = v }),
                Triple("Backend URL (http://...)", url, { v: String -> url = v }),
            ).forEach { (label, value, onValue) ->
                Column {
                    Text(label, fontSize = 12.sp, color = Color(0xFF8899AA),
                        modifier = Modifier.padding(bottom = 6.dp))
                    OutlinedTextField(
                        value = value, onValueChange = onValue,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6366F1),
                            unfocusedBorderColor = Color(0xFF2D2D4A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF6366F1)
                        ),
                        placeholder = { Text(label, color = Color(0xFF4A5568)) }
                    )
                }
            }

            // Token (password field)
            Column {
                Text("Registration Token", fontSize = 12.sp, color = Color(0xFF8899AA),
                    modifier = Modifier.padding(bottom = 6.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0xFF2D2D4A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF6366F1)
                    ),
                    placeholder = { Text("Paste token from admin panel", color = Color(0xFF4A5568)) }
                )
            }

            // Save button
            Button(
                onClick = { vm.save(url, token, name); onSave() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                enabled = url.isNotBlank() && token.isNotBlank() && name.isNotBlank()
            ) {
                Text("Save & Connect", fontWeight = FontWeight.SemiBold)
            }

            // Status
            if (saved.first != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0D2D1A), shape = RoundedCornerShape(10.dp)
                ) {
                    Text("✓ Connected to: ${saved.first}",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF4ADE80), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun MessagingAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6366F1),
            background = Color(0xFF0D0D1A),
            surface = Color(0xFF1A1A2E)
        ),
        content = content
    )
}
