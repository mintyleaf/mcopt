package mcopt.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.metal.PresentationRecovery;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class PresentationRecoveryMixin {
	@Unique private boolean mcopt$deferDraw;

	@WrapOperation(method="renderFrame", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private void mcopt$extract(GameRenderer renderer, DeltaTracker delta, boolean advance, Operation<Void> original) {
		Minecraft mc=(Minecraft)(Object)this;
		this.mcopt$deferDraw=PresentationRecovery.beginFrame(mc.level!=null && mc.player!=null && mc.gui.screen()==null && mc.gui.overlay()==null);
		if(!this.mcopt$deferDraw)original.call(renderer,delta,advance);
	}

	@WrapOperation(method="renderFrame", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/GameRenderer;render()V"))
	private void mcopt$draw(GameRenderer renderer, Operation<Void> original) {
		if(!this.mcopt$deferDraw)original.call(renderer);
	}
}
