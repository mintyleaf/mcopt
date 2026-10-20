package mcopt.metal.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The game's real chunks as far terrain: when the client loads a chunk (and when it unloads it, with the player's changes),
 * its 16 x 16 columns become far-terrain columns with the real surface, trees, water and builds, replacing the generated
 * estimate in every tile that covers them. The render thread copies bounded block-state, biome and lighting samples;
 * interval encoding and colors are worked out on a worker and the tiles patched back on the render thread.
 */
final class LodChunks {
	/**
	 * What the render thread copies out of a chunk, per column: the ground (its top block or the floor under water, what is
	 * under it, its top face, the water's), and a tree crown over air if one floats there (its top block, lowest and top y).
	 * For the solid runs (-Dmcopt.lod.realOcc only): the y each column's run starts at (its ground's top block; Short.MIN_VALUE:
	 * none, under water), and copies of the chunk's sections it can reach (sections[i]: section secFirst + i; null: only air).
	 */
	record Snapshot(int chunkX, int chunkZ, BlockState[] top, BlockState[] under, short[] height, short[] water, Holder<Biome>[] biome,
		BlockState[] crown, BlockState[] crownLeaf, short[] crownLo, short[] crownHi, int[] crownRuns, BlockState[] trunk, BlockState[] above,
		boolean[] overhang, int[] light, boolean[] lightValid,
		short[] runFrom, PalettedContainer<BlockState>[] sections, int secFirst, int minY, boolean islands) {
	}

	/**
	 * A chunk's columns as clipmap columns: the ground (top face, water, colors) and, where a crown floats over air, its lowest
	 * block y (crownLo > the ground's surface), its top face y and colors (top / side then hold the crown's, groundColor the
	 * ground's top). Without a crown crownLo is Short.MIN_VALUE and crownHi the column's top.
	 */
	/**
	 * clear: the column's top doesn't hide what's behind it (water, glass, a fence, a slab...): it occludes nothing. depth
	 * (-Dmcopt.lod.realOcc only, else 0): how far the ground's top solid run reaches down, in blocks (1-127, 127: that deep or
	 * more; 0: none): under it may be air, a cave or the space under an overhang.
	 */
	record Summary(int chunkX, int chunkZ, short[] height, short[] water, int[] top, int[] side, int[] below, boolean[] fringe, short[] crownLo,
		short[] crownHi, int[] groundColor, int[] tex, int[] runs, int[] plantA, int[] plantB, boolean[] clear, byte[] depth, int[] light, short[] islandBottom, boolean[] segmented) {
	}

	private LodChunks() {
	}

	/** A plant, torch or flower over the ground: seen from far away the ground shows. Snow layers count as ground. */
	private static boolean decoration(BlockState s) {
		// (powder snow has no collision shape for a query without an entity, but it is a block of snow to the eye)
		return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.SNOW) && !s.is(Blocks.POWDER_SNOW)
			&& s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	static boolean hangingDecoration(BlockState state) {
		return state.is(Blocks.VINE);
	}

	static boolean generatedHangingDecoration(int geom, int material, BlockState state) {
		return (geom & (LodClip.GEOM_VALID | LodClip.GEOM_CLEAR)) == LodClip.GEOM_VALID
			&& (material & (LodClip.TEX_LIGHT | LodClip.TEX_OVERHANG)) == 0 && hangingDecoration(state);
	}

	static int surfaceY(int start, int minY, java.util.function.IntPredicate decoration) {
		int y = start;
		for (int guard = 0; guard < 128 && y > minY && decoration.test(y); guard++) y--;
		return y;
	}

	private static boolean crownBlock(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(Blocks.SNOW);
	}

