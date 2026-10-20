package mcopt.metal.lod;

import java.util.BitSet;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

public final class LodSurfaceChecks {
	public static void main(String[] args) {
		if (LodChunks.surfaceY(100,-64,y->y>63)!=63 || LodChunks.surfaceY(-10,-64,y->y>-48)!=-48
			|| LodChunks.surfaceY(20,-64,y->false)!=20 || LodChunks.surfaceY(20,0,y->true)!=0)
			throw new AssertionError("Long hanging decoration becomes the terrain surface or scan crosses the world floor");
		int[] scans={0};
		LodChunks.surfaceY(300,-64,y->{scans[0]++;return true;});
		if (scans[0]>128) throw new AssertionError("Decoration scan exceeded its bounded snapshot budget");
		LodTile micro=new LodTile(0,-292,396,4);
		java.util.Arrays.fill(micro.height,(short)63); java.util.Arrays.fill(micro.water,LodTile.DRY);
		int[] mg=new int[16], mc=new int[16], mcr=new int[16], mr=new int[16];
		LodField.pack(micro,mg,mc,mcr,mr,null);
		for (int word : mg) if (word!=LodClip.geomWord(63,false)) throw new AssertionError("Microtile cache repair uses full-tile indexing or changes its surface height");
		BitSet blocks = new BitSet();
		blocks.set(0, 65);
		blocks.set(80); // A one-block stair eave or mushroom cap over the ground.
		layer(blocks, 80, 80, 64);
		blocks.set(81, 84); // A thicker bridge deck, still independent of the ground.
		layer(blocks, 83, 80, 64);
		blocks.set(65, 80); // A real supporting wall must stay solid.
		if (LodOverhang.find(83, 0, blocks::get) != null) throw new AssertionError("Solid wall split into floating roof");
		blocks.clear();
		blocks.set(20);
		blocks.set(149);
		layer(blocks, 149, 149, 20); // Maximum 128-block air gap.
		blocks.clear(149);
		blocks.set(150);
		if (LodOverhang.find(150, 0, blocks::get) != null) throw new AssertionError("Unbounded gap scan");
		int[] probes = {0};
		if (LodOverhang.find(300, -64, y -> { probes[0]++; return true; }) != null || probes[0] > 253)
			throw new AssertionError("Solid terrain scan exceeded thickness budget");
		var negative = LodOverhang.find(-10, -64, y -> y == -10 || y <= -30);
		if (negative == null || negative.bottom() != -10 || negative.ground() != -30) throw new AssertionError("Negative world height");
		// Shapes from the saved world's red mushroom: a three-block edge and a one-block dome over air, beside its stem.
		var edge = LodOverhang.find(99, -64, y -> y >= 97 && y <= 99 || y <= 93);
		var dome = LodOverhang.find(100, -64, y -> y == 100 || y <= 93);
		if (edge == null || edge.bottom() != 97 || edge.ground() != 93 || dome == null || dome.bottom() != 100 || dome.ground() != 93)
			throw new AssertionError("Mushroom cap extended to the ground");
		if (LodOverhang.find(100, -64, y -> y <= 100) != null) throw new AssertionError("Mushroom stem lost its support");
		var shoreCap = LodOverhang.mushroom(67, -64, y -> y == 67 ? 1 : y <= 62 ? 2 : 0);
		if (shoreCap == null || shoreCap.bottom() != 67 || shoreCap.ground() != 62 || shoreCap.occupied() != 1)
			throw new AssertionError("Shoreline mushroom filled the air above water");
		// Multiple caps and intervening leaves are not a solid floor. Keep separate occupied runs down to real terrain.
		var tiers = LodOverhang.mushroom(100, -64, y -> y == 100 || y == 97 || y == 96 ? 1 : y <= 90 ? 2 : 0);
		if (tiers == null || tiers.bottom() != 96 || tiers.ground() != 90 || tiers.occupied() != 0b10011)
			throw new AssertionError("Layered mushroom lost its gaps: " + tiers);
		if (LodOverhang.mushroom(100, -64, y -> y == 100 ? 1 : 2) != null) throw new AssertionError("Mushroom stem became floating");
		if (LodOverhang.exposedGap(0,0) || LodOverhang.exposedGap(0x050505F0)
			|| !LodOverhang.exposedGap(1,0) || !LodOverhang.exposedGap(0x100000F0))
			throw new AssertionError("Sealed cave/geode accepted as an outdoor overhang, or exterior eave rejected");
		// Exact shape of a cached cave roof: grass at y65, amethyst at y31, lit roof and dark cavity.
		int[] cg={0x10E241}, cc={0x8B585368}, cr={0x08B5821F}, ct={LodClip.TEX_LIGHT|LodClip.TEX_OVERHANG|LodPalette.word(1,2,2)}, rr={123};
		int height=cg[0]&4095, topColor=cc[0]&65535;
		LodOverhang.closeCached(0,cg,cc,cr,ct,rr,0x567843,1,3,0x705030);
		if ((cg[0]&LodClip.GEOM_CROWN_BITS)!=0 || (cg[0]&4095)!=height || (cc[0]&65535)!=topColor
			|| (ct[0]&1023)!=1 || ((ct[0]>>>10)&1023)!=1 || ((ct[0]>>>20)&1023)!=3 || rr[0]!=0
			|| cr[0]!=LodClip.belowWord(0x705030)) throw new AssertionError("Cave closure leaked geode material or changed surface/build height");
		if (LodChunks.validLight(false,true,15) || LodChunks.validLight(true,true,0) || LodChunks.validLight(true,true,14)
			|| !LodChunks.validLight(true,true,15) || !LodChunks.validLight(true,false,0)) throw new AssertionError("Unready light accepted or valid dimension rejected");
		if (LodChunks.validLight(true,true,15,0,true) || LodChunks.validLight(true,true,15,0xE5,true)
			|| !LodChunks.validLight(true,true,15,0xF5,true) || !LodChunks.validLight(true,true,15,0,false)
			|| !LodChunks.validLight(true,false,0,0,true)) throw new AssertionError("Capture changed after readiness probe, or real shadows rejected");
		if (LodChunks.validStoredLight(0,true) || LodChunks.validStoredLight(0x0F0F0F0F,true)
			|| !LodChunks.validStoredLight(0xF0,true) || !LodChunks.validStoredLight(0xF0000000,true)
			|| !LodChunks.validStoredLight(0,false)) throw new AssertionError("Unready whole-column sky retained, or real lower-surface shadows discarded");
		if (LodChunks.repairSurfaceLight(LodClip.geomWord(65,false),0xF000,true)!=0xF0F0
			|| LodChunks.repairSurfaceLight(LodClip.crownGeomWord(81,1,false),0x15020007,true)!=0x150200F7
			|| LodChunks.repairSurfaceLight(LodClip.geomWord(65,false),0,true)!=0
			|| LodChunks.repairSurfaceLight(LodClip.geomWord(65,false),0xF000,false)!=0xF000)
			throw new AssertionError("Partial top light not repaired, or true lower shadows/unknown/no-sky samples changed");
		LodClip.Snapshot snapshot = new LodClip.Snapshot(0, -1, 2, true, true, true);
		int last = LodTile.CELLS - 1;
		snapshot.tw[last] = LodClip.TEX_OVERHANG | LodClip.TEX_LIGHT | LodPalette.word(1, 2, 3);
		if (LodConfig.LIGHTING) snapshot.tw[LodTile.CELLS + last] = 0x12AB34CD;
		LodClip.Snapshot copy = snapshot.copy();
		if (!java.util.Arrays.equals(snapshot.tw, copy.tw) || snapshot.tw == copy.tw) throw new AssertionError("Surface publication lost light/metadata");
		checkLightmap();
		System.out.println("PASS: roofs, mushroom-like caps, solid supports, bounded/negative-height extraction, staged light data and day/night lightmap");
	}

