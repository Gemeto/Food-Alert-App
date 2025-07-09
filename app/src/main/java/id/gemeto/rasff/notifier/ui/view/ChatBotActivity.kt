package id.gemeto.rasff.notifier.ui.view

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import id.gemeto.rasff.notifier.ui.theme.MyApplicationTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.gemeto.rasff.notifier.ui.ChatBotViewModel
import id.gemeto.rasff.notifier.ui.ChatUiState

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isComplete: Boolean = true,
    val imageUri: Uri? = null
)

typealias ResultListener = (partialText: String, done: Boolean) -> Unit

class ChatBotActivity : ComponentActivity() {
    private val viewModel: ChatBotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title: String? = intent.getStringExtra("title")
        val justChat: Boolean = intent.getBooleanExtra("justChat", false)
        val imageUri: String? = intent.getStringExtra("imageUri")

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    ChatBotScreen(
                        uiState = uiState,
                        title = title,
                        onSendMessage = { viewModel.sendMessage(justChat, null) },
                        onUserMessageChange = { viewModel.onUserMessageChange(it) },
                        onImageSelected = { viewModel.onImageSelected(it) },
                        onStopTts = { viewModel.stopTts() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBotScreen(
    uiState: ChatUiState,
    title: String?,
    onSendMessage: () -> Unit,
    onUserMessageChange: (String) -> Unit,
    onImageSelected: (Uri?) -> Unit,
    onStopTts: () -> Unit
) {
    val listState = rememberLazyListState()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        onImageSelected(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title ?: "Chat con Gemma 3N",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
                if (!uiState.isModelReady && uiState.llmError == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cargando modelo AI...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                uiState.llmError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages) { message ->
                ChatBubble(message = message, generationMessage = uiState.generationMessage)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (uiState.selectedImageUri != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = uiState.selectedImageUri,
                        contentDescription = "Imagen seleccionada",
                        modifier = Modifier.size(60.dp).padding(end = 8.dp),
                        contentScale = ContentScale.Crop
                    )
                    Text("Imagen lista para enviar", modifier = Modifier.weight(1f))
                    IconButton(onClick = { onImageSelected(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Eliminar imagen")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = uiState.currentMessage,
                onValueChange = onUserMessageChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ej: Avisos sobre dulces...") },
                enabled = !uiState.isGenerating,
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Seleccionar imagen")
            }
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = onStopTts,
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(Icons.Default.Close, contentDescription = "Detener audio")
            }
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = onSendMessage,
                modifier = Modifier.size(56.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                if (uiState.isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, generationMessage: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (message.imageUri != null) {
                    AsyncImage(
                        model = message.imageUri,
                        contentDescription = "Imagen del mensaje",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(bottom = if (message.text.isNotBlank()) 8.dp else 0.dp),
                        contentScale = ContentScale.Crop
                    )
                }

                if (message.text.isNotBlank()) {
                    var textToDisplay = message.text
                    val showFullPrompt = false
                    if(message.isUser && !showFullPrompt) {
                        val originalText = message.text
                        val preguntaMarker = "\n\nPregunta:\n\n"
                        val respuestaMarker = "\n\nRespuesta:"
                        val indexOfPregunta = originalText.indexOf(preguntaMarker)
                        val indexOfRespuesta = originalText.indexOf(respuestaMarker)
                        if (indexOfPregunta != -1 && indexOfRespuesta != -1) {
                            val preguntaYResto = originalText.substring(indexOfPregunta)
                            textToDisplay = preguntaYResto.replaceFirst(preguntaMarker, "")
                                .replaceFirst(respuestaMarker, "")
                        }else if (indexOfPregunta != -1) {
                            val preguntaYResto = originalText.substring(indexOfPregunta)
                            textToDisplay = preguntaYResto.replaceFirst(preguntaMarker, "")
                        }
                    }
                    Text(
                        text = textToDisplay,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!message.isComplete && !message.isUser) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = generationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OCRPreview() {
    MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {

        }
    }
}