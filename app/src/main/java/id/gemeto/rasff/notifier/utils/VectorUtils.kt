package id.gemeto.rasff.notifier.utils

import kotlin.math.sqrt

object VectorUtils {

    /**
     * Calculates the cosine similarity between two vectors.
     *
     * @param vec1 The first vector.
     * @param vec2 The second vector.
     * @return The cosine similarity as a Float. Returns 0.0f if vectors are different sizes,
     *         or if magnitude of either vector is zero.
     */
    fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Float {
        if (vec1.size != vec2.size) {
            // Or throw an IllegalArgumentException("Vectors must be of the same size.")
            return 0.0f
        }
        if (vec1.isEmpty()) { // or vec2.isEmpty(), since sizes are same
            return 0.0f // Or handle as an error, though similarity with empty is undefined
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
}