	private static boolean mushroomCap(BlockState s) {
		return s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK);
	}
	static boolean chorus(BlockState s) {return s.is(Blocks.CHORUS_PLANT) || s.is(Blocks.CHORUS_FLOWER);}

	/** The deepest solid run counted (Snapshot.depth). */
	static final int DEPTH_MAX = 127;
	/** -Dmcopt.lod.realOccRuns=false: no solid runs (every real column solid all the way down), for the cave fixtures' A/B. */
	private static final boolean RUNS = LodMesh.REAL_OCC && !"false".equals(System.getProperty("mcopt.lod.realOccRuns"));

	/**
	 * How many blocks down from y (the ground's top block, x / z in the chunk) are solid (hide what's behind them), up to
	 * DEPTH_MAX, in the snapshot's section copies. A section that holds only such blocks counts whole (solid: per section, 0
	 * not looked at yet, 1 solid, 2 not). Runs on a worker.
	 */
	private static int solidRun(Snapshot s, byte[] solid, int x, int y, int z) {
		int d = 0;
		while (d < DEPTH_MAX) {
			if (y < s.minY) return s.islands ? d : DEPTH_MAX;
			int si = ((y - s.minY) >> 4) - s.secFirst;
			if (si < 0 || si >= s.sections.length) return d;
			PalettedContainer<BlockState> sec = s.sections[si];
			if (sec == null) return d;
			if (solid[si] == 0) solid[si] = (byte) (!sec.maybeHas(st -> !(s.islands ? geometrySolid(st) : st.canOcclude())) ? 1 : 2);
			if (solid[si] == 1) {
				int n = ((y - s.minY) & 15) + 1;
				d += n;
				y -= n;
				continue;
			}
			BlockState state=sec.get(x, (y - s.minY) & 15, z);
			if (!(s.islands ? geometrySolid(state) : state.canOcclude())) return d;
			d++;
			y--;
		}
		return DEPTH_MAX;
	}

	@SuppressWarnings("unchecked")
	static Snapshot snapshot(LevelChunk chunk) {
		return snapshot(chunk, LodCaverns.NONE);
	}

	static Snapshot snapshot(LevelChunk chunk, int cavernY) {
		var level = chunk.getLevel();
		boolean enabled = level.getLightEngine().lightOnInColumn(net.minecraft.core.SectionPos.getZeroNode(chunk.getPos().x(), chunk.getPos().z()));
		return snapshot(chunk, new LightAccess(enabled, level.dimensionType().hasSkyLight(),
			p -> level.getBrightness(LightLayer.SKY, p), p -> level.getBrightness(LightLayer.BLOCK, p), level::getBlockState), cavernY,
			level.dimension() == net.minecraft.world.level.Level.END);
	}

	/** Also used with detached saved sections: no chunk tickets, world generation or live light-engine writes. */
	record LightAccess(boolean enabled, boolean skyLight, java.util.function.ToIntFunction<BlockPos> sky,
		java.util.function.ToIntFunction<BlockPos> block, java.util.function.Function<BlockPos, BlockState> outside) { }

	@SuppressWarnings("unchecked")
	static Snapshot snapshot(ChunkAccess chunk, LightAccess lighting) {
		return snapshot(chunk, lighting, LodCaverns.NONE);
	}

	@SuppressWarnings("unchecked")
	static Snapshot snapshot(ChunkAccess chunk, LightAccess lighting, int cavernY) {
		return snapshot(chunk, lighting, cavernY, false);
	}

	@SuppressWarnings("unchecked")
	static Snapshot snapshot(ChunkAccess chunk, LightAccess lighting, int cavernY, boolean islands) {
		return snapshot(chunk, lighting, cavernY, islands, null);
	}

	/** Frozen owns detached, read-only sections; their palette arrays can also serve the subsequent interval scan. */
	static Snapshot snapshot(LodClientCapture.Frozen frozen) {
		return snapshot(frozen.chunk(), frozen.lighting(), frozen.cavernY(), frozen.islands(), new FrozenBlocks(frozen));
	}

	/** Only the typed Frozen path bypasses ChunkAccess: live/modded chunk getters keep their own semantics. */
	static final class FrozenBlocks {
		final int minY, maxY, minSectionY;
		private final PalettedContainer<BlockState>[] sections;

		@SuppressWarnings("unchecked")
		FrozenBlocks(LodClientCapture.Frozen frozen) {
			var chunk = frozen.chunk();
			minY = chunk.getMinY(); maxY = chunk.getMaxY(); minSectionY = chunk.getMinSectionY();
			var source = chunk.getSections();
			sections = new PalettedContainer[source.length];
			for (int i = 0; i < source.length; i++)
				if (!source[i].hasOnlyAir()) sections[i] = source[i].getStates();
		}

		BlockState get(BlockPos pos) {
			int y = pos.getY();
			if (y < minY || y > maxY) return Blocks.VOID_AIR.defaultBlockState();
			var section = sections[(y >> 4) - minSectionY];
			return section == null ? Blocks.AIR.defaultBlockState() : section.get(pos.getX() & 15, y & 15, pos.getZ() & 15);
		}
	}

	private static BlockState state(ChunkAccess chunk, FrozenBlocks blocks, BlockPos pos) {
		return blocks == null ? chunk.getBlockState(pos) : blocks.get(pos);
	}

	@SuppressWarnings("unchecked")
	private static Snapshot snapshot(ChunkAccess chunk, LightAccess lighting, int cavernY, boolean islands, FrozenBlocks blocks) {
		int minY = blocks == null ? chunk.getMinY() : blocks.minY;
		BlockState[] top = new BlockState[256], under = new BlockState[256], crown = new BlockState[256], crownLeaf = new BlockState[256];
		short[] height = new short[256], water = new short[256], crownLo = new short[256], crownHi = new short[256];
		int[] crownRuns = new int[256], light = new int[256];
		boolean[] overhang = new boolean[256], lightValid = new boolean[256];
		BlockState[] trunk = new BlockState[256];
		// up to 3 blocks over the ground where a decoration stands on it (a plant, for the worker to tell)
		BlockState[] above = new BlockState[256 * 3];
		Holder<Biome>[] biome = new Holder[256];
		short[] runFrom = RUNS || islands || LodConfig.STRUCTURE_DETAIL ? new short[256] : null;
		int runLo = Integer.MAX_VALUE, runHi = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bx0 = chunk.getPos().getMinBlockX(), bz0 = chunk.getPos().getMinBlockZ();
		boolean lightEnabled = LodConfig.LIGHTING && lighting.enabled();
		boolean skyLight = lighting.skyLight();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int px = bx0 + x, pz = bz0 + z;
				int worldTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				LodCaverns.Layer cavity = cavernY == LodCaverns.NONE ? null : LodCaverns.find(minY, worldTop, cavernY, cy -> {
					BlockState state = state(chunk, blocks, pos.set(px, cy, pz));
					return state.isAir() || decoration(state);
				});
				int y = cavity != null ? cavity.floor() : surfaceY(worldTop, minY, cy -> {
					BlockState state = state(chunk, blocks, pos.set(px, cy, pz));
					return state.isAir() || decoration(state);
				});
				BlockState s = state(chunk, blocks, pos.set(px, y, pz));
				crownLo[i] = Short.MIN_VALUE;
				crown[i] = null;
				if(cavity==null && chorus(s)) {
					// A rooted stalk and its overhanging branches are vegetation above the island, not the island's rock surface.
					int topY=y,lowest=y,k=y;long occupied=0;BlockState topState=s;
					BlockState b=s;
					for(int guard=0;guard<128 && k>minY;guard++) {
						if(chorus(b)) {lowest=k;if(topY-k<64) occupied|=1L<<(topY-k);}
						else if(!b.isAir() && !decoration(b)) break;
						b=state(chunk, blocks, pos.set(px,--k,pz));
					}
					if(!b.isAir() && !chorus(b)) {
						crown[i]=topState;crownLeaf[i]=Blocks.CHORUS_PLANT.defaultBlockState();
						crownLo[i]=(short)lowest;crownHi[i]=(short)(topY+1);overhang[i]=true;
						long up=Long.reverse(occupied) >>> Math.max(0,63-(topY-lowest));
						crownRuns[i]=LodTile.runs(up);y=k;s=b;
					}
				}
				if (cavity == null && crownBlock(s)) {
					// a crown: down through leaves (and the air between a spruce's layers) to whatever holds it up. Air right over the
					// ground means it floats; a log or the ground right under the leaves means the tree stands there.
					int topY = y;
					BlockState topState = s;
					int k = y - 1, lowest = y;
					BlockState b = state(chunk, blocks, pos.set(px, k, pz));
					boolean air = false,hasGap=false;
					// leaf blocks down from the top (bit d: topY - d), for the crown's runs (a spruce's tiers)
					long below = topState.is(BlockTags.LEAVES) ? 1L : 0L;
					for (int guard = 0; guard < 128 && k > minY; guard++) {
						if (b.is(BlockTags.LEAVES)) {
							lowest = k;
							air = false;
							if (topY - k < 64) below |= 1L << (topY - k);
						} else if(LodConfig.STRUCTURE_DETAIL && b.is(BlockTags.LOGS)) {
							// A branch or trunk is another occupied interval, not an infinite terrain surface.
							if(trunk[i]==null) trunk[i]=b;
							lowest=k;
							if(topY-k<64) below|=1L<<(topY-k);
						} else if (b.isAir() || decoration(b) || b.is(Blocks.SNOW)) {
							// air between tiers (snow lies on each tier of a spruce in the cold)
							air = true;
							if(b.isAir() || decoration(b)) hasGap=true;
						} else {
							break;
						}
						b = state(chunk, blocks, pos.set(px, --k, pz));
					}
					if (b.is(BlockTags.LOGS)) trunk[i] = b;
					if (LodConfig.STRUCTURE_DETAIL && hasGap || air && !b.is(BlockTags.LOGS)) {
						crown[i] = topState;
						crownLeaf[i] = topState.is(Blocks.SNOW) ? state(chunk, blocks, pos.set(px, topY - 1, pz)) : topState;
						crownLo[i] = (short) lowest;
						// as bits up from the lowest leaf: the leaf at topY - d is lowest + (topY - lowest - d)
						long up = 0;
						for (int d = 0; d < 64; d++) if ((below >>> d & 1) != 0 && topY - lowest - d >= 0 && topY - lowest - d < 64) up |= 1L << (topY - lowest - d);
						crownRuns[i] = LodTile.runs(up);
						// a snow layer on the crown is a covering: the crown's top face is its top leaf's
						crownHi[i] = (short) (topState.is(Blocks.SNOW) ? topY : topY + 1);
						// A bounded canopy scan may still end in air below a tall tree. Air is never a lower solid surface.
						y = surfaceY(k, minY, cy -> {
							BlockState state = state(chunk, blocks, pos.set(px, cy, pz));
							return state.isAir() || decoration(state);
						});
						s = state(chunk, blocks, pos.set(px, y, pz));
					}
				}
				boolean snowOnLeaves = s.is(Blocks.SNOW) && state(chunk, blocks, pos.set(px, y - 1, pz)).is(BlockTags.LEAVES);
				if (cavity == null && LodConfig.OVERHANGS && crown[i] == null && mushroomCap(s)) {
					var cap = LodOverhang.mushroom(y, minY, cy -> {
						BlockState b = state(chunk, blocks, pos.set(px, cy, pz));
						return mushroomCap(b) ? 1 : b.isAir() || decoration(b) || b.is(BlockTags.LEAVES) ? 0 : 2;
					});
					if (cap != null) {
						crown[i] = s;
						crownLeaf[i] = state(chunk, blocks, pos.set(px, cap.bottom(), pz));
						crownLo[i] = (short) cap.bottom();
						crownHi[i] = (short) (y + 1);
						crownRuns[i] = LodTile.runs(cap.occupied());
						overhang[i] = true;
						y = cap.ground();
						s = state(chunk, blocks, pos.set(px, y, pz));
					}
				}
				if (cavity == null && LodConfig.OVERHANGS && crown[i] == null && !s.is(BlockTags.LEAVES) && !snowOnLeaves && s.getFluidState().isEmpty() && !s.isAir()) {
					LodOverhang.Layer floating = LodOverhang.find(y, minY, cy -> {
						BlockState b = state(chunk, blocks, pos.set(px, cy, pz));
						return !b.isAir() && (b.canOcclude() || !decoration(b));
					});
					if (floating != null && (!skyLight || LodOverhang.exposedGap(
						lighting.sky().applyAsInt(pos.set(px, floating.bottom() - 1, pz)),
						lighting.sky().applyAsInt(pos.set(px, floating.ground() + 1, pz))))) {
						BlockState floor = state(chunk, blocks, pos.set(px, floating.ground(), pz));
						if (floor.getFluidState().isEmpty() || floor.getFluidState().is(FluidTags.WATER)) {
							crown[i] = s;
							// The exposed wall belongs to the surface block, not an underground block at the roof's foot.
							crownLeaf[i] = s;
							crownLo[i] = (short) floating.bottom();
							crownHi[i] = (short) (s.is(Blocks.SNOW) ? y : y + 1);
							overhang[i] = true;
							y = floating.ground();
							s = floor;
						}
					}
				}
				if (cavity != null && cavity.ceiling() <= worldTop) {
					crownLo[i] = (short)cavity.ceiling();
					crownHi[i] = (short)Math.min(worldTop + 1, cavity.ceiling() + 16);
					crown[i] = crownLeaf[i] = state(chunk, blocks, pos.set(px, cavity.ceiling(), pz));
					overhang[i] = true;
				}
				if(islands && crown[i]!=null && LodConfig.STRUCTURE_DETAIL) {
					// The first short floor below a roof is not the island foundation. Retain the lowest body and
					// let the worker encode every remaining floor/wall above it, including partially solid stairs.
					int k=Math.max(minY,crownHi[i]-LodStructureLayers.RANGE);
					while(k<crownHi[i] && !geometrySolid(state(chunk, blocks, pos.set(px,k,pz)))) k++;
					int bottom=k;
					while(k<crownHi[i]) {
						BlockState b=state(chunk, blocks, pos.set(px,k,pz));
						if(!geometrySolid(b) || chorus(b)) break;
						k++;
					}
					if(k>bottom && k<crownHi[i]) {y=k-1;s=state(chunk, blocks, pos.set(px,y,pz));}
				}
				if(LodConfig.STRUCTURE_DETAIL && !islands && cavity==null && overhang[i] && !s.isAir()) {
					int limit=Math.max(minY,crownHi[i]-LodStructureLayers.RANGE);
					int floor=LodStructureLayers.ground(y,limit,cy -> {
						BlockState b=state(chunk, blocks, pos.set(px,cy,pz));return !b.isAir() && !decoration(b);
					});
					floor=LodStructureLayers.exteriorGround(floor,limit,cy -> {
						BlockState b=state(chunk, blocks, pos.set(px,cy,pz));return !b.isAir() && !decoration(b);
					},cy -> {
						if(lighting.sky().applyAsInt(pos.set(px,cy,pz))>0) return true;
						BlockState below=state(chunk, blocks, pos.set(px,cy-1,pz));
						return below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.MYCELIUM) || below.is(Blocks.PODZOL);
					});
					if(floor<y) {y=floor;s=state(chunk, blocks, pos.set(px,y,pz));}
				}
				short wet = LodTile.DRY;
				if (s.getFluidState().is(FluidTags.WATER)) {
					wet = (short) (y + 1);
					for (int guard = 0; guard < 96 && y > minY && (s.getFluidState().is(FluidTags.WATER) || s.isAir()); guard++) {
						s = state(chunk, blocks, pos.set(px, --y, pz));
					}
				}
				top[i] = s;
				// a snow layer is a covering (an eighth of a block), not a block: the column's top stays the block under it
				height[i] = (short) (s.is(Blocks.SNOW) ? y : y + 1);
				if (islands && s.isAir() && wet == LodTile.DRY) height[i] = LodIslands.EMPTY;
				water[i] = wet;
				under[i] = state(chunk, blocks, pos.set(px, y - 1, pz));
				// (a snow layer: the run starts at the block under it)
				if (runFrom != null) {
					int from = s.is(Blocks.SNOW) ? y - 1 : y;
					runFrom[i] = wet == LodTile.DRY && height[i] != LodIslands.EMPTY ? (short) from : Short.MIN_VALUE;
					if (runFrom[i] != Short.MIN_VALUE) {
						runLo = Math.min(runLo, from);
						runHi = Math.max(runHi, from);
					}
				}
				if (crown[i] == null && wet == LodTile.DRY && !s.is(Blocks.SNOW)) {
					BlockState a = state(chunk, blocks, pos.set(px, y + 1, pz));
					for (int k = 0; k < 3 && decoration(a); k++) {
						above[i * 3 + k] = a;
						a = state(chunk, blocks, pos.set(px, y + 2 + k, pz));
					}
				}
				if (LodConfig.LIGHTING) {
					// onChunkLoaded precedes the light packet. A raw sky probe above every block must be 15 even at night;
					// zero here means that the light engine's visible section data is not ready, not a shadow on the terrain.
					int skyProbe = lighting.sky().applyAsInt(pos.set(px, worldTop + 1, pz));
					int upperTop = crown[i] != null ? crownHi[i] : wet != LodTile.DRY ? wet : height[i];
					int upperBottom = crown[i] != null ? crownLo[i] : upperTop - 1;
					BlockState upperState = crown[i] != null ? crown[i] : top[i];
					int topSampleY = upperTop + (upperState.is(Blocks.SNOW) ? 1 : 0);
					int topLight = lightAt(chunk, blocks, lighting, pos, px, topSampleY, pz);
					int sideLight = sideLight(chunk, blocks, lighting, pos, px, upperBottom, pz);
					if(lightEnabled && LodConfig.STRUCTURE_DETAIL && overhang[i] && cavity==null) {
						// Retain lights on intermediate balconies/floors too. Those surfaces share the compact side-light byte.
						int block=sideLight&15,base=Math.max(minY,Math.max(height[i]+1,upperTop-LodStructureLayers.RANGE));
						boolean previous=false;
						for(int cy=base;cy<upperTop;cy++) {
							BlockState here=state(chunk, blocks, pos.set(px,cy,pz));boolean solid=!here.isAir() && !decoration(here);
							if(solid && !previous) block=Math.max(block,sideLight(chunk, blocks,lighting,pos,px,cy,pz)&15);
							if(!solid && previous) block=Math.max(block,sideLight(chunk, blocks,lighting,pos,px,cy,pz)&15);
							previous=solid;
						}
						sideLight=(sideLight&240)|block;
					}
					int bottomLight = lightAt(chunk, blocks, lighting, pos, px, upperBottom - 1, pz);
					int groundLight = lightAt(chunk, blocks, lighting, pos, px, wet != LodTile.DRY ? wet : height[i] + (top[i].is(Blocks.SNOW) ? 1 : 0), pz);
					light[i] = topLight | sideLight << 8 | bottomLight << 16 | groundLight << 24;
					// Validate what was actually captured as well as the earlier probe: light data can change between reads.
					lightValid[i] = validLight(lightEnabled, skyLight, skyProbe, topLight, topSampleY > worldTop) && validStoredLight(light[i], skyLight);
				}
				if(LodConfig.STRUCTURE_DETAIL && crown[i]!=null) {
					runLo=Math.min(runLo,Math.max(minY,crownHi[i]-LodStructureLayers.RANGE));runHi=Math.max(runHi,crownHi[i]-1);
				}
				biome[i] = chunk.getNoiseBiome(QuartPos.fromBlock(px), QuartPos.fromBlock(wet != LodTile.DRY ? wet : y), QuartPos.fromBlock(pz));
			}
		}
		// Live sections need copies; frozen sections are already detached and remain read-only.
		PalettedContainer<BlockState>[] sections = null;
		int secFirst = 0;
		if (runFrom != null && runLo <= runHi) {
			secFirst = Math.max(0, (Math.max(minY, runLo - DEPTH_MAX) - minY) >> 4);
			int secLast = Math.min(chunk.getSectionsCount() - 1, (runHi - minY) >> 4);
			sections = new PalettedContainer[Math.max(0, secLast - secFirst + 1)];
			for (int k = 0; k < sections.length; k++) {
				LevelChunkSection sec = chunk.getSection(secFirst + k);
				sections[k] = sec.hasOnlyAir() ? null : blocks == null ? sec.getStates().copy() : sec.getStates();
			}
		}
		return new Snapshot(chunk.getPos().x(), chunk.getPos().z(), top, under, height, water, biome, crown, crownLeaf, crownLo, crownHi, crownRuns, trunk, above, overhang, light, lightValid,
			runFrom, sections, secFirst, minY, islands);
	}
	private static boolean geometrySolid(BlockState state) {return !state.isAir() && !decoration(state) && state.getFluidState().isEmpty();}

	static boolean validLight(boolean enabled, boolean skyLight, int skyAboveSurface) {
		return enabled && (!skyLight || skyAboveSurface == 15);
	}

	static boolean validLight(boolean enabled, boolean skyLight, int skyAboveSurface, int topLight, boolean exposedTop) {
		return validLight(enabled, skyLight, skyAboveSurface) && (!skyLight || !exposedTop || (topLight & 240) == 240);
	}

	/** Every captured column includes its highest surface. All four sky samples zero in a skylit dimension is unready
	 * data, not an underground shadow; a real roof can still have a lit top and completely dark sides/underside/floor. */
	static boolean validStoredLight(int light, boolean skyLight) {
		return !skyLight || (light & 0xF0F0F0F0) != 0;
	}

	/** The highest surface is open water: its raw sky is 15 (14 just inside it), independently of day/night brightness.
	 * Keep block light and lower samples. Water beneath a crown/overhang and dimensions without skylight keep real shadows. */
	static int repairWaterLight(int geom, int light, boolean skyLight) {
		if (!skyLight || (geom & (LodClip.GEOM_WET | LodClip.GEOM_CROWN)) != LodClip.GEOM_WET) return light;
		return (light & ~0xF0F0) | 0xF0 | Math.max(light & 0xF000, 0xE000);
	}

	/** The highest face opens to the sky. A partial light update can leave it zero even when another byte is ready.
	 * Lower faces keep their real shadows; entirely unready columns still use the normal fallback. */
	static int repairSurfaceLight(int geom, int light, boolean skyLight) {
		int repaired = repairWaterLight(geom, light, skyLight);
		return skyLight && validStoredLight(repaired, true) ? (repaired & ~0xF0) | 0xF0 : repaired;
	}

	private static int lightAt(ChunkAccess chunk, FrozenBlocks blocks, LightAccess lighting, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		pos.set(x, y, z);
		BlockState state = (x >> 4) == chunk.getPos().x() && (z >> 4) == chunk.getPos().z() ? state(chunk, blocks, pos) : lighting.outside().apply(pos);
		int block = Math.max(lighting.block().applyAsInt(pos), state.getLightEmission());
		return lighting.sky().applyAsInt(pos) << 4 | block;
	}

	private static int sideLight(ChunkAccess chunk, FrozenBlocks blocks, LightAccess lighting, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		int sky = 0, block = 0;
		for (int d = 0; d < 4; d++) {
			int v = lightAt(chunk, blocks, lighting, pos, x + (d == 0 ? 1 : d == 1 ? -1 : 0), y, z + (d == 2 ? 1 : d == 3 ? -1 : 0));
			sky = Math.max(sky, v >> 4);
			block = Math.max(block, v & 15);
		}
		block = Math.max(block, state(chunk, blocks, pos.set(x, y, z)).getLightEmission());
		return sky << 4 | block;
	}

	/** Colors, as the generator makes them (water over its floor by depth; crowns as LodNoise.dressTrees dresses them). Runs on a worker. */
	static boolean snowy(BlockState s) {
		return s.hasProperty(BlockStateProperties.SNOWY) && s.getValue(BlockStateProperties.SNOWY);
	}

	static Summary summarize(Snapshot s) {
		int[] top = new int[256], side = new int[256], groundColor = new int[256], below = new int[256], tex = new int[256];
		int[] plantA = new int[256], plantB = new int[256];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean[] fringe = new boolean[256], clear = new boolean[256];
		short[] crownHi = new short[256];
		byte[] solidDepth = new byte[256];
		short[] islandBottom = s.islands ? new short[256] : null;
		short[] crownLo=s.crownLo.clone();int[] runs=s.crownRuns.clone();boolean[] segmented=new boolean[256];
		if(LodConfig.STRUCTURE_DETAIL && s.sections!=null) for(int i=0;i<256;i++) {
			if(s.crown[i]==null) continue;
			int x=i&15,z=i>>4,upper=s.crownHi[i],base=Math.max(Math.max(s.minY,upper-LodStructureLayers.RANGE),s.height[i]);
			java.util.function.IntPredicate solid=y -> {
				int section=((y-s.minY)>>4)-s.secFirst;
				if(section<0 || section>=s.sections.length || s.sections[section]==null) return false;
				BlockState state=s.sections[section].get(x,(y-s.minY)&15,z);return !state.isAir() && !decoration(state);
			};
			while(base<upper && !solid.test(base)) base++;
			int packed=LodStructureLayers.encode(base,upper,solid);
			if(packed!=0) {crownLo[i]=(short)base;runs[i]=packed;segmented[i]=true;}
		}
		if (s.sections != null) {
			byte[] solid = new byte[s.sections.length];
			for (int i = 0; i < 256; i++) if (s.runFrom[i] != Short.MIN_VALUE) solidDepth[i] = (byte) solidRun(s, solid, i & 15, s.runFrom[i], i >> 4);
		}
		// (a snow layer is a covering: the block under it hides or not)
		for (int i = 0; i < 256; i++) {
			clear[i] = s.water[i] != LodTile.DRY || s.top[i] == null || !(s.top[i].is(Blocks.SNOW) ? s.under[i] : s.top[i]).canOcclude();
		}
		int bx0 = s.chunkX * 16, bz0 = s.chunkZ * 16;
		for (int i = 0; i < 256; i++) {
			int x = bx0 + (i & 15), z = bz0 + (i >> 4);
			Biome b = s.biome[i].value();
			int color = LodColors.top(s.top[i], b, x, z);
			// a snow layer covers the block under it: the column's side is that (snowy) block's, whose texture has its own
			// white band along the top (no fringe)
			boolean layer = s.top[i].is(Blocks.SNOW);
			int sideColor = LodColors.side(layer ? s.under[i] : s.top[i], b, x, z);
			below[i] = LodColors.top(s.under[i], b, x, z);
			fringe[i] = LodColors.fringed(s.top[i]) && !snowy(s.top[i]);
			boolean leavesTop = s.top[i].is(BlockTags.LEAVES), snowOnLeaves = s.top[i].is(Blocks.SNOW) && s.under[i].is(BlockTags.LEAVES);
			BlockState treeBase = leavesTop || snowOnLeaves ? LodColors.treeBase(leavesTop ? s.top[i] : s.under[i], s.trunk[i]) : null;
			if (leavesTop || snowOnLeaves) {
				// a tree standing here, seen from afar: its crown on top, its leaves on its sides
				BlockState leaf = leavesTop ? s.top[i] : s.under[i];
				if (!snowOnLeaves) color = LodColors.top(leaf, b, x, z);
				sideColor = LodColors.side(leaf, b, x, z);
				// a trunk under the leaves: its logs below the top block
				below[i] = LodColors.side(treeBase, b, x, z);
				fringe[i] = false;
			}
			if (s.water[i] != LodTile.DRY) {
				int depth = s.water[i] - s.height[i];
				int waterColor = LodColors.top(Blocks.WATER.defaultBlockState(), b, x, z);
				color = LodWaterColor.surface(color, waterColor, depth);
				sideColor = color;
			}
			int surface = s.water[i] != LodTile.DRY ? Math.max(s.water[i], s.height[i]) : s.height[i];
			if (islandBottom != null) islandBottom[i] = surface == LodIslands.EMPTY ? LodIslands.EMPTY
				: (short)Math.max(s.minY, surface - Math.max(1, Byte.toUnsignedInt(solidDepth[i])));
			crownHi[i] = (short) surface;
			if (LodConfig.TEXTURES) {
				BlockState sideState = layer ? s.under[i] : s.top[i];
				// Keep the texture choice aligned with the representative color above. The block immediately below the
				// sampled leaf can be stone/soil; it must not texture the whole side of a collapsed canopy column.
				BlockState belowState = treeBase != null && s.crown[i] == null ? treeBase : layer ? sideState : s.under[i];
				tex[i] = s.water[i] != LodTile.DRY ? 0 : LodPalette.word(LodPalette.id(s.top[i]), LodPalette.id(sideState), LodPalette.id(belowState));
			}
			if (s.crown[i] != null) {
				// a crown over air: its own colors; the ground keeps its own under it
				groundColor[i] = color;
				BlockState leaf = s.crownLeaf[i];
				BlockState topLeaf = s.overhang[i] ? leaf : leaf != null && leaf.is(BlockTags.LEAVES) ? leaf : Blocks.OAK_LEAVES.defaultBlockState();
				color = s.overhang[i] || s.crown[i].is(Blocks.SNOW) ? LodColors.top(s.crown[i], b, x, z) : LodColors.top(topLeaf, b, x, z);
				sideColor = LodColors.side(topLeaf, b, x, z);
				crownHi[i] = s.crownHi[i];
				// the crown's top and sides, and the ground's block under it (its walls in the crown's shade)
				BlockState ground = s.top[i].is(Blocks.SNOW) ? s.under[i] : s.top[i];
				if (LodConfig.TEXTURES) tex[i] = LodPalette.word(LodPalette.id(s.crown[i]), LodPalette.id(topLeaf), LodPalette.id(ground));
			}
			if (s.overhang[i]) tex[i] |= LodClip.TEX_OVERHANG;
			if (LodConfig.LIGHTING && s.lightValid[i]) tex[i] |= LodClip.TEX_LIGHT;
			top[i] = color;
			side[i] = sideColor;
			// a plant on the ground: its blocks (crossed quads, up to 3), color and the game's offset
			BlockState p0 = s.above[i * 3];
			if (LodConfig.PLANTS && p0 != null && s.crown[i] == null && LodColors.look(p0).cross() && p0.getFluidState().isEmpty()) {
				int blocks = 1;
				while (blocks < 3 && s.above[i * 3 + blocks] != null && LodColors.look(s.above[i * 3 + blocks]).cross()) blocks++;
				BlockState upper = blocks > 1 ? s.above[i * 3 + blocks - 1] : null;
				var off = p0.getOffset(pos.set(x, s.height[i], z));
				plantA[i] = LodClip.plantWordA(LodPalette.id(p0), upper != null ? LodPalette.id(upper) : 0, blocks);
				plantB[i] = LodClip.plantWordB(LodColors.side(p0, b, x, z), off.x(), off.y(), off.z());
			}
		}
		return new Summary(s.chunkX, s.chunkZ, s.height, s.water, top, side, below, fringe, crownLo, crownHi, groundColor, tex, runs, plantA, plantB,
			clear, solidDepth, s.light, islandBottom, segmented);
	}
}
