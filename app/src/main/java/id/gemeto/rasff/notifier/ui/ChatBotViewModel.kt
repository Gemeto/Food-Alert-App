package id.gemeto.rasff.notifier.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import id.gemeto.rasff.notifier.data.local.AppDatabase
import id.gemeto.rasff.notifier.data.local.entity.Article
import id.gemeto.rasff.notifier.domain.service.TitleVectorizerService
import id.gemeto.rasff.notifier.domain.service.TranslationService
import id.gemeto.rasff.notifier.ui.view.ChatMessage
import id.gemeto.rasff.notifier.ui.view.ResultListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId

// Estado de la UI que el ViewModel expondrá
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentMessage: String = "",
    val selectedImageUri: Uri? = null,
    val isGenerating: Boolean = false,
    val generationMessage: String = "Escribiendo",
    val llmError: String? = null,
    val isModelReady: Boolean = false
)

class ChatBotViewModel(private val application: Application) : AndroidViewModel(application) {

    // --- Objetos de Larga Duración ---
    private var llmInference: LlmInference? = null
    private var textToSpeech: TextToSpeech? = null
    private val db by lazy {
        Room.databaseBuilder(
            application,
            AppDatabase::class.java, "database-alert-notifications"
        ).build()
    }
    private val articleDao by lazy { db.articleDao() }
    private val titleVectorizerService by lazy {
        TitleVectorizerService.getInstance(
            embeddingModelPath = "/data/local/tmp/gecko.tflite",
            sentencePieceModelPath = "/data/local/tmp/sentencepiece.model",
            useGpu = true
        )
    }
    private val translationService by lazy { TranslationService() }

    // --- Estado de la UI ---
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    // --- Constantes y Prompts ---
    private val SIMILARITY_THRESHOLD = 0.8f
    private val sysPrompt = "Eres un asistente capaz de leer el contexto de alertas alimentarias actuales. Unicamente contesta a la pregunta del usuario. Contesta siempre en español."
    private val sysImagePrompt = "Unicamente devuelve una cadena de texto que contenga las palabras clave de la lista en la imagen, separadas por un espacio. " +
            "No uses numeros ni simbolos, las palabras clave son unicamente los alimentos listados, siempre en singular. " +
            "No debes emitir ninguna otra información."

    init {
        initializeLlmAndTts()
    }

