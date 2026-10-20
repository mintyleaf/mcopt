package mcopt.replay;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.serialization.Dynamic;
import java.net.Proxy;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.*;
import net.minecraft.server.dedicated.*;
import net.minecraft.server.jsonrpc.JsonRpc;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.*;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.*;

/** Creates new vanilla fixture terrain only; refuses an existing destination. */
public final class Fixture {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Pass -PfixtureOutput=PATH");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) throw new IllegalArgumentException("Fixture destination must not exist: " + root);
        Files.createDirectories(root);
        String seed = args.length > 1 ? args[1] : "12345";
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path properties = root.resolve("server.properties");
        Files.writeString(properties,"online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-seed="+Long.parseLong(seed)
            +"\ngamemode=spectator\ndifficulty=peaceful\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\nmax-tick-time=-1\n");
        var settings = new DedicatedServerSettings(properties);
        var storage = LevelStorageSource.createDefault(root).validateAndCreateAccess("world");
        var packs = ServerPacksSource.createPackRepository(storage);
        var configMethod = Main.class.getDeclaredMethod("loadOrCreateConfig",DedicatedServerProperties.class,Dynamic.class,boolean.class,PackRepository.class);
        configMethod.setAccessible(true);
        var config = (WorldLoader.InitConfig)configMethod.invoke(null,settings.getProperties(),null,false,packs);
        var dataMethod = Main.class.getDeclaredMethod("createNewWorldData",DedicatedServerSettings.class,WorldLoader.DataLoadContext.class,Registry.class,boolean.class,boolean.class);
        dataMethod.setAccessible(true);
        WorldLoader.WorldDataSupplier<LevelDataAndDimensions.WorldDataAndGenSettings> data = context -> {
            try { return (WorldLoader.DataLoadOutput<LevelDataAndDimensions.WorldDataAndGenSettings>)dataMethod.invoke(null,settings,context,
                context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM),false,false); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        WorldStem stem = Util.blockUntilDone(executor -> WorldLoader.load(config,data,WorldStem::new,Util.backgroundExecutor(),executor)).get(90,TimeUnit.SECONDS);
        var notifications = new NotificationManager();
        var services = Services.create(MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY),root.toFile());
        var ready = new CompletableFuture<Void>();
        DedicatedServer server = MinecraftServer.spin(thread -> {
            var created = new DedicatedServer(thread,storage,packs,stem,Optional.empty(),settings,DataFixers.getDataFixer(),services,
                    JsonRpc.create(settings,notifications),notifications) {
                @Override protected boolean initServer() throws java.io.IOException {
                    boolean initialized = super.initServer();
                    if (initialized) ready.complete(null);
                    return initialized;
                }
            };
            notifications.setServer(created);return created;
        });
        try {
            ready.get(90,TimeUnit.SECONDS);
            server.submit(() -> {
                server.setDefaultGameType(GameType.SPECTATOR);
                server.getWorldData().overworldData().setAllowCommands(true);
                server.getGameRules().set(GameRules.ADVANCE_TIME,false,server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER,false,server);
                server.setWeatherParameters(1_000_000,0,false,false);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set 6000");
                for (int z=-2;z<=2;z++) for (int x=-2;x<=2;x++) server.overworld().getChunk(x,z);
                server.saveEverything(true,true,true);
            }).get(90,TimeUnit.SECONDS);
        } finally { server.halt(true); }
        Files.writeString(root.resolve("fixture.json"),"{\"schema\":1,\"seed\":"+seed+",\"minecraft\":\"26.3\",\"generated\":\"vanilla FULL spawn fixture\"}\n");
        System.out.println("REPLAY_FIXTURE_READY " + root);
        System.exit(0);
    }
}
