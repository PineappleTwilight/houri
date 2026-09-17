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
 * Strict achromatic-only policy: a pixel is touched only when it is a shade of
 * white, black or gray (absolute channel spread under 0.06). Anything carrying
 * real color passes through untouched. Dark achromatic strokes swap
 * unconditionally so lettering stays legible, flat achromatic 3x3 chunks
 * bucket-swap so scanned paper inverts with no JPEG-noise speckle, and
 * remaining near-gray pixels (antialiased edges) take a narrow blend.
 * Near-transparent taps are excluded from the chunk vote.
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
            b.putFloat(chunkRange)
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
    range: f32,
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
    // Hard gate: any real color passes through untouched.
    if (spread >= params.gate) { return texel; }
    // Bucket-select probe: min/max luma and max spread over the 3x3 chunk
    // around this pixel. Near-transparent taps (feathered bubble fringes,
    // page margins) are excluded from the vote: unpremultiplying them
    // amplifies quantization noise into false color/variance.
    let maxC = vec2<i32>(textureDimensions(src)) - vec2<i32>(1, 1);
    var minL = 1.0;
    var maxL = 0.0;
    var chunkSpread = 0.0;
    var hits = 0;
    for (var oy = -1; oy <= 1; oy = oy + 1) {
        for (var ox = -1; ox <= 1; ox = ox + 1) {
            let p = clamp(fragCoord + vec2<i32>(ox, oy), vec2<i32>(0, 0), maxC);
            let t = textureLoad(src, p, 0);
            if (t.a < 0.1) { continue; }
            hits = hits + 1;
            let nc = t.rgb / t.a;
            chunkSpread = max(chunkSpread, spreadOf(nc));
            let l = dot(nc, vec3<f32>(0.299, 0.587, 0.114));
            minL = min(minL, l);
            maxL = max(maxL, l);
        }
    }
    var out_c: vec3<f32>;
    let centerLuma = dot(c, vec3<f32>(0.299, 0.587, 0.114));
    if (centerLuma < 0.5 && spread < params.tol) {
        // Ink: dark achromatic strokes swap regardless of neighborhood, so
        // lettering stays legible. Deliberately before the chunk vote.
        out_c = vec3<f32>(1.0) - c;
    } else if (hits > 0 && chunkSpread < params.tol && (maxL - minL) < params.range) {
        // Bucket fill: one flat shade of paper or ink swaps cleanly.
        out_c = vec3<f32>(1.0) - c;
    } else {
        // Near-gray stragglers (antialiased edges) blend; visibly tinted
        // pixels never reach here.
        let gray = 1.0 - smoothstep(params.tol - 0.02, params.gate, spread);
        out_c = mix(c, vec3<f32>(1.0) - c, gray);
    }
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
