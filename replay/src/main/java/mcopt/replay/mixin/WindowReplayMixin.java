package mcopt.replay.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.replay.PacketTrace;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderSystem.class)
abstract class WindowReplayMixin {
    @WrapMethod(method="pollEvents")
    private static void mcoptReplay$events(SDLEventHandler handler,Operation<Void> original) {
        var scope=PacketTrace.beginWork("pollEvents");
        try{original.call(handler);}finally{PacketTrace.end(scope);}
    }
}
