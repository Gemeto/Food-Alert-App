package id.gemeto.rasff.notifier.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorUtilsTest {

    private val delta = 1e-6f // Delta for float comparisons

    @Test
    fun `cosineSimilarity with identical non-zero vectors returns 1`() {
        val vec = listOf(1.0f, 2.0f, 3.0f)
        assertEquals(1.0f, VectorUtils.cosineSimilarity(vec, vec), delta)
    }

    @Test
    fun `cosineSimilarity with orthogonal vectors returns 0`() {
        val vec1 = listOf(1.0f, 0.0f)
        val vec2 = listOf(0.0f, 1.0f)
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }

    @Test
    fun `cosineSimilarity with opposite vectors returns -1`() {
        val vec1 = listOf(1.0f, 2.0f)
        val vec2 = listOf(-1.0f, -2.0f)
        assertEquals(-1.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }

    @Test
    fun `cosineSimilarity with one zero vector returns 0`() {
        val vec1 = listOf(1.0f, 2.0f)
        val vec2 = listOf(0.0f, 0.0f)
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec2, vec1), delta)
    }

    @Test
    fun `cosineSimilarity with two zero vectors returns 0`() {
        val vec1 = listOf(0.0f, 0.0f)
        val vec2 = listOf(0.0f, 0.0f)
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }

    @Test
    fun `cosineSimilarity with vectors of different lengths returns 0`() {
        val vec1 = listOf(1.0f, 2.0f)
        val vec2 = listOf(1.0f, 2.0f, 3.0f)
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }
    
    @Test
    fun `cosineSimilarity with empty vectors returns 0`() {
        val vec1 = emptyList<Float>()
        val vec2 = emptyList<Float>()
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }

    @Test
    fun `cosineSimilarity with one empty vector returns 0`() {
        val vec1 = listOf(1.0f, 2.0f)
        val vec2 = emptyList<Float>()
        assertEquals(0.0f, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }

    @Test
    fun `cosineSimilarity with sample non-trivial vectors`() {
        // Example: vec1 = [1, 2, 3], vec2 = [4, 5, 6]
        // Dot product = 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        // Magnitude vec1 = sqrt(1^2 + 2^2 + 3^2) = sqrt(1+4+9) = sqrt(14)
        // Magnitude vec2 = sqrt(4^2 + 5^2 + 6^2) = sqrt(16+25+36) = sqrt(77)
        // Cosine Similarity = 32 / (sqrt(14) * sqrt(77)) = 32 / sqrt(1078)
        // sqrt(1078) approx 32.8329
        // Similarity approx 32 / 32.8329 approx 0.9746
        val vec1 = listOf(1.0f, 2.0f, 3.0f)
        val vec2 = listOf(4.0f, 5.0f, 6.0f)
        val expected = 32.0f / (kotlin.math.sqrt(14.0f) * kotlin.math.sqrt(77.0f))
        assertEquals(expected, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }

    @Test
    fun `cosineSimilarity with another sample`() {
        val vec1 = listOf(3.0f, 2.0f, 0.0f, 5.0f)
        val vec2 = listOf(1.0f, 0.0f, 0.0f, 0.0f)
        // Dot product = 3*1 + 2*0 + 0*0 + 5*0 = 3
        // Mag vec1 = sqrt(9+4+0+25) = sqrt(38)
        // Mag vec2 = sqrt(1) = 1
        // Sim = 3 / sqrt(38) approx 3 / 6.1644 = 0.4866
        val expected = 3.0f / kotlin.math.sqrt(38.0f)
        assertEquals(expected, VectorUtils.cosineSimilarity(vec1, vec2), delta)
    }
}
