package id.gemeto.rasff.notifier.domain.service

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.await
import java.util.Optional

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
        // Inicializar el modelo Gecko con los parámetros proporcionados
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
        // Crear el objeto EmbedData con el texto de entrada
        val cleanInput = input.replace("[0-9]".toRegex(), "").replace(".".toRegex(), "").replace("-".toRegex(), "").trim()
        val embedData = EmbedData.builder<String>()
            .setData(input)
            .setTask(taskType)
            .build()

        // Crear la solicitud de embedding
        val embeddingRequest = EmbeddingRequest.create(ImmutableList.of(embedData))

        // Obtener los embeddings de forma asíncrona y convertir a List<Float>
        val embeddings = geckoModel.getEmbeddings(embeddingRequest).await()
        return embeddings.toList()
    }

    /**
     * Genera vectores de embedding para múltiples textos de entrada.
     *
     * @param inputs Lista de textos para los cuales generar embeddings
     * @param taskType El tipo de tarea (por defecto RETRIEVAL_DOCUMENT)
     * @return List<List<Float>> con los vectores de embedding
     */
    suspend fun getBatchVectors(
        inputs: List<String>,
        taskType: EmbedData.TaskType = EmbedData.TaskType.RETRIEVAL_DOCUMENT
    ): List<List<Float>> {
        if (inputs.isEmpty()) {
            return emptyList()
        }

        // Crear objetos EmbedData para cada texto de entrada
        val embedDataList = inputs.map { input ->
            EmbedData.builder<String>()
                .setData(input)
                .setTask(taskType)
                .build()
        }

        // Crear la solicitud de embedding por lotes
        val embeddingRequest = EmbeddingRequest.create(ImmutableList.copyOf(embedDataList))

        // Obtener los embeddings de forma asíncrona
        val embeddingsList = geckoModel.getBatchEmbeddings(embeddingRequest).await()

        // Convertir ImmutableList<ImmutableList<Float>> a List<List<Float>>
        return embeddingsList.map { it.toList() }
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