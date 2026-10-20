package mcopt.replay.mixin;

import java.util.function.BooleanSupplier;
import mcopt.replay.Replay;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerReplayMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcoptReplay$server(BooleanSupplier hasTime, CallbackInfo ci) { Replay.server((ServerLevel)(Object)this); }
}