	private static void layer(BitSet blocks, int top, int bottom, int ground) {
		var layer = LodOverhang.find(top, 0, blocks::get);
		if (layer == null || layer.bottom() != bottom || layer.ground() != ground) throw new AssertionError("Wrong floating layer: " + layer);
	}

	private static void checkLightmap() {
		long f = MemoryUtil.nmemCalloc(1, LodLightmap.FRAME_BYTES);
		try {
			if (LodLightmap.FRAME_BYTES > 4096) throw new AssertionError("Metal inline uniform limit");
			LightmapRenderState s = new LightmapRenderState();
			s.blockLightTint = new Vector3f(1.0F, 0.6F, 0.2F);
			s.skyLightColor = new Vector3f(1);
			s.ambientColor = new Vector3f();
			s.nightVisionColor = new Vector3f();
			s.blockFactor = 1;
			s.skyFactor = 1;
			LodLightmap.write(f, s);
			near(1, channel(f, 15, 0, 0));
			near(0, channel(f, 0, 0, 0));
			float day = channel(f, 15, 0, 0);
			s.skyFactor = 0.05F;
			LodLightmap.write(f, s);
			if (channel(f, 15, 0, 0) >= day || channel(f, 0, 15, 0) <= 0.9F) throw new AssertionError("Torch lost brightness at night");
			if (channel(f, 0, 14, 0) <= channel(f, 0, 14, 2)) throw new AssertionError("Block-light warmth lost");
			s.brightness = 1;
			s.darknessEffectScale = 0.25F;
			s.bossOverlayWorldDarkening = 0.5F;
			LodLightmap.write(f, s);
			for (int i = 0; i < 256; i++) for (int c = 0; c < 3; c++) {
				float v = Float.float16ToFloat(MemoryUtil.memGetShort(f + 272 + i * 8L + c * 2L));
				if (!Float.isFinite(v) || v < 0 || v > 1) throw new AssertionError("Invalid lightmap texel");
			}
			near(0, channel(f, 0, 0, 0));
		} finally { MemoryUtil.nmemFree(f); }
	}

	private static float channel(long f, int sky, int block, int channel) {
		return Float.float16ToFloat(MemoryUtil.memGetShort(f + 272 + (sky * 16L + block) * 8 + channel * 2L));
	}
	private static void near(float a, float b) {
		if (Math.abs(a - b) > 0.001) throw new AssertionError("Expected " + a + ", got " + b);
	}
}
