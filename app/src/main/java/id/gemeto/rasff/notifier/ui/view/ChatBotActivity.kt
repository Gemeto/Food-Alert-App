package id.gemeto.rasff.notifier.ui.view

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
import id.gemeto.rasff.notifier.data.local.AppDatabase
import id.gemeto.rasff.notifier.data.local.dao.ArticleDAO
import id.gemeto.rasff.notifier.data.local.entity.Article
import id.gemeto.rasff.notifier.domain.service.TitleVectorizerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isComplete: Boolean = true,
    val imageUri: Uri? = null
)

typealias ResultListener = (partialText: String, done: Boolean) -> Unit

class ChatBotActivity : ComponentActivity() {

    companion object {
        internal var sharedLlmInference: LlmInference? = null
        internal var isModelLoading = false
    }

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
                    ChatBotScreen(
                        title = title,
                        justChat = justChat,
                        imageUri = imageUri
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // El modelo permanece en memoria
    }

    override fun onDestroy() {
        super.onDestroy()
        // Solo liberar el modelo si la actividad se está cerrando completamente
        if (!isChangingConfigurations) {
            sharedLlmInference?.close()
            sharedLlmInference = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBotScreen(title: String?, imageUri: String?, justChat: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Estados para el chat
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var currentMessage by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationMessage by remember { mutableStateOf("Escribiendo") }
    var currentStreamingMessage by remember { mutableStateOf("") }
    var isUserTouching by remember { mutableStateOf(false) }
    var userScrolledManually by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

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

    // Estado para LlmInference usando el companion object
    var llmInference by remember { mutableStateOf<LlmInference?>(ChatBotActivity.sharedLlmInference) }
    var llmError by remember { mutableStateOf<String?>(null) } // Para mostrar errores de carga del modelo

    val _titleVectorizerService = TitleVectorizerService.getInstance(
        embeddingModelPath = "/data/local/tmp/gecko.tflite",
        sentencePieceModelPath = "/data/local/tmp/sentencepiece.model",
        useGpu = true
    )
    val SIMILARITY_THRESHOLD = 0.8f
    var similaritySearchText: String = ""
    var similartySearchVector: List<Float> = emptyList()

    data class ArticleWithSimilarity(val dbArticle: Article, val similarity: Float)

    fun searchKeywords(query: String): List<String> = query.trim().split(" ", "\n").map { it.lowercase().removeSuffix("s") }

    fun keywordSearchFilter(query: String, article: ArticleWithSimilarity): Boolean =
        searchKeywords(query).any{ query -> article.dbArticle.title.lowercase().contains(query) }

    suspend fun searchSimilarity(query: String, article: Article): Float = withContext(Dispatchers.IO) {
        if(similaritySearchText != query) {
            similartySearchVector = _titleVectorizerService.getVector(query)
            similaritySearchText = query
        }
        _titleVectorizerService.cosineSimilarity(
            similartySearchVector,
            article.titleVector,
        ).toFloat()
    }

    suspend fun similaritySearchFilter(query: String, article: Article): Boolean =
        searchSimilarity(query, article) > SIMILARITY_THRESHOLD

    // LaunchedEffect para inicializar LLM en un hilo de fondo
    LaunchedEffect(Unit) { // Se ejecuta solo una vez cuando el composable entra en la composición
        // Solo cargar si no existe ya un modelo y no se está cargando
        if (ChatBotActivity.sharedLlmInference == null && !ChatBotActivity.isModelLoading) {
            ChatBotActivity.isModelLoading = true
            withContext(Dispatchers.IO) { // Mover la inicialización a un hilo de fondo
                try {
                    val taskOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath("/data/local/tmp/llm/gemma-3n-E2B-it-int4.task") // Asegúrate que esta ruta sea accesible
                        .setMaxTopK(64)
                        .setMaxNumImages(1)
                        .build()
                    val newModel = LlmInference.createFromOptions(context, taskOptions)
                    ChatBotActivity.sharedLlmInference = newModel
                    llmInference = newModel
                } catch (e: Exception) {
                    llmError = "Error al cargar el modelo AI: ${e.localizedMessage}"
                } finally {
                    ChatBotActivity.isModelLoading = false
                }
            }
        } else if (ChatBotActivity.sharedLlmInference != null) {
            // Si ya existe el modelo, usarlo directamente
            llmInference = ChatBotActivity.sharedLlmInference
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

    suspend fun sendMessage(justChat: Boolean = false) {
        if ((currentMessage.isBlank() && selectedImageUri == null) || isGenerating || llmInference == null) return

        val sysPrompt = "Eres un asistente capaz de leer el contexto de alertas alimentarias actuales. Unicamente contesta a la pregunta del usuario. Contesta siempre en español."
        val sysImagePrompt = "Unicamente devuelve una cadena de texto que contenga las palabras clave de la lista en la imagen, separadas por un espacio. " +
                "No uses numeros ni simbolos, las palabras clave son unicamente los alimentos listados, siempre en singular. " +
                "No debes emitir ninguna otra información."

        val msg = if(!justChat && selectedImageUri == null) {
            generationMessage = "Buscando contenido relevante"
            val queryVector = _titleVectorizerService.getVector(currentMessage)
            val allDbArticles = _articleDao.getAll()
            val articlesWithSimilarity = allDbArticles.map { dbArticle ->
                val similarity = _titleVectorizerService.cosineSimilarity(
                    queryVector,
                    dbArticle.titleVector,
                ).toFloat()
                ArticleWithSimilarity(dbArticle, similarity)
            }

            var kekywordFilteredArticles = articlesWithSimilarity.filter {
                    article -> keywordSearchFilter(currentMessage, article)
            }
            var filteredArticles = articlesWithSimilarity
                .filter { similaritySearchFilter(currentMessage, it.dbArticle) }
                .sortedByDescending { it.similarity }
                .take(5)
                .map { it.dbArticle }
                .plus(kekywordFilteredArticles.map { it.dbArticle })
                .distinctBy { it.id }
                .take(10)
            val ragContext = filteredArticles.joinToString("\n") { it.title }
            "$sysPrompt\n\nInformación de referencia:\n\n$ragContext\n\nPregunta:\n\n$currentMessage\n\nRespuesta:"
        }else {
            "$sysPrompt\n\nPregunta:\n\n$currentMessage"
        }
        messages = messages + ChatMessage(
            text = msg,
            isUser = true,
            imageUri = selectedImageUri
        )

        val userImageUri = selectedImageUri
        currentMessage = ""
        selectedImageUri = null
        isGenerating = true

        userScrolledManually = false
        isAutoScrolling = true

        // Agregar mensaje vacío para el streaming
        currentStreamingMessage = ""
        messages = messages + ChatMessage("", false, false)
        isAutoScrolling = true

        scope.launch {
            try {
                // Mueve la lógica de inferencia pesada a un hilo de fondo
                withContext(Dispatchers.IO) {
                    // Crear sesión con modalidad de visión habilitada
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(10)
                        .setTemperature(0.9f)
                        .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                        .build()

                    var keywords = ""
                    if (!justChat && userImageUri != null) {
                        generationMessage = "Analizando la imagen"
                        val session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions)
                        session.addQueryChunk(sysImagePrompt)
                        val mpImage = uriToMPImage(userImageUri)
                        if (mpImage != null) {
                            session.addImage(mpImage)
                            keywords = session.generateResponse()
                            session.close()
                        }
                    }

                    val session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions)

                    // Preparar el prompt
                    val finalPrompt = if (msg.isNotBlank()) {
                        if(keywords.isNotBlank()) {
                            generationMessage = "Buscando contenido relevante"
                            val queryVector = _titleVectorizerService.getVector(keywords)
                            val allDbArticles = _articleDao.getAll()
                            val articlesWithSimilarity = allDbArticles.map { dbArticle ->
                                val similarity = _titleVectorizerService.cosineSimilarity(
                                    queryVector,
                                    dbArticle.titleVector,
                                ).toFloat()
                                ArticleWithSimilarity(dbArticle, similarity)
                            }

                            var kekywordFilteredArticles = articlesWithSimilarity.filter {
                                    article -> keywordSearchFilter(currentMessage, article)
                            }
                            var filteredArticles = articlesWithSimilarity
                                .filter { similaritySearchFilter(currentMessage, it.dbArticle) }
                                .sortedByDescending { it.similarity }
                                .take(5)
                                .map { it.dbArticle }
                                .plus(kekywordFilteredArticles.map { it.dbArticle })
                                .distinctBy { it.id }
                                .take(10)

                            val ragContext = filteredArticles.joinToString("\n") { it.title }
                            "$sysPrompt\n\nInformación de referencia:\n\n$ragContext\n\nPalabras clave:\n\n$keywords\n\n" +
                                    "Pregunta:\n\n" +
                                    "Escribe las noticias en la información de referencia que contengan una o mas palabras clave, o contengan alimentos relacionados con las palabras clave. " +
                                    "Incluye siempre la o las palabras clave proporcionadas por las cuales se haya seleccionado la noticia en la respuesta, entre parentesis justo despues de la noticia. (Ej: Titulo noticia (carne)), " +
                                    "y si la palabra clave por la que se ha seleccionado la noticia no aparece en la en las palabras clave proporcionadas en este mensaje, indica con una flecha a partir de que palabra clave se ha seleccionado la noticia. (Ej: Titulo noticia (hamburguesa -> carne)). " +
                                    "No debes emitir ninguna otra información.\n\n" +
                                    "Respuesta:"
                        } else {
                            msg
                        }

                    } else {
                        "Hola"
                    }
                    generationMessage = "Escribiendo"
                    session.addQueryChunk(finalPrompt)
                    val resultListener: ResultListener = { partialText, done ->
                        currentStreamingMessage += partialText
                        messages = messages.dropLast(1) + ChatMessage(currentStreamingMessage, false, done)
                    }
                    session.generateResponseAsync(resultListener).await()
                    session.close()
                    currentStreamingMessage = ""
                    isGenerating = false
                    isAutoScrolling = false
                }
            } catch (e: Exception) {
                messages = messages.dropLast(1) +
                        ChatMessage("Error: No se pudo generar respuesta - ${e.message}", false, true)
                isGenerating = false
                isAutoScrolling = false
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
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isUserTouching = true
                            try {
                                awaitRelease()
                            } finally {
                                isUserTouching = false
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isUserTouching = true
                            if (isGenerating) {
                                userScrolledManually = true
                                isAutoScrolling = false
                            }
                        },
                        onDragEnd = {
                            isUserTouching = false
                        },
                        onDrag = { _, _ ->
                            if (isGenerating) {
                                userScrolledManually = true
                                isAutoScrolling = false
                            }
                        }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message = message, generationMessage = generationMessage)
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
                placeholder = { Text("Ej: Avisos sobre dulces...") },
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
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        sendMessage(justChat = justChat)
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
            ChatBotScreen(
                title = "Chat con Gemma 3N",
                imageUri = ""
            )
        }
    }
}