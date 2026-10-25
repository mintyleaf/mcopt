package mcopt.probe;

import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Does not initialize Minecraft, SDL, a renderer, a world or any game registry. */
public final class GameplayProbeChecks {
    private record Target(String owner, String method, String descriptor) { }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        // Validate exact method contracts against the actual game jar without loading target classes.
        for (var target : List.of(
                new Target("net/minecraft/client/renderer/texture/TextureManager", "registerAndLoad", "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/ReloadableTexture;)V"),
                new Target("com/mojang/blaze3d/pipeline/PipelineCache", "get", "(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"),
                new Target("net/minecraft/client/sounds/SoundEngine", "play", "(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;"),
                new Target("net/minecraft/client/sounds/SoundEngine", "shouldChangeDevice", "()Z"),
                new Target("net/minecraft/client/particle/ParticleEngine", "createParticle", "(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;"),
                new Target("net/minecraft/client/particle/ParticleEngine", "tick", "()V"),
                new Target("net/minecraft/client/renderer/extract/LevelExtractor", "extractVisibleEntities", "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"),
                new Target("net/minecraft/client/renderer/extract/LevelExtractor", "extractVisibleBlockEntities", "(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V"),
                new Target("net/minecraft/client/renderer/extract/LevelExtractor", "extractBlockDestroyAnimation", "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"),
                new Target("net/minecraft/client/renderer/LevelRenderer", "submitBlockDestroyAnimation", "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"),
                new Target("net/minecraft/client/multiplayer/ClientLevel", "pollLightUpdates", "()V"),
                new Target("net/minecraft/client/Minecraft", "startAttack", "()Z"),
                new Target("net/minecraft/client/Minecraft", "continueAttack", "(Z)V"),
                new Target("net/minecraft/client/Minecraft", "startUseItem", "()V"))) {
            try (var in = GameplayProbeChecks.class.getClassLoader().getResourceAsStream(target.owner + ".class")) {
                require(in != null, "Missing target class: " + target.owner);
                var model = ClassFile.of().parse(in.readAllBytes());
                require(model.methods().stream().anyMatch(m -> m.methodName().equalsString(target.method)
                    && m.methodType().equalsString(target.descriptor)), "Missing target: " + target);
            }
        }

        new GameplayProbe().onInitializeClient();
        require(!GameplayProbe.enabled(), "Probe unexpectedly active outside a recording");
        require(GameplayProbe.begin("disabled") == null, "Disabled probe allocated a scope");
        var file = Files.createTempFile("mcopt-gameplay-probe-check-", ".jfr");
        try (var recording = new Recording()) {
            recording.enable("mcopt.GameplayWork").withThreshold(Duration.ofMillis(2)).withStackTrace();
            recording.enable("mcopt.GameplayAction").withoutStackTrace();
            recording.start();
            require(GameplayProbe.enabled(), "Explicit recording did not activate probe");
            var event = GameplayProbe.begin("textureLoad");
            Thread.sleep(10);
            require(GameplayProbe.end(event), "Retained slow work did not pass threshold");
            event.subject = "minecraft:diagnostic-check";
            event.commit();
            GameplayProbe.action("attackOrBreak");
            recording.stop();
            recording.dump(file);
        }
        require(!GameplayProbe.enabled(), "Probe remained active after stop");
        try {
            var events = RecordingFile.readAllEvents(file);
            var work = events.stream().filter(e -> e.getEventType().getName().equals("mcopt.GameplayWork")).toList();
            var actions = events.stream().filter(e -> e.getEventType().getName().equals("mcopt.GameplayAction")).toList();
            require(work.size() == 1 && work.getFirst().getString("subject").equals("minecraft:diagnostic-check"), "Work identity was lost");
            require(work.getFirst().getStackTrace() != null, "Work caller missing");
            require(actions.size() == 1 && actions.getFirst().getString("operation").equals("attackOrBreak"), "Action context missing");
            require(actions.getFirst().getStackTrace() == null, "Action unnecessarily captured a stack");
        } finally { Files.deleteIfExists(file); }
        System.out.println("Gameplay probe: 14 real game target contracts and bounded JFR event lifecycle pass; no game launched.");
    }
}
