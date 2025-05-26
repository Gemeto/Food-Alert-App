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
import androidx.core.net.toUri
import androidx.room.Room
import id.gemeto.rasff.notifier.data.AppDatabase
import id.gemeto.rasff.notifier.data.ArticleDAO
import id.gemeto.rasff.notifier.data.TitleVectorizerService
import id.gemeto.rasff.notifier.ui.Article
import id.gemeto.rasff.notifier.utils.VectorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val imageUri: String? = intent.getStringExtra("imageUri")
        val context: String? = intent.getStringExtra("context")
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OCRScreen(
                        title = title,
                        imageUri = imageUri,
                        sysContext = context
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRScreen(title: String?, imageUri: String?, sysContext: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Estados para el chat
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var currentMessage by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var currentStreamingMessage by remember { mutableStateOf("") }

    // Estados para la imagen - REMOVIDO hasStoragePermission
    var selectedImageUri by remember { mutableStateOf<Uri?>(imageUri?.toUri()) }

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

    // Estado para LlmInference, inicializado a null
    var llmInference by remember { mutableStateOf<LlmInference?>(null) }
    var llmError by remember { mutableStateOf<String?>(null) } // Para mostrar errores de carga del modelo

    // LaunchedEffect para inicializar LLM en un hilo de fondo
    LaunchedEffect(Unit) { // Se ejecuta solo una vez cuando el composable entra en la composición
        withContext(Dispatchers.IO) { // Mover la inicialización a un hilo de fondo
            try {
                val taskOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath("/data/local/tmp/llm/gemma-3n-E2B-it-int4.task") // Asegúrate que esta ruta sea accesible
                    .setMaxTopK(64)
                    .setMaxNumImages(1)
                    .build()
                llmInference = LlmInference.createFromOptions(context, taskOptions)
            } catch (e: Exception) {
                llmError = "Error al cargar el modelo AI: ${e.localizedMessage}"
            }
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

    val _db = Room.databaseBuilder(
        context = context,
        AppDatabase::class.java, "database-alert-notifications"
    ).build()
    val _articleDao: ArticleDAO = _db.articleDao()
    val _titleVectorizerService = TitleVectorizerService(context = context)

    //Mappers
    fun toDbArticle(uiArticle: Article, titleVector: List<Float>? = null): id.gemeto.rasff.notifier.data.Article {
        return id.gemeto.rasff.notifier.data.Article(
            id = uiArticle.link, // Assuming link is a unique identifier
            title = uiArticle.title,
            content = uiArticle.description,
            titleVector = titleVector ?: emptyList()
        )
    }

    fun toUiArticle(dbArticle: id.gemeto.rasff.notifier.data.Article): Article {
        return Article(
            title = dbArticle.title,
            description = dbArticle.content,
            link = dbArticle.id,
            imageUrl = "", // Placeholder
            unixTime = 0L // Placeholder
        )
    }

    // Threshold for similarity; can be adjusted
    val SIMILARITY_THRESHOLD = 0.7f
    // Wrapper for articles with their similarity scores
    data class ArticleWithSimilarity(val dbArticle: id.gemeto.rasff.notifier.data.Article, val similarity: Float)

    suspend fun sendMessage(sysContext: String?) {
        if ((currentMessage.isBlank() && selectedImageUri == null) || isGenerating || llmInference == null) return

        val queryVector = _titleVectorizerService.getVector(currentMessage) // Suspend call
        val allDbArticles = _articleDao.getAll() // Suspend call

        val articlesWithSimilarity = allDbArticles.map { dbArticle ->
            val similarity = VectorUtils.cosineSimilarity(queryVector, dbArticle.titleVector)
            ArticleWithSimilarity(dbArticle, similarity)
        }

        // Primary filter: similarity search
        var filteredArticles = articlesWithSimilarity
            .filter { it.similarity > SIMILARITY_THRESHOLD && it.similarity.isFinite() }
            .sortedByDescending { it.similarity }
            .take(4)
            .map { it.dbArticle }

        val scontext = filteredArticles.joinToString("\n") { it.title }
        // Agregar mensaje del usuario con imagen si existe
        val sysPrompt = "Eres un asistente capaz de leer el contexto de alertas alimentarias actuales y ver imagenes. Unicamente contesta a la pregunta del usuario. Contesta siempre en español."
        val msg = "$sysPrompt\n\nInformación de referencia:\n\n$scontext\n\nPregunta:\n\n$currentMessage\n\nRespuesta:"
        //val msg = "$sysPrompt\n\nPregunta:\n\n$currentMessage"
        messages = messages + ChatMessage(
            text = msg,
            isUser = true,
            imageUri = selectedImageUri
        )

        val userImageUri = selectedImageUri
        currentMessage = ""
        selectedImageUri = null
        isGenerating = true

        // Agregar mensaje vacío para el streaming simulado
        currentStreamingMessage = ""
        messages = messages + ChatMessage("", false, false)

        scope.launch { // Asegúrate que 'scope' no esté rígidamente atado al Main thread por defecto para todo.
            // Si usas viewModelScope, esto ya maneja la cancelación automáticamente.
            try {
                // Mueve la lógica de inferencia pesada a un hilo de fondo
                val fullResponse = withContext(Dispatchers.IO) { // O Dispatchers.Default si es más apropiado
                    // Crear sesión con modalidad de visión habilitada
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(10)
                        .setTemperature(0.9f)
                        .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                        .build()

                    val session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions)

                    // Preparar el prompt
                    val finalPrompt = if (msg.isNotBlank()) {
                        msg
                    } else {
                        "Hola"
                    }

                    // Agregar el texto del query
                    session.addQueryChunk(finalPrompt)

                    // Si hay imagen, agregarla a la sesión
                    if (userImageUri != null) {
                        val mpImage = uriToMPImage(userImageUri) // Esta función también podría ser pesada
                        if (mpImage != null) {
                            session.addImage(mpImage)
                        }
                    }

                    // Generar respuesta (esta es la operación principal bloqueante)
                    val response = session.generateResponse()

                    // Cerrar la sesión
                    session.close()
                    response // Retornar la respuesta para usarla fuera de withContext
                }

                // La simulación de streaming y actualización de UI debe ocurrir en el hilo principal
                // Si 'scope' ya es MainScope o viewModelScope, no necesitas cambiar de contexto aquí.
                // Si 'scope' es un scope genérico con Dispatchers.Default o IO, necesitas volver al Main thread:
                // withContext(Dispatchers.Main) { ... }

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
                    kotlinx.coroutines.delay(100) // delay es una función suspendible, no bloquea el hilo.
                }

                // Completar el mensaje
                messages = messages.dropLast(1) +
                        ChatMessage(currentStreamingMessage, false, true)
                currentStreamingMessage = ""
                isGenerating = false

            } catch (e: Exception) {
                // Manejar error (asegúrate que la actualización de UI también sea en el Main thread si es necesario)
                // withContext(Dispatchers.Main) { ... }
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title ?: "Chat con Gemma 3N",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                if (llmInference == null && llmError == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cargando modelo AI...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                llmError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
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

            FloatingActionButton(
                onClick = { selectImage() },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp, // ICONO SIEMPRE FIJO
                    contentDescription = "Seleccionar imagen",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            // Botón de envío
            FloatingActionButton(
                onClick = {
                    scope.launch {
                    sendMessage(sysContext)
                }},
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
                    var textToDisplay = message.text
                    if(message.isUser && false) {
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
                title = "Chat con Gemma 3N",
                imageUri = "",
                sysContext = "Sin contexto"
            )
        }
    }
}