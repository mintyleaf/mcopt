package mcopt.metal.compat;

import java.lang.invoke.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

/** Replays the optional mod's compile-loop additions when our mesher replaces that loop. */
public final class BetterGrassSnow {
	private BetterGrassSnow() { }
	private static final class Holder { static final @Nullable Api API = resolve(); }
	public static @Nullable Api get() { return Holder.API; }

	public record Api(MethodHandle layer, MethodHandle canHave, MethodHandle snow) {
		public @Nullable BlockState ghost(BlockAndTintGetter level, BlockPos pos) {
			try {
				// Reject ineligible shapes before the expensive neighbor search. The mod's predicate also honors OFF/whitelists.
				return (boolean)this.canHave.invokeExact(level, pos) ? (BlockState)this.layer.invokeExact(level, pos) : null;
			} catch (RuntimeException | Error failure) { throw failure; }
			catch (Throwable failure) { throw new IllegalStateException("BetterGrassify ghost layer failed", failure); }
		}
		public BlockState snowy(BlockAndTintGetter level, BlockPos pos, BlockState state) {
			if (state.getOptionalValue(BlockStateProperties.SNOWY).isEmpty()) return state;
			try {
				BlockPos above = pos.above();
				return (boolean)this.snow.invokeExact(level, above) && (boolean)this.canHave.invokeExact(level, above)
					? state.setValue(BlockStateProperties.SNOWY, true) : state;
			} catch (RuntimeException | Error failure) { throw failure; }
			catch (Throwable failure) { throw new IllegalStateException("BetterGrassify snowy state failed", failure); }
		}
	}

	static boolean supportedVersion(String version) { return version.equals("1.8.8+fabric.26.3"); }
	private static @Nullable Api resolve() {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.compat.betterGrassSnow", "true"))
			|| FabricLoader.getInstance().getModContainer("bettergrass").filter(mod -> supportedVersion(mod.getMetadata().getVersion().getFriendlyString())).isEmpty()) return null;
		try {
			Api api = bind(Class.forName("dev.ultimatchamp.bettergrass.util.BetterSnowUtils"));
			System.out.println("[mcopt] BetterGrassify snow layers: enabled");
			return api;
		} catch (ReflectiveOperationException | LinkageError failure) {
			System.out.println("[mcopt] BetterGrassify snow layers: unchanged (unsupported API)");
			return null;
		}
	}
	static Api bind(Class<?> utils) throws ReflectiveOperationException {
		var lookup = MethodHandles.publicLookup();
		return new Api(lookup.findStatic(utils, "getLayerNeighbour", MethodType.methodType(BlockState.class, BlockAndTintGetter.class, BlockPos.class)),
			lookup.findStatic(utils, "canHaveGhostLayer", MethodType.methodType(boolean.class, BlockAndTintGetter.class, BlockPos.class)),
			lookup.findStatic(utils, "isLayerNeighbourSnow", MethodType.methodType(boolean.class, BlockAndTintGetter.class, BlockPos.class)));
	}
}
