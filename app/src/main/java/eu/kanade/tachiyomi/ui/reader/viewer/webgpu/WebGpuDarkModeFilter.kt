// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUTextureView
import ca.mpreg.webgpuviewer.filter.FilterFullscreen
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Intelligent dark-mode filter for the WebGPU reader.
 *
 * Per-pixel, hue-preserving policy: a pixel's achromatic weight comes from its
 * channel spread (shades of white, black or gray swap; anything carrying real
 * color only dims). Grays take a luminance invert that carries the chroma
 * along, so tinted paper turns dark warm/cool gray instead of hue-shifting
 * (a naive RGB 1-c turns beige skin-shadows blue). Saturated art keeps its
 * hue and is only dimmed, never brightened.
 *
 * Deliberately neighborhood-free: the previous 3x3 chunk vote produced block
 * boundaries (split speech bubbles, posterized faces, speckled windows) on
 * full-color pages. A smooth per-pixel blend cannot disagree with its
 * neighbor, so there are no seams; it is also 9x fewer taps per pixel.
 *
 * AMOLED subtoggle: crushes near-blacks (inverted-paper residue) to pure black
 * so OLED pixels turn fully off and save battery.
 *
 * Runs as a post-process [FilterFullscreen] pass, so it applies live with no page
 * re-decode. Disabled by default; [active] is [enabled] only.
 */
class WebGpuDarkModeFilter(
    amoled: Boolean = false,
) : FilterFullscreen() {

    @Volatile
    var amoled: Boolean = amoled
        set(value) {
            if (field == value) return
            field = value
            uniformsDirty = true
            invalidate()
        }

    @Volatile
    var tolerance: Float = 0.06f
        set(value) {
            if (field == value) return
            field = value
            uniformsDirty = true
            invalidate()
        }

    /**
     * Color-dim amount (0.02..0.30): saturated pixels keep their hue and are
     * scaled by (1 - this), so the slider reads "higher = darker colors".
     * Kept under the legacy [ReaderPreferences.webgpuDarkModeChunkRange] key
     * so existing user values carry over as dim strength.
     */
    @Volatile
    var chunkRange: Float = 0.10f
        set(value) {
            if (field == value) return
            field = value
            uniformsDirty = true
            invalidate()
        }

    override val active: Boolean get() = enabled
    override val code: String get() = FRAGMENT

    @Volatile
    private var uniformsDirty = true

    private val uniforms: GPUBuffer by lazy {
        device.createBuffer(
            GPUBufferDescriptor(label = label, size = 16, usage = BufferUsage.Uniform or BufferUsage.CopyDst),
        )
    }

    private val uniformBytes: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
    }

    override fun prepare(srcWidth: Int, srcHeight: Int) {
        if (uniformsDirty) {
            uniformsDirty = false
            val b = uniformBytes
            b.clear()
            b.putFloat(if (amoled) 1f else 0f)
            b.putFloat(tolerance)
            b.putFloat(tolerance + 0.03f)
            // Slot 3 now carries the color-dim factor directly (1 - dim amount)
            // so the shader stays branch-free; see chunkRange KDoc.
            b.putFloat((1f - chunkRange).coerceIn(0.5f, 1f))
            b.flip()
            device.queue.writeBuffer(uniforms, 0, b)
        }
    }

    override fun entries(src: GPUTextureView): Array<GPUBindGroupEntry> = arrayOf(
        GPUBindGroupEntry(0, buffer = uniforms),
        GPUBindGroupEntry(1, textureView = src),
    )

    override fun cleanup() {
        rebind()
    }

    companion object {
        const val FRAGMENT = """
struct Params {
    amoled: f32,
    tol: f32,
    gate: f32,
    dim: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;

fn spreadOf(c: vec3<f32>) -> f32 {
    return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let fragCoord = vec2<i32>(in.position.xy);
    let texel = textureLoad(src, fragCoord, 0);
    if (texel.a <= 0.0) { return texel; }
    let c = texel.rgb / max(texel.a, 0.1);
    let spread = spreadOf(c);
    let lum = dot(c, vec3<f32>(0.299, 0.587, 0.114));
    // Achromatic weight: grays fully swap, real color only dims.
    // Smoothstep (no neighborhood vote) so neighbors can never disagree:
    // no split bubbles, no posterized patches, no speckle.
    let w = 1.0 - smoothstep(params.tol - 0.02, params.gate, spread);
    // Hue-preserving invert: flip luminance, carry chroma along at slightly
    // reduced strength. Pure gray collapses to exactly 1-lum; tinted paper
    // lands on a dark gray of the same warmth instead of hue-shifting
    // (naive 1-c turns beige skin-shadows blue).
    let chroma = c - vec3<f32>(lum);
    let inv = clamp(vec3<f32>(1.0 - lum) + chroma * 0.9, vec3<f32>(0.0), vec3<f32>(1.0));
    // Saturated art keeps its hue, only darkens: never brightened, so night
    // skies stay night and skin stays skin.
    let dimmed = c * params.dim;
    var out_c = mix(dimmed, inv, w);
    if (params.amoled > 0.5) {
        let luma = dot(out_c, vec3<f32>(0.299, 0.587, 0.114));
        if (luma < 0.08) {
            out_c = vec3<f32>(0.0);
        }
    }
    return vec4<f32>(out_c * texel.a, texel.a);
}
"""
    }
}
// KMK <--
