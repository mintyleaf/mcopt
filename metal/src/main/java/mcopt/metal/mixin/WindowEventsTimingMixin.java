package mcopt.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.MetalBridge;
import org.spongepowered.asm.mixin.Mixin;

/** Distinguish native event-pump stalls from drawable acquisition and render work. */
@Mixin(RenderSystem.class)
abstract class WindowEventsTimingMixin {
	@WrapMethod(method = "pollEvents")
	private static void mcopt$pollEvents(SDLEventHandler handler, Operation<Void> original) {
		Object event = MetalBridge.windowEventsBegin();
		try { original.call(handler); }
		finally { MetalBridge.windowEventsEnd(event); }
	}
}
