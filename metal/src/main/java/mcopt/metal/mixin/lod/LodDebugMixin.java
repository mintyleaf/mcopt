package mcopt.metal.mixin.lod;

import java.util.List;
import mcopt.metal.lod.Lod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
abstract class LodDebugMixin {
	@Inject(method = "extractLines", at = @At("HEAD"))
	private void mcopt$lodStats(GuiGraphicsExtractor g, List<String> lines, boolean left, int width, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (left && mc.level != null && mc.getDebugOverlay().showDebugScreen()) {
			List<String> stats = Lod.debugLines();
			if (!stats.isEmpty()) { lines.add(""); lines.addAll(stats); }
		}
	}
}
