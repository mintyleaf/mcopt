package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.probe.GameplayProbe;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ParticleEngine.class)
abstract class ParticleProbeMixin {
    @WrapMethod(method = "createParticle")
    private Particle mcoptProbe$create(ParticleOptions options, double x, double y, double z,
                                      double dx, double dy, double dz, Operation<Particle> original) {
        var event = GameplayProbe.begin("particleCreate");
        try { return original.call(options, x, y, z, dx, dy, dz); }
        finally {
            if (GameplayProbe.end(event)) {
                try { event.subject = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType())); }
                catch (RuntimeException unavailable) { event.subject = "<unavailable>"; }
                event.implementation = options == null ? "null" : options.getClass().getName();
                event.commit();
            }
        }
    }
    @WrapMethod(method = "tick")
    private void mcoptProbe$tick(Operation<Void> original) {
        var event = GameplayProbe.begin("particleTick");
        try { original.call(); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
}
