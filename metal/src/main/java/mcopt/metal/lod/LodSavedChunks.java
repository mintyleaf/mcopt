package mcopt.metal.lod;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Detached, read-only saved sections for repairing invalid old LOD samples. Never requests or generates a world chunk. */
final class LodSavedChunks {
	private LodSavedChunks() { }

	record Saved(ProtoChunk chunk, LodChunks.LightAccess lighting) { }

	static Saved read(ServerLevel level, int cx, int cz) throws IOException {
		CollectToTag visitor = new CollectToTag();
		try {
			level.getChunkSource().chunkScanner().scanChunk(new ChunkPos(cx, cz), visitor).get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Saved LOD repair interrupted", e);
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IOException("Saved LOD repair could not read chunk " + cx + "," + cz, e);
		}
		if (!(visitor.getResult() instanceof CompoundTag tag)) return null;
		return decode(level, tag, cx, cz);
	}

	static Saved decode(ServerLevel level, CompoundTag tag, int cx, int cz) {
		String status = tag.getStringOr("Status", "");
		if (!status.isEmpty() && !status.equals("minecraft:full") && !status.equals("full")) return null;
		// The scanner returns raw disk NBT. Apply the same version/context upgrade as vanilla ChunkMap before decoding.
		var source = level.getChunkSource();
		var context = net.minecraft.server.level.ChunkMap.getChunkDataFixContextTag(level.dimension(), source.getGenerator().getTypeNameForDataFixer());
		tag = source.chunkMap.upgradeChunkTag(tag, -1, context, net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
		SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
		if (data == null || !data.chunkPos().equals(new ChunkPos(cx, cz)) || data.chunkStatus() != ChunkStatus.FULL
			|| !data.heightmaps().containsKey(Heightmap.Types.WORLD_SURFACE)) return null;
		return detached(level, data, level.dimensionType().hasSkyLight());
	}

	static Saved detached(LevelHeightAccessor height, SerializableChunkData data, boolean skyLight) {
		LevelChunkSection[] sections = new LevelChunkSection[height.getSectionsCount()];
		var sky = new HashMap<Integer, DataLayer>();
		var block = new HashMap<Integer, DataLayer>();
		for (var s : data.sectionData()) {
			int index = height.getSectionIndexFromSectionY(s.y());
			if (index >= 0 && index < sections.length) sections[index] = s.chunkSection();
			if (s.skyLight() != null) sky.put(s.y(), s.skyLight());
			if (s.blockLight() != null) block.put(s.y(), s.blockLight());
		}
		ProtoChunk chunk = new ProtoChunk(data.chunkPos(), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
			height, data.containerFactory(), null);
		chunk.setPersistedStatus(ChunkStatus.FULL);
		data.heightmaps().forEach(chunk::setHeightmap);
		// Vanilla omits empty sky sections above the highest terrain. Interior missing sections remain unknown.
		int skyAbove = chunk.getSectionYFromSectionIndex(chunk.getHighestFilledSectionIndex() + 1) << 4;
		var lighting = new LodChunks.LightAccess(data.lightCorrect(), skyLight,
			p -> data.lightCorrect() && skyLight && p.getY() >= skyAbove && !sky.containsKey(p.getY() >> 4) ? 15 : nibble(sky, data.chunkPos(), p),
			p -> nibble(block, data.chunkPos(), p), p -> Blocks.AIR.defaultBlockState());
		return new Saved(chunk, lighting);
	}

	/** Server thread only. Boundary probes must never call ServerLevel.getBlockState and synchronously load neighbors. */
	static LodChunks.Snapshot liveSnapshot(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
		return liveSnapshot(level, chunk, level.dimensionType().hasCeiling() ? 64 : LodCaverns.NONE);
	}

	static LodChunks.Snapshot liveSnapshot(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk, int cavernY) {
		var lighting = new LodChunks.LightAccess(chunk.isLightCorrect(), level.dimensionType().hasSkyLight(),
			p -> level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p),
			p -> level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p), p -> {
				var neighbor = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
				return neighbor == null ? Blocks.AIR.defaultBlockState() : neighbor.getBlockState(p);
			});
		return LodChunks.snapshot(chunk, lighting, cavernY, level.dimension() == net.minecraft.world.level.Level.END);
	}

	/** Worker-owned bounded neighborhood for side light and boundary block probes; all reads are detached. */
	static final class Window {
		private final ServerLevel level;
		private final int cavernY;
		private final java.util.LinkedHashMap<Long, Saved> chunks = new java.util.LinkedHashMap<>(32, 0.75F, true);
		Window(ServerLevel level) { this(level, level.dimensionType().hasCeiling() ? 64 : LodCaverns.NONE); }
		Window(ServerLevel level, int cavernY) { this.level = level; this.cavernY = cavernY; }
		Saved get(int x, int z) throws IOException {
			long key = LodForest.chunkKey(x, z);
			if (this.chunks.containsKey(key)) return this.chunks.get(key);
			Saved saved = read(this.level, x, z);
			this.chunks.put(key, saved);
			if (this.chunks.size() > 16) this.chunks.remove(this.chunks.keySet().iterator().next());
			return saved;
		}
		LodChunks.Snapshot snapshot(int x, int z) throws IOException {
			// A chunk cached earlier as a neighbor predates the caller's capture stamp. Re-read the authoritative center.
			this.chunks.remove(LodForest.chunkKey(x, z));
			Saved center = this.get(x, z);
			if (center == null || !center.lighting.enabled()) return null;
			var neighbors = new HashMap<Long, Saved>();
			neighbors.put(LodForest.chunkKey(x, z), center);
			for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
				Saved side = this.get(x + d[0], z + d[1]);
				if (side != null) neighbors.put(LodForest.chunkKey(x + d[0], z + d[1]), side);
			}
			return LodSavedChunks.snapshot(center, neighbors, this.cavernY, this.level.dimension() == net.minecraft.world.level.Level.END);
		}
	}

	static LodChunks.Snapshot snapshot(Saved center, java.util.Map<Long, Saved> neighbors) {
		return snapshot(center, neighbors, LodCaverns.NONE);
	}

	static LodChunks.Snapshot snapshot(Saved center, java.util.Map<Long, Saved> neighbors, int cavernY) {
		return snapshot(center, neighbors, cavernY, false);
	}

	static LodChunks.Snapshot snapshot(Saved center, java.util.Map<Long, Saved> neighbors, int cavernY, boolean islands) {
		java.util.function.Function<BlockPos, Saved> owner = p -> neighbors.get(LodForest.chunkKey(p.getX() >> 4, p.getZ() >> 4));
		var lighting = new LodChunks.LightAccess(center.lighting.enabled(), center.lighting.skyLight(),
			p -> { Saved s = owner.apply(p); return s == null || !s.lighting.enabled() ? 0 : s.lighting.sky().applyAsInt(p); },
			p -> { Saved s = owner.apply(p); return s == null || !s.lighting.enabled() ? 0 : s.lighting.block().applyAsInt(p); },
			p -> { Saved s = owner.apply(p); return s == null ? Blocks.AIR.defaultBlockState() : s.chunk.getBlockState(p); });
		return LodChunks.snapshot(center.chunk, lighting, cavernY, islands);
	}

	private static int nibble(java.util.Map<Integer, DataLayer> sections, ChunkPos chunk, BlockPos pos) {
		if ((pos.getX() >> 4) != chunk.x() || (pos.getZ() >> 4) != chunk.z()) return 0;
		DataLayer data = sections.get(pos.getY() >> 4);
		return data == null ? 0 : data.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}
}
