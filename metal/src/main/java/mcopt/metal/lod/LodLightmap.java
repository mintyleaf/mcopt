package mcopt.metal.lod;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.lwjgl.system.MemoryUtil;

/** The game's lightmap.fsh evaluated into 256 half4 texels; stays below Metal's 4 KiB inline-uniform limit. */
final class LodLightmap {
	static final int FRAME_BYTES = 272 + 256 * 8;

	private LodLightmap() { }

	static void write(long frame, LightmapRenderState s) {
		MemoryUtil.memPutFloat(frame + 256, LodConfig.LIGHTING ? 1 : 0);
		MemoryUtil.memPutFloat(frame + 260, LodConfig.SURFACE_DATA ? 1 : 0);
		for (int sky = 0; sky < 16; sky++) for (int block = 0; block < 16; block++) {
			float b = brightness(block) * s.blockFactor, k = brightness(sky) * s.skyFactor;
			float p = 2 * block / 15.0F - 1, tintMix = 0.9F * p * p;
			float nv = s.nightVisionEffectIntensity;
			float r = Math.max(s.ambientColor.x(), s.nightVisionColor.x() * nv) + s.skyLightColor.x() * k
				+ (s.blockLightTint.x() + (1 - s.blockLightTint.x()) * tintMix) * b;
			float g = Math.max(s.ambientColor.y(), s.nightVisionColor.y() * nv) + s.skyLightColor.y() * k
				+ (s.blockLightTint.y() + (1 - s.blockLightTint.y()) * tintMix) * b;
			float blue = Math.max(s.ambientColor.z(), s.nightVisionColor.z() * nv) + s.skyLightColor.z() * k
				+ (s.blockLightTint.z() + (1 - s.blockLightTint.z()) * tintMix) * b;
			r = Math.clamp(r * (1 - 0.3F * s.bossOverlayWorldDarkening) - s.darknessEffectScale, 0, 1);
			g = Math.clamp(g * (1 - 0.4F * s.bossOverlayWorldDarkening) - s.darknessEffectScale, 0, 1);
			blue = Math.clamp(blue * (1 - 0.4F * s.bossOverlayWorldDarkening) - s.darknessEffectScale, 0, 1);
			float max = Math.max(r, Math.max(g, blue));
			if (max > 0) {
				float inv = 1 - max, gamma = (1 - inv * inv * inv * inv) / max;
				r += (r * gamma - r) * s.brightness;
				g += (g * gamma - g) * s.brightness;
				blue += (blue * gamma - blue) * s.brightness;
			}
			long at = frame + 272 + (sky * 16L + block) * 8;
			MemoryUtil.memPutShort(at, Float.floatToFloat16(r));
			MemoryUtil.memPutShort(at + 2, Float.floatToFloat16(g));
			MemoryUtil.memPutShort(at + 4, Float.floatToFloat16(blue));
			MemoryUtil.memPutShort(at + 6, Float.floatToFloat16(1));
		}
	}

	private static float brightness(int level) {
		float v = level / 15.0F;
		return v / (4 - 3 * v);
	}
}
