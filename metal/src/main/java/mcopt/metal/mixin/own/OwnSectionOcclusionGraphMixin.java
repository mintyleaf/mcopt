package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnViewDistance;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep the CPU visibility/compilation graph consistent with our GPU's vertical range while LOD fog is extended. */
@Mixin(SectionOcclusionGraph.class)
abstract class OwnSectionOcclusionGraphMixin {
	@Redirect(method = "getRelativeFrom", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ViewArea;getViewDistance()I"))
	private int mcopt$verticalDistance(ViewArea viewArea) {
		// Horizontal distance is checked separately by isInViewDistance and keeps the configured render distance.
		return OwnViewDistance.verticalDistance(viewArea.getViewDistance());
	}
}
