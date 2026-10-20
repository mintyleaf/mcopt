package mcopt.metal.lod;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.level.material.FogType;

/** Extends the terrain cutoff independently of a fog editor's environmental range. */
final class LodFog {
	private LodFog() {
	}

	static void adjust(FogData fog, FogType type, float reach, double fogStart, double haze, boolean preserveEnvironmentalRange) {
		adjust(fog, type, reach, fogStart, haze, preserveEnvironmentalRange, false);
	}

	static void adjust(FogData fog, FogType type, float reach, double fogStart, double haze, boolean preserveEnvironmentalRange, boolean extendAtmosphere) {
		if (reach <= 0 || type != FogType.ATMOSPHERIC) return;
		fog.renderDistanceStart = fogStart >= 0 ? (float) (reach * fogStart) : reach - Math.clamp(reach / 10.0F, 4.0F, 64.0F);
		fog.renderDistanceEnd = reach;
		// Nether and End fog normally ends well before a LOD can begin. Extend their atmosphere too,
		// including fog-editor presets, while keeping their color and any already-longer environmental range.
		if (extendAtmosphere) {
			float end = (float)(reach * haze);
			fog.environmentalEnd = Math.max(fog.environmentalEnd, end);
			// The same minimum start applies on both sides of the end threshold, keeping fog-editor transitions continuous.
			fog.environmentalStart = Math.max(fog.environmentalStart, end * 0.65F);
			return;
		}
		// A fog editor can smoothly cross 512. Applying the vanilla haze multiplier at that boundary causes a sudden jump.
		if (!preserveEnvironmentalRange && fog.environmentalEnd >= 512) {
			float scale = Math.max(1.0F, (float) (haze * reach) / 1024.0F);
			fog.environmentalStart *= scale;
			fog.environmentalEnd *= scale;
			// Vanilla starts clear-weather haze at zero, so stretching only its end still veils most of the LOD range.
			// Keep that haze in the outer quarter. Rain retains its shorter end; dense biome/effect fog stays unchanged.
			fog.environmentalStart = Math.max(fog.environmentalStart, fog.environmentalEnd * 0.75F);
		}
	}
}
