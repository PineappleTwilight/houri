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
 * Only achromatic pixels (shades of white/black/gray: paper, ink, screentones)
 * are swapped — paper-white becomes black, ink-black becomes white, gray tones
 * invert. Chromatic (colored) pixels pass through untouched, so color pages keep
 * their original colors instead of turning into photo-negatives.
 *
 * AMOLED subtoggle: crushes near-blacks (inverted-paper residue, JPEG noise) to
 * pure black so OLED pixels turn fully off and save battery.
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
            b.putFloat(0f)
            b.putFloat(0f)
            b.putFloat(0f)
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
    _pad0: f32,
    _pad1: f32,
    _pad2: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureLoad(src, vec2<i32>(in.position.xy), 0);
    if (texel.a <= 0.0) { return texel; }
    let c = texel.rgb / texel.a;
    // Saturation gate: 0 for shades of white/black/gray, ~1 for colors.
    let mx = max(c.r, max(c.g, c.b));
    let mn = min(c.r, min(c.g, c.b));
    var sat = 0.0;
    if (mx > 0.0001) {
        sat = (mx - mn) / mx;
    }
    // 1 for achromatic pixels, 0 for colored ones, smooth blend between so
    // tinted paper/antialiased text edges don't get a hard cutoff.
    let gray = 1.0 - smoothstep(0.15, 0.35, sat);
    let swapped = vec3<f32>(1.0) - c;
    var out_c = mix(c, swapped, gray);
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
