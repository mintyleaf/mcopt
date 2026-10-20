package mcopt.metal.lod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import sun.misc.Unsafe;

/** Exercises the real disk codec without starting world-generation workers or allocating a GPU clipmap. */
public final class LodCacheChecks {
	public static void main(String[] args) throws Exception {
		Field uf = Unsafe.class.getDeclaredField("theUnsafe");
		uf.setAccessible(true);
		LodField field = (LodField) ((Unsafe) uf.get(null)).allocateInstance(LodField.class);
		Path dir = Files.createTempDirectory("mcopt-lod-surface-cache-");
		try {
			Field cache = LodField.class.getDeclaredField("cache");
			cache.setAccessible(true);
			cache.set(field, dir);
			Class<?>[] signature = {long.class, int[].class, int[].class, int[].class, int[].class, int[].class, int[].class};
			Method save = LodField.class.getDeclaredMethod("save", signature), load = LodField.class.getDeclaredMethod("load", signature);
			save.setAccessible(true); load.setAccessible(true);
			int n = LodTile.CELLS;
			int[] g = new int[n], c = new int[n], cr = new int[n], tw = new int[2*n], runs = new int[n], plants = new int[2*n];
			for (int i=0;i<n;i++) {
				g[i] = LodClip.crownGeomWord(81,1,false);
				c[i] = i | (n-i)<<16;
				cr[i] = LodClip.crownWord(64,0x102030,(i&1)==0);
				tw[i] = LodClip.TEX_OVERHANG | LodClip.TEX_LIGHT; // Palette zero avoids needing a resource-pack/model manager.
				tw[n+i] = i < 256 ? 0 : Integer.rotateLeft(0xF07B1203,i&31);
				plants[n+i] = i;
			}
			long key = LodTile.key(0,-12,5);
			save.invoke(field,key,g,c,cr,tw,runs,plants);
			int[][] expected = {g,c,cr,tw,runs,plants};
			int[][] actual = Arrays.stream(expected).map(a -> new int[a.length]).toArray(int[][]::new);
			boolean loaded = (boolean) load.invoke(field,key,actual[0],actual[1],actual[2],actual[3],actual[4],actual[5]);
			if (!loaded) throw new AssertionError("Surface cache did not load");
			for (int i=0;i<expected.length;i++) if (!Arrays.equals(expected[i],actual[i])) throw new AssertionError("Cache plane " + i + " changed");
			// Reading without lighting must still consume the light plane and leave the following plant data aligned.
			int[] shortTex = new int[n], afterPlants = new int[2*n];
			if (!(boolean) load.invoke(field,key,new int[n],new int[n],new int[n],shortTex,new int[n],afterPlants)
				|| !Arrays.equals(plants,afterPlants)) throw new AssertionError("Disabled-light cache read lost alignment");
			// Reproduce a fixed strip of valid-but-zero open-water lighting from the user's format-12 cache.
			int[] waterG = g.clone(), waterTex = tw.clone();
			waterG[0] = waterG[1] = waterG[4] = waterG[5] = LodClip.geomWord(63,true);
			waterG[3] = LodClip.geomWord(64,false);
			waterTex[n] = 0x127B0000; waterTex[n+1] = 0x03EA010D;
			waterTex[n+2] = waterTex[n+3] = waterTex[n+4] = 0;
			waterTex[4] &= ~LodClip.TEX_LIGHT; waterTex[n+5] = 0x1234F5F7;
			long waterKey = LodTile.key(0,7,-9);
			save.invoke(field,waterKey,waterG,c,cr,waterTex,runs,plants);
			Field sky = LodField.class.getDeclaredField("skyLight"); sky.setAccessible(true); sky.setBoolean(field,true);
			if (!(boolean) load.invoke(field,waterKey,actual[0],actual[1],actual[2],actual[3],actual[4],actual[5])) throw new AssertionError("Water cache fixture did not load");
			int[] repaired = waterTex.clone(); repaired[n] = 0x127BE0F0; repaired[n+1] = 0x03EAE1FD;
			for (int i=0;i<n;i++) {
				if ((repaired[i]&LodClip.TEX_LIGHT)!=0) repaired[n+i]=LodChunks.repairSurfaceLight(waterG[i],repaired[n+i],true);
				if (!LodChunks.validStoredLight(repaired[n+i],true)) repaired[i] &= ~LodClip.TEX_LIGHT;
			}
			if (!Arrays.equals(repaired,actual[3]) || !Arrays.equals(waterG,actual[0]) || !Arrays.equals(c,actual[1])
				|| !Arrays.equals(cr,actual[2]) || !Arrays.equals(plants,actual[5])) throw new AssertionError("Water repair lost shadows, torches, geometry or materials");
			if ((actual[3][3]&LodClip.TEX_LIGHT)!=0 || actual[3][n+3]!=0)
				throw new AssertionError("Exposed ground with zero cached sky did not fall back to estimated daylight");
			sky.setBoolean(field,false);
			if (!(boolean) load.invoke(field,waterKey,actual[0],actual[1],actual[2],actual[3],actual[4],actual[5])
				|| !Arrays.equals(waterTex,actual[3])) throw new AssertionError("Water repair changed a dimension without skylight");
			// The previous release saved unready, all-zero light as valid. Migrate its lighting, preserving every terrain,
			// material, overhang and plant word (including genuine dark samples, which must not be guessed from brightness).
			Path file = dir.resolve("L0/-12.5.lod");
			byte[] previous;
			try (var in = new java.util.zip.InflaterInputStream(Files.newInputStream(file))) { previous = in.readAllBytes(); }
			java.nio.ByteBuffer.wrap(previous).putInt(11);
			try (var out = new java.util.zip.DeflaterOutputStream(Files.newOutputStream(file))) { out.write(previous); }
			for (int[] plane : actual) Arrays.fill(plane,0);
			if (!(boolean) load.invoke(field,key,actual[0],actual[1],actual[2],actual[3],actual[4],actual[5])) throw new AssertionError("Previous cache did not load");
			for (int i=0;i<expected.length;i++) {
				int[] migrated = expected[i].clone();
				if (i == 3) for (int j=0;j<n;j++) migrated[j] &= ~LodClip.TEX_LIGHT;
				if (!Arrays.equals(migrated,actual[i])) throw new AssertionError("Previous cache migration damaged plane " + i);
			}
			// Legacy tiles remain readable, avoiding a full terrain-cache rebuild just to add optional surface data.
			try (var out = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(Files.newOutputStream(file)))) {
				out.writeInt(10);
				for (int v : g) out.writeInt(v);
				for (int v : c) out.writeInt(v);
				out.writeBoolean(true);
				for (int v : cr) out.writeInt(v);
				for (int v : runs) out.writeInt(v);
				out.writeBoolean(true);
				out.writeInt(1); out.writeUTF("");
				for (int i=0;i<n;i++) out.writeInt(0);
				out.writeBoolean(true);
				out.writeInt(1); out.writeUTF("");
				for (int i=0;i<n;i++) { out.writeInt(plants[i]); out.writeInt(plants[n+i]); }
			}
			Arrays.fill(afterPlants,0);
			if (!(boolean) load.invoke(field,key,new int[n],new int[n],new int[n],new int[2*n],new int[n],afterPlants)
				|| !Arrays.equals(plants,afterPlants)) throw new AssertionError("Legacy cache read lost alignment");
			// Coarser caches have no material metadata: recover repaired real surfaces from fine samples, including builds.
			for (int i=0;i<n;i++) {
				g[i]=LodClip.geomWord(64,false); c[i]=0x12345678; cr[i]=LodClip.belowWord(0x705030);
				tw[i]=LodClip.TEX_LIGHT|LodClip.TEX_OVERHANG; tw[n+i]=0xF0;
			}
			save.invoke(field,key,g,c,cr,tw,runs,plants);
			int[] coarseG=new int[n], coarseC=new int[n], coarseCr=new int[n], coarseRuns=new int[n];
			Arrays.fill(coarseG,LodClip.crownGeomWord(64,27,false)); Arrays.fill(coarseC,0x8B585368);
			Arrays.fill(coarseCr,LodClip.crownWord(4,0xAA22CC));
			coarseG[32*64+1]=LodClip.geomWord(65,false); // A mismatching surface must not be overwritten.
			Method upgrade=LodField.class.getDeclaredMethod("upgradeCoarseSurfaces",long.class,int[].class,int[].class,int[].class,int[].class);
			upgrade.setAccessible(true); upgrade.invoke(field,LodTile.key(1,-6,2),coarseG,coarseC,coarseCr,coarseRuns);
			for (int z=0;z<64;z++) for (int x=0;x<64;x++) {
				int i=z*64+x; boolean replaced=x<32&&z>=32&&i!=32*64+1;
				if (coarseC[i]!=(replaced?0x12345678:0x8B585368) || (replaced&&(coarseG[i]&LodClip.GEOM_CROWN)!=0))
					throw new AssertionError("Coarse cache retained the underground geode/cave, or damaged a different surface");
			}
			System.out.println("PASS: cache codec, partial light repair, legacy tiles and coarse recovery from corrected fine surfaces preserve geometry/materials");
		} finally {
			try (var files = Files.walk(dir)) {
				for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
			}
		}
	}
}
