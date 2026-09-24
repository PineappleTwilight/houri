package exh.yakuyomi

// KMK --> Qwen3 text-only preset catalog invariants + tier-5 default behavior.
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class LocalLlmCatalogTest {

    @Test
    fun `qwen3-1_7b preset carries exact text-only GGUF metadata`() {
        val model = LocalLlmCatalog.byId("qwen3-1.7b").shouldNotBeNull()

        model.displayName shouldBe "Qwen3 1.7B"
        model.paramsB shouldBe "1.7B"
        model.qualityTier shouldBe 2
        model.isTranslationFinetune shouldBe false
        model.supportsVision shouldBe false
        model.sizeBytes shouldBe 1_257_880_128L
        model.minRamBytes shouldBe 3L * 1024 * 1024 * 1024
        model.ggufRepo shouldBe "unsloth/Qwen3-1.7B-GGUF"
        model.ggufFile shouldBe "Qwen3-1.7B-Q5_K_M.gguf"
        model.mmprojRepo.shouldBeNull()
        model.mmprojFile.shouldBeNull()
        model.contextLength shouldBe 4096
        model.defaultSampling.shouldNotBeNull().let {
            it.temperature shouldBe 0.3f
            it.repeatPenalty shouldBe 1.0f
            it.contextLength shouldBe 4096
        }
    }

    @Test
    fun `qwen3-4b preset carries exact text-only GGUF metadata`() {
        val model = LocalLlmCatalog.byId("qwen3-4b").shouldNotBeNull()

        model.displayName shouldBe "Qwen3 4B"
        model.paramsB shouldBe "4B"
        model.qualityTier shouldBe 4
        model.isTranslationFinetune shouldBe false
        model.supportsVision shouldBe false
        model.sizeBytes shouldBe 2_889_514_272L
        model.minRamBytes shouldBe 4L * 1024 * 1024 * 1024
        model.ggufRepo shouldBe "unsloth/Qwen3-4B-GGUF"
        model.ggufFile shouldBe "Qwen3-4B-Q5_K_M.gguf"
        model.mmprojRepo.shouldBeNull()
        model.mmprojFile.shouldBeNull()
        model.contextLength shouldBe 4096
        model.defaultSampling.shouldNotBeNull().let {
            it.temperature shouldBe 0.3f
            it.repeatPenalty shouldBe 1.0f
            it.contextLength shouldBe 4096
        }
    }

    @Test
    fun `qwen3-4b is gated out below 4 GiB while qwen3-1_7b fits at 3 GiB`() {
        val threeGiB = 3L * 1024 * 1024 * 1024
        val fourGiB = 4L * 1024 * 1024 * 1024

        LocalLlmCatalog.fitForRam(totalRamBytes = threeGiB).map { it.id } shouldContain "qwen3-1.7b"
        LocalLlmCatalog.fitForRam(totalRamBytes = threeGiB).map { it.id } shouldNotContain "qwen3-4b"
        LocalLlmCatalog.fitForRam(totalRamBytes = fourGiB).map { it.id } shouldContain "qwen3-4b"
    }

    @Test
    fun `bestForDevice still defaults to the tier-5 gemma model on capable devices`() {
        LocalLlmCatalog.bestForDevice(8L * 1024 * 1024 * 1024)?.id shouldBe "gemma-4-e4b-it"
    }

    @Test
    fun `qwen3-4b outranks qwen3-1_7b`() {
        val qwen3FourB = LocalLlmCatalog.byId("qwen3-4b").shouldNotBeNull()
        val qwen3OnePointSevenB = LocalLlmCatalog.byId("qwen3-1.7b").shouldNotBeNull()

        qwen3FourB.qualityTier shouldBeGreaterThan qwen3OnePointSevenB.qualityTier
    }
}
// KMK <--
