package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.probe.GameplayProbe;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SoundEngine.class)
abstract class SoundProbeMixin {
    @WrapMethod(method = "play")
    private SoundEngine.PlayResult mcoptProbe$play(SoundInstance sound, Operation<SoundEngine.PlayResult> original) {
        var event = GameplayProbe.begin("soundPlay");
        try { return original.call(sound); }
        finally {
            if (GameplayProbe.end(event)) {
                // A custom sound's metadata must not replace the game's result/exception.
                try { event.subject = String.valueOf(sound.getIdentifier()); }
                catch (RuntimeException unavailable) { event.subject = "<unavailable>"; }
                event.implementation = sound == null ? "null" : sound.getClass().getName();
                event.commit();
            }
        }
    }
    @WrapMethod(method = "shouldChangeDevice")
    private boolean mcoptProbe$device(Operation<Boolean> original) {
        var event = GameplayProbe.begin("soundDeviceCheck");
        try { return original.call(); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
}
