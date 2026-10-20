package mcopt.metal.mixin.compat;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Each compatibility patch applies only when its corresponding optional mod is loaded. */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
	static { mcopt.metal.Profile.apply(); }
	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".DhRenderApiMixin")) return FabricLoader.getInstance().isModLoaded("distanthorizons");
		if (mixinClassName.endsWith(".MoreCullingFaceMixin")) return FabricLoader.getInstance().isModLoaded("moreculling")
			&& Boolean.parseBoolean(System.getProperty("mcopt.compat.moreCullingFace", "true"));
		return false;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if (mixinClassName.endsWith(".MoreCullingFaceMixin")) {
			boolean optimized = mcopt.metal.compat.MoreCullingFaceOptimizer.optimize(targetClass);
			System.out.println("[mcopt] MoreCulling face callback: " + (optimized ? "optimized" : "unchanged (unrecognized bridge)"));
		}
	}
}
