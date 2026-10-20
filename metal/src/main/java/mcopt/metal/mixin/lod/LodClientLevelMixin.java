package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain: chunks the client loads, and unloads with the player's changes, become far terrain with their real surface. */
@Mixin(ClientLevel.class)
abstract class LodClientLevelMixin {
	@Inject(method = "onChunkLoaded", at = @At("TAIL"))
	private void mcopt$lodChunkLoaded(ChunkPos pos, CallbackInfo ci) {
		Lod.chunk(((ClientLevel) (Object) this).getChunkSource().getChunk(pos.x(), pos.z(), false));
	}

	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void mcopt$lodBlockChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
		net.minecraft.world.level.block.state.BlockState newState, int flags, CallbackInfo ci) {
		// Surface geometry, textures and light sources all need to follow edits, including stairs and torches.
		if (oldState != newState) Lod.blockChanged((ClientLevel)(Object)this, pos, oldState, newState);
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void mcopt$lodChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
		Lod.unloading(chunk);
		Lod.unloaded(chunk);
	}
}
