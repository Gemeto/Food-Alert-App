package id.gemeto.rasff.notifier.domain.service

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.await
import java.util.Optional
import kotlin.math.sqrt

/**
 * Clase singleton para generar embeddings usando el modelo Gecko.
 * Garantiza que solo exista una instancia en memoria, independientemente
 * de desde cuántas actividades se intente instanciar.
 */
class TitleVectorizerService private constructor(
    private val embeddingModelPath: String,
    private val sentencePieceModelPath: String? = null,
    private val useGpu: Boolean = true
) {

    private val geckoModel: GeckoEmbeddingModel

    init {
        geckoModel = GeckoEmbeddingModel(
            embeddingModelPath,
            Optional.ofNullable(sentencePieceModelPath),
            useGpu
        )
    }

    /**
     * Genera un vector de embedding para el texto de entrada.
     *
     * @param input El texto para el cual generar el embedding
     * @param taskType El tipo de tarea (por defecto RETRIEVAL_DOCUMENT)
     * @return List<Float> con el vector de embedding
     */
    suspend fun getVector(
        input: String,
        taskType: EmbedData.TaskType = EmbedData.TaskType.RETRIEVAL_DOCUMENT
    ): List<Float> {
        val cleanInput = input.trim()
        val embedData = EmbedData.builder<String>()
            .setData(cleanInput)
            .setTask(taskType)
            .build()
        val embeddingRequest = EmbeddingRequest.create(ImmutableList.of(embedData))
        val embeddings = geckoModel.getEmbeddings(embeddingRequest).await()
        return embeddings.toList()
    }

    fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Float {
        if (vec1.size != vec2.size) {
            return 0.0f
        }
        if (vec1.isEmpty()) {
            return 0.0f
        }

        var dotProduct = 0.0f
        var magnitude1 = 0.0f
        var magnitude2 = 0.0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            magnitude1 += vec1[i] * vec1[i]
            magnitude2 += vec2[i] * vec2[i]
        }

        magnitude1 = sqrt(magnitude1)
        magnitude2 = sqrt(magnitude2)

        return if (magnitude1 == 0.0f || magnitude2 == 0.0f) {
            0.0f // Division by zero, vectors have no magnitude or are zero vectors
        } else {
            dotProduct / (magnitude1 * magnitude2)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: TitleVectorizerService? = null

        /**
         * Obtiene la instancia singleton del servicio.
         * Thread-safe usando double-checked locking.
         */
        fun getInstance(
            embeddingModelPath: String = "/data/local/tmp/gecko.tflite",
            sentencePieceModelPath: String? = "/data/local/tmp/sentencepiece.model",
            useGpu: Boolean = true
        ): TitleVectorizerService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TitleVectorizerService(
                    embeddingModelPath,
                    sentencePieceModelPath,
                    useGpu
                ).also { INSTANCE = it }
            }
        }

        /**
         * Factory method para crear una instancia con rutas por defecto.
         * Utiliza el patrón singleton internamente.
         */
        fun createDefault(): TitleVectorizerService {
            return getInstance()
        }

        /**
         * Método para limpiar la instancia singleton (útil para testing).
         * ¡Usar con precaución en producción!
         */
        @Synchronized
        fun clearInstance() {
            INSTANCE = null
        }
    }
}