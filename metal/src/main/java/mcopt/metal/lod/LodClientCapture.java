package mcopt.metal.lod;

import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Render-thread copies only. No live chunk, level or light-engine reference reaches the capture worker. */
final class LodClientCapture {
	private LodClientCapture() { }

	record Frozen(ProtoChunk chunk, LodChunks.LightAccess lighting, int cavernY, boolean islands) {
		LodChunks.Snapshot snapshot() { return LodChunks.snapshot(this); }
	}

	static Frozen freeze(LevelChunk chunk, int cavernY) {
		var level = chunk.getLevel();
		var engine = level.getLightEngine();
		ChunkAccess[] sides = new ChunkAccess[4];
		int x = chunk.getPos().x(), z = chunk.getPos().z();
		for (int d = 0; d < 4; d++) sides[d] = level.getChunkSource().getChunk(x + (d == 0 ? 1 : d == 1 ? -1 : 0),
			z + (d == 2 ? 1 : d == 3 ? -1 : 0), false);
		return freeze(chunk, sides, level.palettedContainerFactory(), engine.getMinLightSection(), engine.getLightSectionCount(),
			engine.lightOnInColumn(SectionPos.getZeroNode(x, z)), level.dimensionType().hasSkyLight(),
			(layer, section) -> engine.getLayerListener(layer).getDataLayerData(section), cavernY,
			level.dimension() == net.minecraft.world.level.Level.END);
	}

	/** Shared by the detached fixtures; providers are invoked only while copying, never retained. */
	static Frozen freeze(ChunkAccess source, ChunkAccess[] sides, PalettedContainerFactory factory, int lightFirst, int lightCount,
		boolean enabled, boolean skyLight, BiFunction<LightLayer, SectionPos, DataLayer> layers, int cavernY, boolean islands) {
		LevelChunkSection[] sections = new LevelChunkSection[source.getSectionsCount()];
		for (int i = 0; i < sections.length; i++) sections[i] = source.getSection(i).copy();
		var height = LevelHeightAccessor.create(source.getMinY(), source.getHeight());
		var detached = new ProtoChunk(source.getPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height, factory, null);
		detached.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
		// Do not retain the mutable packed heightmap array or a live height accessor.
		detached.setHeightmap(Heightmap.Types.WORLD_SURFACE, source.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getRawData().clone());
		int x = source.getPos().x(), z = source.getPos().z();
		Column[] columns = new Column[5];
		columns[0] = copy(null, x, z, lightFirst, lightCount, skyLight, layers, -1);
		for (int d = 0; d < 4; d++) columns[d + 1] = copy(sides[d], x + (d == 0 ? 1 : d == 1 ? -1 : 0),
			z + (d == 2 ? 1 : d == 3 ? -1 : 0), lightFirst, lightCount, skyLight, layers, d);
		var lighting = new LodChunks.LightAccess(enabled, skyLight,
			p -> { var c = owner(columns, p); return c == null ? 0 : c.sky(p); },
			p -> { var c = owner(columns, p); return c == null ? 0 : c.block(p); },
			p -> Blocks.AIR.defaultBlockState()); // Neighbor emission is folded into the boundary block-light nibble.
		return new Frozen(detached, lighting, cavernY, islands);
	}

	private static Column owner(Column[] columns, BlockPos pos) {
		int x = pos.getX() >> 4, z = pos.getZ() >> 4;
		for (var c : columns) if (c.x == x && c.z == z) return c;
		return null;
	}

