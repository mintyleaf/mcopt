package mcopt.probe.mixin;

import mcopt.probe.GameplayProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class ActionProbeMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void mcoptProbe$attack(CallbackInfoReturnable<Boolean> cir) { GameplayProbe.action("attackOrBreak"); }

    @Inject(method = "continueAttack", at = @At("HEAD"))
    private void mcoptProbe$continue(boolean attacking, CallbackInfo ci) {
        if (attacking) GameplayProbe.action("continueBreaking");
    }
    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void mcoptProbe$use(CallbackInfo ci) { GameplayProbe.action("useItem"); }
}
