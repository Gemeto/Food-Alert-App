package id.gemeto.rasff.notifier.ui.view

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import id.gemeto.rasff.notifier.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.io.InputStream

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isComplete: Boolean = true,
    val imageUri: Uri? = null
)

class OCRActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title: String? = intent.getStringExtra("title")
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OCRScreen(
                        title = title
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRScreen(title: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Estados para el chat
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var currentMessage by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var currentStreamingMessage by remember { mutableStateOf("") }

    // Estados para la imagen - REMOVIDO hasStoragePermission
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    // Estado para LazyColumn
    val listState = rememberLazyListState()

    // Launcher para seleccionar imagen de galería - SIMPLIFICADO
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
        }
    }

    // Inicializar LLM con capacidades multimodales
    val llmInference = remember {
        try {
            val taskOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath("/data/local/tmp/llm/gemma-3n-E2B-it-int4.task")
                .setMaxTopK(64)
                .setMaxNumImages(1)
                .build()
            LlmInference.createFromOptions(context, taskOptions)
        } catch (e: Exception) {
            null
        }
    }

    // Función para convertir URI a MPImage
    fun uriToMPImage(uri: Uri): MPImage? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            BitmapImageBuilder(bitmap).build()
        } catch (e: Exception) {
            null
        }
    }

    fun sendMessage() {
        if ((currentMessage.isBlank() && selectedImageUri == null) || isGenerating || llmInference == null) return

        // Agregar mensaje del usuario con imagen si existe
        messages = messages + ChatMessage(
            text = currentMessage,
            isUser = true,
            imageUri = selectedImageUri
        )

        val userMessage = currentMessage
        val userImageUri = selectedImageUri
        currentMessage = ""
        selectedImageUri = null
        isGenerating = true

        // Agregar mensaje vacío para el streaming simulado
        currentStreamingMessage = ""
        messages = messages + ChatMessage("", false, false)

        scope.launch {
            try {
                // Crear sesión con modalidad de visión habilitada
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(10)
                    .setTemperature(0.4f)
                    .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                    .build()

                val session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions)

                // Preparar el prompt
                val finalPrompt = if (userMessage.isNotBlank()) {
                    userMessage
                } else if (userImageUri != null) {
                    "Describe detalladamente lo que ves en esta imagen."
                } else {
                    "Hola"
                }

                // Agregar el texto del query
                session.addQueryChunk(finalPrompt)

                // Si hay imagen, agregarla a la sesión
                if (userImageUri != null) {
                    val mpImage = uriToMPImage(userImageUri)
                    if (mpImage != null) {
                        session.addImage(mpImage)
                    }
                }

                // Generar respuesta
                val fullResponse = session.generateResponse()

                // Cerrar la sesión
                session.close()

                // Simular streaming dividiendo la respuesta en palabras
                val words = fullResponse.split(" ")
                currentStreamingMessage = ""

                for (i in words.indices) {
                    val partialText = words.take(i + 1).joinToString(" ")
                    currentStreamingMessage = partialText

                    // Actualizar mensaje en streaming
                    messages = messages.dropLast(1) +
                            ChatMessage(currentStreamingMessage, false, false)

                    // Auto-scroll al final
                    listState.animateScrollToItem(messages.size - 1)

                    // Pausa para simular escritura
                    kotlinx.coroutines.delay(100)
                }

                // Completar el mensaje
                messages = messages.dropLast(1) +
                        ChatMessage(currentStreamingMessage, false, true)
                currentStreamingMessage = ""
                isGenerating = false

            } catch (e: Exception) {
                // Manejar error
                messages = messages.dropLast(1) +
                        ChatMessage("Error: No se pudo generar respuesta - ${e.message}", false, true)
                isGenerating = false
            }
        }
    }

    // FUNCIÓN SIMPLIFICADA - sin verificación de permisos
    fun selectImage() {
        imagePickerLauncher.launch("image/*")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header con título e imagen
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title ?: "Chat con Gemma 3N",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message = message)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Preview de imagen seleccionada
        if (selectedImageUri != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = "Imagen seleccionada",
                        modifier = Modifier
                            .size(60.dp)
                            .padding(end = 8.dp),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        text = "Imagen lista para enviar",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = { selectedImageUri = null }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Eliminar imagen")
                    }
                }
            }
        }

        // Input field con botones
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = currentMessage,
                onValueChange = { currentMessage = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribe tu mensaje...") },
                enabled = !isGenerating,
                maxLines = 3
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Botón de galería - ICONO FIJO
            FloatingActionButton(
                onClick = { selectImage() },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp, // ICONO SIEMPRE FIJO
                    contentDescription = "Seleccionar imagen",
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Botón de envío
            FloatingActionButton(
                onClick = { sendMessage() },
                modifier = Modifier.size(56.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Enviar",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
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
                // Mostrar imagen si existe
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

                // Mostrar texto si no está vacío
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
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
                            text = "Escribiendo",
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
            OCRScreen(
                title = "Chat con Gemma 3N"
            )
        }
    }
}