    private fun initializeLlmAndTts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Inicializar TTS
                textToSpeech = TextToSpeech(application) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        Log.d("TextToSpeech", "Initialization Success")
                    } else {
                        Log.d("TextToSpeech", "Initialization Failed")
                    }
                }
                val taskOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath("/data/local/tmp/llm/gemma-3n-E2B-it-int4.task")
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .setMaxTopK(64)
                    .setMaxTokens(4096)
                    .setMaxNumImages(1)
                    .build()
                llmInference = LlmInference.createFromOptions(application, taskOptions)
                _uiState.update { it.copy(isModelReady = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(llmError = "Error al cargar el modelo AI: ${e.localizedMessage}") }
            }
        }
    }

    fun sendMessage(justChat: Boolean, initialImageUri: String?) {
        val llm = llmInference ?: return
        val currentState = _uiState.value
        if ((currentState.currentMessage.isBlank() && currentState.selectedImageUri == null && initialImageUri == null) || currentState.isGenerating) {
            return
        }

        viewModelScope.launch {
            // Preparar el estado de la UI para el envío
            val userMessageText = currentState.currentMessage
            val userImageUri = currentState.selectedImageUri ?: initialImageUri?.toUri()

            _uiState.update {
                it.copy(
                    isGenerating = true,
                    currentMessage = "",
                    selectedImageUri = null
                )
            }

            // Añadir el mensaje del usuario a la lista
            val userMessagePrompt = buildUserMessagePrompt(justChat, userMessageText, userImageUri)
            val userChatMessage = ChatMessage(text = userMessagePrompt, isUser = true, imageUri = userImageUri)
            _uiState.update { it.copy(messages = it.messages + userChatMessage) }

            // Añadir el placeholder para la respuesta del bot
            val botPlaceholderMessage = ChatMessage("", isUser = false, isComplete = false)
            _uiState.update { it.copy(messages = it.messages + botPlaceholderMessage) }

            try {
                withContext(Dispatchers.IO) {
                    val finalPrompt = generateFinalPrompt(llm, userMessagePrompt, userImageUri, justChat)

                    _uiState.update { it.copy(generationMessage = "Escribiendo") }

                    val session = LlmInferenceSession.createFromOptions(llm, getSessionOptions())
                    if(justChat && userImageUri != null) {
                        uriToMPImage(userImageUri)?.let { session.addImage(it) }
                    }
                    session.addQueryChunk(finalPrompt)

                    // El listener actualiza directamente el StateFlow
                    val resultListener: ResultListener = { partialText, done ->
                        _uiState.update { state ->
                            val lastMessage = state.messages.last()
                            val updatedMessage = lastMessage.copy(text = lastMessage.text + partialText, isComplete = done)
                            val updatedList = state.messages.dropLast(1) + updatedMessage
                            state.copy(messages = updatedList)
                        }
                        if (done) {
                            textToSpeech?.speak(
                                _uiState.value.messages.last().text,
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                null
                            )
                        }
                    }

                    session.generateResponseAsync(resultListener).await()
                    session.close()
                }

            } catch (e: Exception) {
                val errorMessage = ChatMessage("Error: No se pudo generar respuesta - ${e.message}", false, true)
                _uiState.update { it.copy(messages = it.messages.dropLast(1) + errorMessage) }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    private suspend fun buildUserMessagePrompt(justChat: Boolean, userMessage: String, imageUri: Uri?): String {
        return withContext(Dispatchers.IO) {
            if (!justChat && imageUri == null) {
                _uiState.update { it.copy(generationMessage = "Buscando contenido relevante") }
                val translatedUserMessage = translationService.translateTextToEnglish(userMessage)
                val queryVector = titleVectorizerService.getVector(translatedUserMessage)
                val allDbArticles = articleDao.getAll()

                val articlesWithSimilarity = allDbArticles.map { dbArticle ->
                    val similarity = titleVectorizerService.cosineSimilarity(queryVector, dbArticle.titleVector)
                    Pair(dbArticle, similarity)
                }

                val keywordFilteredArticles = articlesWithSimilarity
                    .filter { (article, _) -> userMessage.trim().split(" ").any { article.title.lowercase().contains(it.lowercase()) } }
                    .map { it.first }

                val filteredArticles = articlesWithSimilarity
                    .filter { it.second > SIMILARITY_THRESHOLD }
                    .sortedByDescending { it.second }
                    .take(5)
                    .map { it.first }
                    .plus(keywordFilteredArticles)
                    .distinctBy { it.id }
                    .take(10)

                val ragContext = if (filteredArticles.isNotEmpty()) {
                    filteredArticles.joinToString("\n") { translationService.translateTextToSpanish(it.title) }
                } else "No hay informacion relevante"

                "$sysPrompt\n\nInformación de referencia:\n\n$ragContext\n\nPregunta:\n\n$userMessage\n\nRespuesta:"
            } else {
                userMessage
            }
        }
    }

    private suspend fun generateFinalPrompt(llm: LlmInference, msg: String, userImageUri: Uri?, justChat: Boolean): String {
        var keywords = ""
        if (!justChat && userImageUri != null) {
            _uiState.update { it.copy(generationMessage = "Analizando la imagen") }
            val session = LlmInferenceSession.createFromOptions(llm, getSessionOptions())
            session.addQueryChunk(sysImagePrompt)
            uriToMPImage(userImageUri)?.let {
                session.addImage(it)
                keywords = session.generateResponse()
            }
            session.close()
        }

        return withContext(Dispatchers.IO) {
            if (keywords.isNotBlank()) {
                _uiState.update { it.copy(generationMessage = "Buscando contenido relevante") }
                // Lógica RAG con keywords de la imagen
                val translatedKeywords = translationService.translateTextToEnglish(keywords)
                val fourMonthInSecs = 2629800 * 4
                val allDbArticles = articleDao.getAll().filter { it.unixTime >= LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond - fourMonthInSecs }
                var filteredArticles = listOf<Article>()
                translatedKeywords.split(", ").forEach { translatedKeyword ->
                    val queryVector = titleVectorizerService.getVector("$translatedKeyword alert")
                    val articlesWithSimilarity = allDbArticles.map { dbArticle ->
                        val similarity = titleVectorizerService.cosineSimilarity(queryVector, dbArticle.titleVector)
                        Pair(dbArticle, similarity)
                    }
                    filteredArticles = articlesWithSimilarity
                        .filter { it.second > SIMILARITY_THRESHOLD }
                        .sortedByDescending { it.second }
                        .take(3)
                        .map { it.first }
                        .plus(filteredArticles)
                        .distinctBy { it.id }
                }
                val ragContext = filteredArticles.joinToString("\n") { translationService.translateTextToSpanish(it.title) }
                "$sysPrompt\n\nInformación de referencia:\n\n$ragContext\n\nPalabras clave:\n\n$keywords\n\n" +
                        "Pregunta:\n\n" +
                        "Escribe las noticias proporcionadas en la información de referencia que contengan alguna palabra clave u otro nombre de alimento que este directamente relacionado. " +
                        "Incluye siempre la o las palabras clave proporcionadas por las cuales se haya seleccionado la noticia en la respuesta, entre parentesis justo despues de la noticia. (Ej: Titulo noticia (carne)), " +
                        "y si la palabra clave por la que se ha seleccionado la noticia no aparece en la en las palabras clave proporcionadas en este mensaje, explica brevemente porque es relevante para el mensaje. " +
                        "No debes emitir ninguna otra información.\n\n" +
                        "Respuesta:"
            } else {
                msg.ifBlank { "Hola" }
            }
        }
    }

    private fun getSessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions =
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(10)
            .setTemperature(0.7f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            .build()

    private fun uriToMPImage(uri: Uri): MPImage? {
        return try {
            val inputStream: InputStream? = application.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            BitmapImageBuilder(bitmap).build()
        } catch (e: Exception) {
            Log.e("ChatBotViewModel", "Error converting URI to MPImage", e)
            null
        }
    }

    fun onUserMessageChange(message: String) {
        _uiState.update { it.copy(currentMessage = message) }
    }

    fun onImageSelected(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun stopTts() {
        textToSpeech?.stop()
    }

    override fun onCleared() {
        super.onCleared()
        llmInference?.close()
        textToSpeech?.shutdown()
        Log.d("ChatBotViewModel", "ViewModel cleared and resources released.")
    }
}