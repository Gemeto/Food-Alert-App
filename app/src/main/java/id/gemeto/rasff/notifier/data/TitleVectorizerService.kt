package id.gemeto.rasff.notifier.data

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TitleVectorizerService(
    private val context: Context,
    private val modelAssetPath: String = "universal_sentence_encoder.tflite",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "TitleVectorizerService"
    }

    private var textEmbedder: TextEmbedder? = null
    private var vectorSize: Int = 512 // Default, will be updated from embedder

    init {
        // Initialize the embedder
        initializeEmbedder()
    }

    private fun initializeEmbedder() {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)

            val optionsBuilder = TextEmbedderOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setQuantize(false) // Set to false for float embeddings
                .setL2Normalize(true) // Normalize embeddings

            val options = optionsBuilder.build()
            textEmbedder = TextEmbedder.createFromOptions(context, options)

            Log.i(TAG, "MediaPipe TextEmbedder initialized successfully with model: $modelAssetPath")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaPipe TextEmbedder: ${e.message}", e)
            textEmbedder = null
        }
    }

    suspend fun getVector(title: String): List<Float> {
        val embedder = textEmbedder
        if (embedder == null) {
            Log.e(TAG, "MediaPipe TextEmbedder not initialized. Returning zero vector.")
            return List(vectorSize) { 0.0f }
        }

        return withContext(ioDispatcher) {
            try {
                val embeddingResult = embedder.embed(title)
                Log.d(TAG, " Titttle: $title, MediaPipe TextEmbedder result: $embeddingResult")

                if (embeddingResult.embeddingResult().embeddings().isNotEmpty()) {
                    val sentenceEmbedding = embeddingResult.embeddingResult().embeddings()[0]

                    // Get the float embedding (not quantized)
                    val floatEmbedding = sentenceEmbedding.floatEmbedding()

                    // Update vector size based on actual embedding
                    vectorSize = floatEmbedding.size

                    // Log embedding information
                    if (sentenceEmbedding.headName().isPresent) {
                        Log.d(TAG, "Embedding head name: ${sentenceEmbedding.headName().get()}")
                    }
                    Log.d(TAG, "Embedding vector dimension: ${floatEmbedding.size}")

                    // Convert to List<Float>
                    return@withContext floatEmbedding.toList()
                } else {
                    Log.w(TAG, "MediaPipe TextEmbedder returned no embeddings for title: $title")
                    return@withContext List(vectorSize) { 0.0f }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during MediaPipe text embedding for title '$title': ${e.message}", e)
                return@withContext List(vectorSize) { 0.0f }
            }
        }
    }

    fun close() {
        textEmbedder?.close()
        textEmbedder = null
        Log.i(TAG, "MediaPipe TextEmbedder closed.")
    }
}