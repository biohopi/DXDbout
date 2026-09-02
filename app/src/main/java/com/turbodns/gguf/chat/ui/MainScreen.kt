package com.turbodns.gguf.chat.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodns.gguf.chat.data.AppSettings
import com.turbodns.gguf.chat.data.ExecutionMode
import com.turbodns.gguf.chat.engine.ModelLoadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.copyUriToLocalAndLoad(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemma 4 Local GGUF Chat", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Pick Model File")
                    }
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Chat")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ModelStatusBar(uiState = uiState, context = context)

            val listState = rememberLazyListState()
            LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text) {
                if (uiState.messages.isNotEmpty()) {
                    listState.animateScrollToItem(uiState.messages.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages, key = { it.id }) { msg ->
                    ChatMessageBubble(msg)
                }
            }

            MessageInputArea(
                isGenerating = uiState.isGenerating,
                onSend = { viewModel.sendMessage(it) },
                onStop = { viewModel.stopGeneration() }
            )
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings = uiState.settings,
            onDismiss = { showSettingsDialog = false },
            onSave = { updated ->
                viewModel.updateSettings(updated)
                showSettingsDialog = false
            }
        )
    }
}

@Composable
fun ModelStatusBar(uiState: UiState, context: Context) {
    val loadState = uiState.modelLoadState
    val stats = uiState.stats

    val memoryInfo = remember(uiState.messages.size, uiState.isGenerating) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val availGb = mem.availMem / (1024 * 1024 * 1024f)
        val totalGb = mem.totalMem / (1024 * 1024 * 1024f)
        String.format("%.1f/%.1f GB Free", availGb, totalGb)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                when (loadState) {
                    is ModelLoadState.Loaded -> {
                        Text(
                            text = "Model: ${loadState.modelName} (${loadState.quantization})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Backend: ${if (loadState.isVulkan) "Vulkan GPU" else "CPU NEON"} | RAM: $memoryInfo",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ModelLoadState.Loading -> {
                        Text("Status: Loading model...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    is ModelLoadState.Error -> {
                        Text("Error: ${loadState.error}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    ModelLoadState.Idle -> {
                        Text("No Model Loaded (Tap folder to open .gguf)", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            stats?.let {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = String.format("%.1f tok/s", it.tokensPerSecond),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                message.webStatus?.let { status ->
                    Text(
                        text = "🌐 $status",
                        fontSize = 10.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Text(
                    text = message.text.ifEmpty { if (message.isStreaming) "..." else "" },
                    fontSize = 14.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { clipboardManager.setText(AnnotatedString(message.text)) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy message",
                            tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageInputArea(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ask Gemma 4...") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isGenerating) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.StopCircle, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    var baseUrl by remember { mutableStateOf(currentSettings.baseUrl) }
    var apiKey by remember { mutableStateOf(currentSettings.apiKey) }
    var modelId by remember { mutableStateOf(currentSettings.modelId) }
    var mode by remember { mutableStateOf(currentSettings.executionMode) }
    var useVulkan by remember { mutableStateOf(currentSettings.useVulkan) }
    var temp by remember(currentSettings.temperature) { mutableStateOf(currentSettings.temperature) }
    var topP by remember(currentSettings.topP) { mutableStateOf(currentSettings.topP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Execution Mode", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExecutionMode.values().forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(m.name.replace("_", " "), fontSize = 10.sp) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useVulkan, onCheckedChange = { useVulkan = it })
                    Text("Enable Vulkan GPU Acceleration")
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("OpenAI Base URL") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        currentSettings.copy(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            modelId = modelId,
                            executionMode = mode,
                            useVulkan = useVulkan,
                            temperature = temp,
                            topP = topP
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
