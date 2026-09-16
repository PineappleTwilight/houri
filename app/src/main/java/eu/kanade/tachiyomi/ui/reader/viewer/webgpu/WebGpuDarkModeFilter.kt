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
 * Manga pages are overwhelmingly paper-white background with ink-black text/art. A naive
 * per-channel negative (1 - rgb) swaps those two correctly but mangles color pages into
 * photo-negatives. This filter instead inverts the HSV *value* (brightness) while preserving
 * hue and saturation, so:
 * - white paper (v=1) becomes black (v=0), black ink (v=0) becomes white (v=1)
 * - gray screentones invert cleanly (achromatic, hue undefined)
 * - color art keeps recognizable hues, only the lightness flips
 *
 * AMOLED subtoggle: crushes near-blacks (inverted-paper residue, JPEG noise) to pure black
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

fn rgb_to_hsv(c: vec3<f32>) -> vec3<f32> {
    let mx = max(c.r, max(c.g, c.b));
    let mn = min(c.r, min(c.g, c.b));
    let delta = mx - mn;
    var h = 0.0;
    if (delta > 0.0001) {
        if (mx == c.r) {
            h = fract((c.g - c.b) / delta + 6.0) / 6.0;
        } else if (mx == c.g) {
            h = ((c.b - c.r) / delta + 2.0) / 6.0;
        } else {
            h = ((c.r - c.g) / delta + 4.0) / 6.0;
        }
    }
    var s = 0.0;
    if (mx > 0.0001) {
        s = delta / mx;
    }
    return vec3<f32>(h, s, mx);
}

fn hsv_to_rgb(c: vec3<f32>) -> vec3<f32> {
    if (c.y <= 0.0001) {
        return vec3<f32>(c.z);
    }
    let h = fract(c.x) * 6.0;
    let i = floor(h);
    let f = h - i;
    let p = c.z * (1.0 - c.y);
    let q = c.z * (1.0 - f * c.y);
    let t = c.z * (1.0 - (1.0 - f) * c.y);
    if (i < 0.5) { return vec3<f32>(c.z, t, p); }
    if (i < 1.5) { return vec3<f32>(q, c.z, p); }
    if (i < 2.5) { return vec3<f32>(p, c.z, t); }
    if (i < 3.5) { return vec3<f32>(p, q, c.z); }
    if (i < 4.5) { return vec3<f32>(t, p, c.z); }
    return vec3<f32>(c.z, p, q);
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureLoad(src, vec2<i32>(in.position.xy), 0);
    if (texel.a <= 0.0) { return texel; }
    let c = texel.rgb / texel.a;
    let hsv = rgb_to_hsv(c);
    // Swap the two shades: paper-white (v=1) <-> ink-black (v=0). Hue/sat kept
    // so color art stays recognizable instead of becoming a photo-negative.
    let swapped = hsv_to_rgb(vec3<f32>(hsv.x, hsv.y, 1.0 - hsv.z));
    var out_c = swapped;
    if (params.amoled > 0.5) {
        let luma = dot(swapped, vec3<f32>(0.299, 0.587, 0.114));
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
