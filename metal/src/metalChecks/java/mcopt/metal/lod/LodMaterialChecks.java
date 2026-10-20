package mcopt.metal.lod;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;

/** Real block states and the game's own multipart definitions, without starting the renderer or a world. */
public final class LodMaterialChecks {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		for (var vine : Blocks.VINE.getStateDefinition().getPossibleStates()) {
			int generated=LodClip.geomWord(90,false);
			if (!LodChunks.generatedHangingDecoration(generated,0,vine)
				|| LodChunks.generatedHangingDecoration(generated|LodClip.GEOM_CLEAR,0,vine)
				|| LodChunks.generatedHangingDecoration(generated,LodClip.TEX_LIGHT,vine)
				|| LodChunks.generatedHangingDecoration(generated,LodClip.TEX_OVERHANG,vine)
				|| LodChunks.generatedHangingDecoration(0,0,vine)) throw new AssertionError("Vine cache repair overwrites captured terrain/builds or misses a hanging vine state");
		}
		if (LodChunks.hangingDecoration(Blocks.OAK_LEAVES.defaultBlockState()) || LodChunks.hangingDecoration(Blocks.OAK_LOG.defaultBlockState()))
			throw new AssertionError("Vine filtering removed solid canopy/trunk blocks");
		int checked = 0;
		for (Block block : new Block[] {Blocks.RED_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM_BLOCK}) {
			String name = block == Blocks.RED_MUSHROOM_BLOCK ? "red_mushroom_block" : "brown_mushroom_block";
			try (var in = LodMaterialChecks.class.getResourceAsStream("/assets/minecraft/blockstates/" + name + ".json")) {
				if (in == null) throw new AssertionError("Missing real mushroom model definition");
				var parts = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("multipart");
				for (var state : block.getStateDefinition().getPossibleStates()) {
					Direction face = LodColors.sideFace(state);
					boolean skin = state.getValue(HugeMushroomBlock.NORTH) || state.getValue(HugeMushroomBlock.EAST)
						|| state.getValue(HugeMushroomBlock.SOUTH) || state.getValue(HugeMushroomBlock.WEST) || state.getValue(HugeMushroomBlock.UP);
					boolean selected = state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face));
					if (skin != selected) throw new AssertionError("Interior selected over available mushroom skin: " + state);
					String sideModel = null, bottomModel = null;
					for (var part : parts) {
						var p = part.getAsJsonObject(); var when = p.getAsJsonObject("when");
						if (when.has(face.getSerializedName()) && when.get(face.getSerializedName()).getAsBoolean() == selected)
							sideModel = p.getAsJsonObject("apply").get("model").getAsString();
						if (when.has("down") && when.get("down").getAsBoolean() == state.getValue(HugeMushroomBlock.DOWN))
							bottomModel = p.getAsJsonObject("apply").get("model").getAsString();
					}
					if (sideModel == null || !sideModel.endsWith(skin ? name : "mushroom_block_inside")) throw new AssertionError("Wrong cap-side model");
					if (bottomModel == null || !bottomModel.endsWith(state.getValue(HugeMushroomBlock.DOWN) ? name : "mushroom_block_inside"))
						throw new AssertionError("Cap underside lost its actual material");
					checked++;
				}
			}
		}
		if (LodColors.sideFace(Blocks.GRASS_BLOCK.defaultBlockState()) != Direction.NORTH
			|| LodColors.sideFace(Blocks.MUSHROOM_STEM.defaultBlockState()) != Direction.NORTH) throw new AssertionError("Unrelated materials changed");
		for (Block leafBlock : new Block[] {Blocks.OAK_LEAVES,Blocks.BIRCH_LEAVES,Blocks.JUNGLE_LEAVES}) {
			var leaf=leafBlock.defaultBlockState();
			if (!LodColors.leafColumn(LodClip.geomWord(81,false),leaf,leaf)
				|| !LodColors.leafColumn(LodClip.geomWord(81,false),Blocks.SNOW.defaultBlockState(),leaf)
				|| LodColors.leafColumn(LodClip.crownGeomWord(81,2,false),leaf,leaf)
				|| LodColors.leafColumn(LodClip.geomWord(81,false),Blocks.STONE.defaultBlockState(),Blocks.STONE.defaultBlockState())
				|| LodColors.treeBase(leaf,null)!=leaf) throw new AssertionError("Tree base leaks soil/stone or overwrites real ground beneath a floating crown");
			for (Block log : new Block[] {Blocks.OAK_LOG,Blocks.JUNGLE_LOG,Blocks.ACACIA_LOG}) for (Direction.Axis axis : Direction.Axis.values()) {
				var source=log.defaultBlockState().setValue(RotatedPillarBlock.AXIS,axis);
				var base=LodColors.treeBase(leaf,source);
				if (base.getBlock()!=log || base.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y
					|| source.getValue(RotatedPillarBlock.AXIS)!=axis) throw new AssertionError("Tree wall repeats horizontal end grain or changes the source branch");
			}
		}
		System.out.println("PASS: " + checked + " mushroom skins/undersides; leaf-column material guards and upright trunk bases preserve real ground and source blocks");
	}
}
