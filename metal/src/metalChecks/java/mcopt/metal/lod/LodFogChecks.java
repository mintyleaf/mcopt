package mcopt.metal.lod;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.level.material.FogType;

/** Regression for environmental fog transitions crossing the vanilla haze threshold. */
public final class LodFogChecks {
	public static void main(String[] args) {
		float previous = 0;
		for (int step = 0; step <= 256; step++) {
			float end = 480 + step * 0.25F, start = end / 4;
			FogData fog = fog(start, end);
			LodFog.adjust(fog, FogType.ATMOSPHERIC, 8192, -1, 1, true);
			equal(start, fog.environmentalStart);
			equal(end, fog.environmentalEnd);
			equal(8192, fog.renderDistanceEnd);
			equal(8128, fog.renderDistanceStart);
			if (step > 0) equal(0.25F, fog.environmentalEnd - previous);
			previous = fog.environmentalEnd;
			equal(768, fog.skyEnd);
			equal(192, fog.cloudEnd);
		}
		for (FogType type : FogType.values()) {
			if (type == FogType.ATMOSPHERIC) continue;
			FogData fog = fog(20, 600);
			LodFog.adjust(fog, type, 8192, -1, 1, true);
			equal(20, fog.environmentalStart);
			equal(600, fog.environmentalEnd);
			equal(192, fog.renderDistanceEnd);
		}
		FogData disabled = fog(20, 600);
		LodFog.adjust(disabled, FogType.ATMOSPHERIC, 0, -1, 1, true);
		equal(192, disabled.renderDistanceEnd);
		equal(600, disabled.environmentalEnd);
		FogData vanilla = fog(128, 1024);
		LodFog.adjust(vanilla, FogType.ATMOSPHERIC, 8192, 0.8, 1, false);
		equal(6144, vanilla.environmentalStart);
		equal(8192, vanilla.environmentalEnd);
		equal((float) (8192 * 0.8), vanilla.renderDistanceStart);
		FogData clear = fog(0,1024), rain = fog(-160,768), dense = fog(0,96);
		LodFog.adjust(clear,FogType.ATMOSPHERIC,8192,-1,1,false);
		LodFog.adjust(rain,FogType.ATMOSPHERIC,8192,-1,1,false);
		LodFog.adjust(dense,FogType.ATMOSPHERIC,8192,-1,1,false);
		equal(6144,clear.environmentalStart); equal(8192,clear.environmentalEnd);
		equal(4608,rain.environmentalStart); equal(6144,rain.environmentalEnd);
		equal(0,dense.environmentalStart); equal(96,dense.environmentalEnd);
		for (boolean editor : new boolean[]{false,true}) {
			FogData endFog=fog(0,192);
			LodFog.adjust(endFog,FogType.ATMOSPHERIC,8192,-1,1,editor,true);
			equal(8192,endFog.environmentalEnd);equal(8192*0.65F,endFog.environmentalStart);
			equal(8192,endFog.renderDistanceEnd);
			for(FogType type: FogType.values()) {
				if(type==FogType.ATMOSPHERIC) continue;
				FogData fluid=fog(0,16);
				LodFog.adjust(fluid,type,8192,-1,1,editor,true);
				equal(16,fluid.environmentalEnd);equal(192,fluid.renderDistanceEnd);
			}
			FogData nether=fog(0,96);
			LodFog.adjust(nether,FogType.ATMOSPHERIC,1024,-1,1,editor,true);
			equal(1024,nether.environmentalEnd);equal(665.6F,nether.environmentalStart);
			FogData lava=fog(0,1);
			LodFog.adjust(lava,FogType.LAVA,1024,-1,1,editor,true);
			equal(1,lava.environmentalEnd);
			FogData longer=fog(400,2048);
			LodFog.adjust(longer,FogType.ATMOSPHERIC,1024,-1,1,true,true);
			equal(2048,longer.environmentalEnd);equal(665.6F,longer.environmentalStart);
			for (int k=0;k<=256;k++) {
				float end=992+k*0.25F;
				FogData transition=fog(end/4,end);
				LodFog.adjust(transition,FogType.ATMOSPHERIC,1024,-1,1,editor,true);
				equal(Math.max(1024,end),transition.environmentalEnd);equal(665.6F,transition.environmentalStart);
			}
		}
		System.out.println("LOD fog: 257 transition steps remain continuous across 512; terrain cutoff, vanilla haze and fluid fog passed");
	}

	private static FogData fog(float start, float end) {
		FogData fog = new FogData();
		fog.environmentalStart = start;
		fog.environmentalEnd = end;
		fog.renderDistanceStart = 176;
		fog.renderDistanceEnd = 192;
		fog.skyEnd = 768;
		fog.cloudEnd = 192;
		return fog;
	}

	private static void equal(float expected, float actual) {
		if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
	}
}
