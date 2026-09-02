package com.turbodns.gguf.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.turbodns.gguf.chat.ui.ChatViewModel
import com.turbodns.gguf.chat.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (viewModel.uiState.value.settings.modelPath.isNotBlank()) {
            viewModel.loadModelFromPath(viewModel.uiState.value.settings.modelPath)
        } else {
            viewModel.tryLoadAssetModel()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