	private static Column copy(ChunkAccess source, int x, int z, int first, int count, boolean skyLight,
		BiFunction<LightLayer, SectionPos, DataLayer> layers, int edge) {
		DataLayer[] sky = new DataLayer[count], block = new DataLayer[count];
		for (int i = 0; i < count; i++) {
			SectionPos pos = SectionPos.of(x, first + i, z);
			DataLayer s = skyLight ? layers.apply(LightLayer.SKY, pos) : null;
			DataLayer b = LodConfig.LIGHTING ? layers.apply(LightLayer.BLOCK, pos) : null;
			sky[i] = s == null || edge >= 0 ? s : s.copy();
			block[i] = b == null || edge >= 0 ? b : b.copy();
		}
		if (edge < 0) return new Column(x, z, first, sky, block, skyLight, edge, null, null);
		// Side-light probes cross one edge. Store its light pairs, not two full volumes and the neighbor's block palette.
		byte[][] light = new byte[count][];
		int[] uniform = new int[count];
		for (int i = 0; i < count; i++) {
			int sy = first + i, index = source == null ? -1 : source.getSectionIndexFromSectionY(sy);
			LevelChunkSection section = source == null || index < 0 || index >= source.getSectionsCount() ? null : source.getSection(index);
			boolean emission = section != null && !section.hasOnlyAir() && section.maybeHas(s -> s.getLightEmission() > 0);
			DataLayer sk = sky[i], bl = block[i];
			boolean flattened = sk == null;
			if (sk == null) for (int k = i + 1; k < count; k++) if (sky[k] != null) { sk = sky[k]; break; }
			if ((sk == null || sk.isDefinitelyHomogenous()) && (bl == null || bl.isDefinitelyHomogenous()) && !emission) {
				uniform[i] = (skyLight ? sk == null ? 15 : sk.get(0,0,0) : 0) << 4 | (bl == null ? 0 : bl.get(0,0,0));
				continue;
			}
			byte[] values = light[i] = new byte[256];
			for (int y0 = 0; y0 < 16; y0++) for (int along = 0; along < 16; along++) {
				int lx = edge < 2 ? edge == 0 ? 0 : 15 : along;
				int lz = edge >= 2 ? edge == 2 ? 0 : 15 : along;
				int b = bl == null ? 0 : bl.get(lx,y0,lz);
				if (LodConfig.LIGHTING && emission) b = Math.max(b,section.getBlockState(lx,y0,lz).getLightEmission());
				int s = !skyLight ? 0 : sk == null ? 15 : sk.get(lx,flattened ? 0 : y0,lz);
				values[y0 * 16 + along] = (byte)(s << 4 | b);
			}
		}
		return new Column(x, z, first, null, null, skyLight, edge, light, uniform);
	}

	private record Column(int x, int z, int first, DataLayer[] sky, DataLayer[] block, boolean skyLight,
		int edge, byte[][] light, int[] uniform) {
		int edgeLight(BlockPos p) {
			int i = (p.getY() >> 4) - this.first;
			if (i >= this.light.length) return this.skyLight ? 240 : 0;
			if (i < 0) return !this.skyLight ? 0 : this.light[0] == null ? this.uniform[0] & 240
				: this.light[0][(this.edge < 2 ? p.getZ() : p.getX()) & 15] & 240;
			return this.light[i] == null ? this.uniform[i] : this.light[i][(p.getY() & 15) * 16 + ((this.edge < 2 ? p.getZ() : p.getX()) & 15)] & 255;
		}
		int sky(BlockPos p) {
			if (this.edge >= 0) return this.edgeLight(p) >> 4;
			if (!this.skyLight) return 0;
			int i = (p.getY() >> 4) - this.first;
			if (i >= this.sky.length) return 15;
			if (i >= 0 && this.sky[i] != null) return this.sky[i].get(p.getX() & 15, p.getY() & 15, p.getZ() & 15);
			// Vanilla's visible skylight lookup fills an absent section from the bottom row of the next section above.
			for (i = Math.max(0, i + 1); i < this.sky.length; i++)
				if (this.sky[i] != null) return this.sky[i].get(p.getX() & 15, 0, p.getZ() & 15);
			return 15;
		}
		int block(BlockPos p) {
			if (this.edge >= 0) return this.edgeLight(p) & 15;
			int i = (p.getY() >> 4) - this.first;
			return i < 0 || i >= this.block.length || this.block[i] == null ? 0 : this.block[i].get(p.getX() & 15, p.getY() & 15, p.getZ() & 15);
		}
	}
}
