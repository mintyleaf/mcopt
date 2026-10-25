package mcopt.replay;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.management.ManagementFactory;
import java.util.*;
import jdk.jfr.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Opt-in wall-clock route playback in a marker-owned disposable instance. Never uses or records account arguments. */
public final class Replay {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PLAN = System.getProperty("mcopt.replay.plan", "");
    private static final String WORLD = "mcopt-replay";
    private static final boolean ON = !PLAN.isEmpty();
    private record Pose(double x,double y,double z,float yaw,float pitch,int fov) { }
    private record Segment(String name,double seconds,double[] from,double[] to,float yaw,float yawTo,float pitch,int fov,Map<String,String> edits) {
        Pose at(double fraction) {
            return new Pose(from[0]+(to[0]-from[0])*fraction,from[1]+(to[1]-from[1])*fraction,
                from[2]+(to[2]-from[2])*fraction,(float)(yaw+(yawTo-yaw)*fraction),pitch,fov);
        }
    }
    private static final List<Segment> segments = new ArrayList<>();
    private static final JsonArray liveStates = new JsonArray();
    private static boolean liveControls,traceClassLoading,lightSampling,firstUseTracing,gameplayEffects;
    private static boolean fallingBlocks,starTransition,reloadBeforeWarmup,naturalCaves;
    private static java.util.concurrent.CompletableFuture<Void> reloadFuture;
    private static long starPipelineDraws,firstStarPipelineNanos;
    private static Object starSettings;
    private static java.lang.reflect.Field starsEnabled;
    private static java.lang.reflect.Field starPipelineField;
    private static boolean originalStarsEnabled,starGateOpened;
    private static long packetBudgetNanos;
    public static long packetBudgetNanos(){return packetBudgetNanos;}
    public static boolean traceClientWork(){return ON && started && !finished;}
    private static volatile Pose target;
    private static Pose warmPose;
    private static volatile boolean serverReady;
    private static volatile boolean loaded,finished;
    private static boolean opened,started;
    private static boolean schematicFixture,compatFixture,terrainChanges,captureImage,captureRequested,moreCullingProbe,fogScaleProbe;
    private static volatile long mutationClock=Long.MAX_VALUE;
    private static volatile boolean captureFinished;
    private static volatile Exception captureError;
    private static double warmup,total;
    private static long startedAt,measureAt,lastFrame;
    private static int phase=-1,frames,lastFov=-1;
    private static boolean stableSetup=true;
    private static int minRenderDistance=Integer.MAX_VALUE,maxRenderDistance;
    private static int worldTargetWidth,worldTargetHeight;
    private static Path output;
    private static Recording recording;
    private static Marker marker;
    private static JsonObject setup;
    private static String routeHash;
    private static final int CAPACITY=60_000;
    private static final long[] times=new long[CAPACITY], durations=new long[CAPACITY];
    private static final int[] phases=new int[CAPACITY];
    private static final double[] xs=new double[CAPACITY],ys=new double[CAPACITY],zs=new double[CAPACITY],errors=new double[CAPACITY];
    private static final boolean[] focused=new boolean[CAPACITY];

    @Name("mcopt.ReplayPhase") @Label("Controlled replay phase") @Category("mcopt") @StackTrace(false)
    static final class Marker extends Event { String phase,routeSha256; }
    @Name("mcopt.ReplayOverlay") @Label("Schematic overlay CPU scope") @Category("mcopt") @StackTrace(false)
    static final class OverlaySample extends Event { }
    @Name("mcopt.ReplayFirstStarDraw") @Label("First actual colored-star pipeline request") @Category("mcopt") @StackTrace(false)
    static final class FirstStarDraw extends Event { double measuredSeconds; }
    public static Event overlaySample() { return ON && !finished ? new OverlaySample() : null; }
    public static boolean observesStarPipeline() { return ON && started && !finished && starTransition; }
    public static boolean isStarPipeline(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline) {
        if(!observesStarPipeline())return false;
        try {return starPipelineField.get(null)==pipeline;}
        catch(IllegalAccessException error){throw new IllegalStateException(error);}
    }
    public static void starPipelineDraw() {
        if(!observesStarPipeline())return;
        starPipelineDraws++;
        if(firstStarPipelineNanos==0){
            firstStarPipelineNanos=System.nanoTime();
            var event=new FirstStarDraw();event.measuredSeconds=(firstStarPipelineNanos-measureAt)/1e9;event.commit();
        }
    }

    private Replay() { }
    /** Sample inside renderLevel, after render-scaling mods take over and before they restore the UI target. */
    public static void worldTarget() {
        if(!ON || finished) return;
        var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if(phase>=0) stableSetup &= target.width==setup.get("worldTargetWidth").getAsInt()
            && target.height==setup.get("worldTargetHeight").getAsInt();
        worldTargetWidth=target.width;worldTargetHeight=target.height;
    }
    private static void load(Minecraft mc) throws Exception {
        Path owned=mc.gameDirectory.toPath().resolve(".mcopt-replay-owned.json");
        if(!Files.isRegularFile(owned)) throw new IllegalStateException("Replay requires a marker-owned disposable instance");
        byte[] bytes=Files.readAllBytes(Path.of(PLAN));
        JsonObject plan=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        traceClassLoading=plan.has("traceClassLoading") && plan.get("traceClassLoading").getAsBoolean();
        lightSampling=plan.has("lightSampling") && plan.get("lightSampling").getAsBoolean();
        firstUseTracing=plan.has("firstUseTracing") && plan.get("firstUseTracing").getAsBoolean();
        gameplayEffects=plan.has("gameplayEffects") && plan.get("gameplayEffects").getAsBoolean();
        fallingBlocks=plan.has("fallingBlocks") && plan.get("fallingBlocks").getAsBoolean();
        starTransition=plan.has("starTransition") && plan.get("starTransition").getAsBoolean();
        reloadBeforeWarmup=plan.has("reloadBeforeWarmup") && plan.get("reloadBeforeWarmup").getAsBoolean();
        naturalCaves=plan.has("naturalCaves") && plan.get("naturalCaves").getAsBoolean();
        if(naturalCaves)for(String flag:List.of("terrainChanges","compatFixture","schematicFixture"))
            if(plan.has(flag) && plan.get(flag).getAsBoolean())throw new IllegalArgumentException("naturalCaves cannot use synthetic fixtures");
        if((fallingBlocks || starTransition) && !gameplayEffects)throw new IllegalArgumentException("fallingBlocks/starTransition require gameplayEffects");
        if(firstUseTracing && !FabricLoader.getInstance().isModLoaded("mcopt-gameplay-probe"))
            throw new IllegalStateException("firstUseTracing requires the separate gameplay probe");
        double packetBudget=plan.has("packetBudgetMs")?plan.get("packetBudgetMs").getAsDouble():0;
        if(!Double.isFinite(packetBudget)||packetBudget<0||packetBudget>20)throw new IllegalArgumentException("packetBudgetMs must be0..20");
        packetBudgetNanos=(long)(packetBudget*1e6);
        schematicFixture=plan.has("schematicFixture") && plan.get("schematicFixture").getAsBoolean();
        compatFixture=plan.has("compatFixture") && plan.get("compatFixture").getAsBoolean();
        terrainChanges=plan.has("terrainChanges") && plan.get("terrainChanges").getAsBoolean();
        captureImage=plan.has("captureImage") && plan.get("captureImage").getAsBoolean();
        fogScaleProbe=plan.has("fogScaleProbe") && plan.get("fogScaleProbe").getAsBoolean();
        moreCullingProbe=plan.has("moreCullingProbe") && plan.get("moreCullingProbe").getAsBoolean();
        if(plan.has("maximize") && plan.get("maximize").getAsBoolean())org.lwjgl.sdl.SDLVideo.SDL_MaximizeWindow(mc.getWindow().handle());
        if(plan.get("schema").getAsInt()!=1) throw new IllegalArgumentException("Unsupported route schema");
        warmup=plan.get("warmupSeconds").getAsDouble();
        if(!Double.isFinite(warmup)||warmup<3||warmup>120) throw new IllegalArgumentException("warmupSeconds must be 3..120");
        for(JsonElement value:plan.getAsJsonArray("phases")) {
            JsonObject p=value.getAsJsonObject();
            double seconds=p.get("seconds").getAsDouble();
            double[] a=JSON.fromJson(p.get("from"),double[].class),b=JSON.fromJson(p.get("to"),double[].class);
            if(!Double.isFinite(seconds)||seconds<=0||a.length!=3||b.length!=3) throw new IllegalArgumentException("Invalid route segment");
            for(double v:a) if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite position");
            for(double v:b) if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite position");
            int fov=p.get("fov").getAsInt();float yaw=p.get("yaw").getAsFloat(),pitch=p.get("pitch").getAsFloat();
            float yawTo=p.has("yawTo")?p.get("yawTo").getAsFloat():yaw;
            if(fov<30||fov>110||!Float.isFinite(yaw)||!Float.isFinite(yawTo)||!Float.isFinite(pitch)||Math.abs(pitch)>90) throw new IllegalArgumentException("Invalid view");
            Map<String,String> edits=new LinkedHashMap<>();
            if(p.has("mcopt")) for(var e:p.getAsJsonObject("mcopt").entrySet()) {
                Class<?> controls=Class.forName("mcopt.metal.config.McoptRuntime");
                if(!(boolean)controls.getMethod("live",String.class).invoke(null,e.getKey()))throw new IllegalArgumentException("Not a live mcopt setting: "+e.getKey());
                edits.put(e.getKey(),e.getValue().getAsString());
            }
            liveControls|=!edits.isEmpty();
            segments.add(new Segment(p.get("name").getAsString(),seconds,a,b,yaw,yawTo,pitch,fov,Map.copyOf(edits)));total+=seconds;
        }
        if(segments.isEmpty()||segments.size()>32||total>300) throw new IllegalArgumentException("Route must have 1..32 phases, <=300 seconds");
        if(gameplayEffects && (!terrainChanges || total<58))throw new IllegalArgumentException("gameplayEffects requires the caving fixture and >=58s");
        routeHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        output=Path.of(System.getProperty("mcopt.replay.output")).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.writeString(output.resolve("live.json"),"{\"pid\":"+ProcessHandle.current().pid()+",\"routeSha256\":\""+routeHash+"\"}\n");
        Pose start=segments.getFirst().at(0);
        warmPose=new Pose(start.x,start.y,start.z,start.yaw,15,70);
        target=warmPose;loaded=true;
        System.out.println("[mcopt-replay] route "+routeHash+", warmup "+warmup+"s, measured "+total+"s");
    }

    public static void frame() {
        if(!ON||finished)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if(!loaded)load(mc);
            if(!opened && mc.gui.screen() instanceof TitleScreen) {
                Path world=mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
                if(!Files.isRegularFile(world.resolve("level.dat")))throw new IllegalStateException("Prepared fixture is missing");
                opened=true;mc.createWorldOpenFlows().openWorld(WORLD,()->fail(mc,new IllegalStateException("World open failed")));return;
            }
            if(mc.player==null||mc.level==null||!serverReady||mc.gui.screen()!=null) {
                if(started)throw new IllegalStateException("Playback interrupted by screen/world change");
                return;
            }
            long now=System.nanoTime();
            if(captureRequested) {
                if(captureError!=null)throw captureError;
                if(captureFinished)finish(mc,null);
                return;
            }
            if(!started) {
                if(starTransition && starSettings==null) {
                    Object config=Class.forName("com.beash.atmospherics.AtmosphericsClient").getMethod("getConfig").invoke(null);
                    starSettings=config.getClass().getField("stars").get(config);
                    starsEnabled=starSettings.getClass().getField("enabled");
                    starPipelineField=net.minecraft.client.renderer.SkyRenderer.class.getDeclaredField("ambientfog$pipeline");
                    starPipelineField.setAccessible(true);
                    originalStarsEnabled=starsEnabled.getBoolean(starSettings);
                    if(!originalStarsEnabled || !starSettings.getClass().getField("colored").getBoolean(starSettings))
                        throw new IllegalStateException("Star transition requires enabled colored Atmospherics stars");
                    // Keep the first request cold even if the saved world initially loads at night.
                    starsEnabled.setBoolean(starSettings,false);
                }
                if(reloadBeforeWarmup){
                    if(reloadFuture==null){reloadFuture=mc.reloadResourcePacks();return;}
                    if(!reloadFuture.isDone())return;
                    reloadFuture.join();
                }
                if(schematicFixture)SchematicFixture.load(output);
                if(moreCullingProbe)MoreCullingProbe.run(mc,output);
                started=true;startedAt=now;measureAt=now+(long)(warmup*1e9);
                mutationClock=measureAt;
                setup=setup(mc);
                JsonObject capture=new JsonObject();capture.addProperty("label","controlled_replay");capture.addProperty("pid",ProcessHandle.current().pid());
                capture.addProperty("startEpoch",System.currentTimeMillis()/1000.0);capture.addProperty("seconds",warmup+total);
                capture.addProperty("javaHome",System.getProperty("java.home"));capture.addProperty("gameDirectory",mc.gameDirectory.toString());
                Files.writeString(output.resolve("capture.json"),JSON.toJson(capture)+"\n");
                startRecording();
                System.out.println("[mcopt-replay] warmup started");
            }
            if(now<measureAt) { target=warmPose;applyPose();return; }
            // Effective distance can still be negotiating with the integrated server at initial world entry.
            if(phase<0) {
                if(compatFixture)TerrainCompatFixture.probe(mc,output);
                setup=setup(mc);setup.addProperty("sampledAfterWarmup",true);
            }
            int rd=mc.options.getEffectiveRenderDistance();minRenderDistance=Math.min(minRenderDistance,rd);maxRenderDistance=Math.max(maxRenderDistance,rd);
            stableSetup &= rd==setup.get("renderDistance").getAsInt()
                && mc.getWindow().getWidth()==setup.get("framebufferWidth").getAsInt()
                && mc.getWindow().getHeight()==setup.get("framebufferHeight").getAsInt()
                && mc.gameRenderer.mainRenderTarget().width==setup.get("mainTargetWidth").getAsInt()
                && mc.gameRenderer.mainRenderTarget().height==setup.get("mainTargetHeight").getAsInt()
                && mc.options.enableVsync().get()==setup.get("vsync").getAsBoolean()
                && mc.options.framerateLimit().get()==setup.get("maxFps").getAsInt();
            if(lastFrame!=0 && phase>=0) {
                if(frames==CAPACITY)throw new IllegalStateException("Frame sample capacity exceeded");
                times[frames]=lastFrame-measureAt;durations[frames]=now-lastFrame;phases[frames]=phase;
                xs[frames]=mc.player.getX();ys[frames]=mc.player.getY();zs[frames]=mc.player.getZ();
                Pose p=target;errors[frames]=Math.sqrt(Math.pow(xs[frames]-p.x,2)+Math.pow(ys[frames]-p.y,2)+Math.pow(zs[frames]-p.z,2));
                focused[frames]=mc.isWindowActive();frames++;
            }
            double elapsed=(now-measureAt)/1e9,offset=0;
            if(starTransition && !starGateOpened && elapsed>=23) {
                starsEnabled.setBoolean(starSettings,true);starGateOpened=true;
            }
            int selected=0;
            for(;selected<segments.size();selected++) {
                Segment s=segments.get(selected);if(elapsed<offset+s.seconds)break;offset+=s.seconds;
            }
            if(selected==segments.size()) {
                recordLiveState();
                if(captureImage) {
                    if(marker!=null){marker.end();marker.commit();marker=null;}
                    captureRequested=true;lastFrame=0;
                    net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),image->{
                        try {image.writeToFile(output.resolve("view.png"));}
                        catch(Exception e){captureError=e;}
                        finally {image.close();captureFinished=true;}
                    });
                } else finish(mc,null);
                return;
            }
            if(selected!=phase) {
                recordLiveState();
                if(marker!=null) {marker.end();marker.commit();}
                if(!segments.get(selected).edits.isEmpty())applyLiveEdits(mc,segments.get(selected).edits);
                phase=selected;marker=new Marker();marker.phase=segments.get(phase).name;marker.routeSha256=routeHash;marker.begin();
                System.out.println("[mcopt-replay] phase "+segments.get(phase).name);
            }
            target=segments.get(phase).at(Math.clamp((elapsed-offset)/segments.get(phase).seconds,0,1));
            applyPose();lastFrame=now;
        } catch(Exception error) {fail(mc,error);}
    }

    private static void recordLiveState() throws Exception {
        if(!liveControls||phase<0)return;
        JsonObject state=new JsonObject();state.addProperty("phase",segments.get(phase).name);
        if(fogScaleProbe)state.add("fogScale",FogScaleProbe.read());
        state.add("lod",JSON.toJsonTree(Class.forName("mcopt.metal.lod.Lod").getMethod("report").invoke(null)));
        state.add("lodThreads",JSON.toJsonTree(Thread.getAllStackTraces().keySet().stream()
            .filter(t->t.isAlive()&&t.getName().startsWith("mcopt-lod-")).map(Thread::getName).sorted().toList()));
        liveStates.add(state);
        Files.writeString(output.resolve("live-controls.json"),JSON.toJson(liveStates)+"\n");
    }

    /** Use the actual settings editor's save path; only the marker-owned instance config is edited. */
    private static void applyLiveEdits(Minecraft mc,Map<String,String> edits) throws Exception {
        Class<?> type=Class.forName("mcopt.metal.config.McoptSettings");
        var constructor=type.getDeclaredConstructor(Path.class,boolean.class);constructor.setAccessible(true);
        Object editor=constructor.newInstance(mc.gameDirectory.toPath().resolve("config/mcopt.properties"),true);
        var field=type.getDeclaredField("values");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,String> values=(Map<String,String>)field.get(editor);
        values.putAll(edits);
        var save=type.getDeclaredMethod("save");save.setAccessible(true);save.invoke(editor);
    }

    /** Applied after mouse/tick updates, before camera extraction, so input never drives the route. */
    public static void applyPose() {
        if(!ON||!loaded||finished||target==null)return;
        Minecraft mc=Minecraft.getInstance();if(mc.player==null)return;
        Pose p=target;
        mc.player.setPos(p.x,p.y,p.z);mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot(p.yaw);mc.player.setXRot(p.pitch);mc.player.setOldPosAndRot();
        if(lastFov!=p.fov) {mc.options.fov().set(p.fov);lastFov=p.fov;}
    }

    /** Server-thread position/ticket updates, without teleport commands on every frame. Only the disposable Overworld runs. */
    public static void server(ServerLevel level) {
        if(!ON||!loaded||finished||target==null||level.dimension()!=Level.OVERWORLD)return;
        if(!level.getServer().isSingleplayer())return;
        Pose p=target;
        for(var player:level.players()) {
            if(!serverReady) {
                if(schematicFixture)SchematicFixture.populate(level);
                if(compatFixture)TerrainCompatFixture.populate(level);
                if(terrainChanges)TerrainChanges.populate(level);
                if(gameplayEffects)GameplayEffects.populate(level,fallingBlocks,starTransition);
                player.setGameMode(terrainChanges||naturalCaves?GameType.CREATIVE:GameType.SPECTATOR);
                if(terrainChanges){
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH));
                }
                level.getServer().getGameRules().set(GameRules.ADVANCE_TIME,false,level.getServer());
                level.getServer().getGameRules().set(GameRules.ADVANCE_WEATHER,false,level.getServer());
                if(!naturalCaves)level.getServer().getGameRules().set(GameRules.SPAWN_MOBS,false,level.getServer());
                level.getServer().getGameRules().set(GameRules.PLAYER_MOVEMENT_CHECK,false,level.getServer());
                level.getServer().getGameRules().set(GameRules.ELYTRA_MOVEMENT_CHECK,false,level.getServer());
            }
            player.setPos(p.x,p.y,p.z);player.setYRot(p.yaw);player.setXRot(p.pitch);player.setDeltaMovement(Vec3.ZERO);
            player.connection.resetPosition();level.getChunkSource().move(player);serverReady=true;
        }
        if(terrainChanges&&mutationClock!=Long.MAX_VALUE)TerrainChanges.tick(level,(System.nanoTime()-mutationClock)/1e9);
        if(gameplayEffects&&mutationClock!=Long.MAX_VALUE)GameplayEffects.tick(level,(System.nanoTime()-mutationClock)/1e9);
    }

    private static void startRecording() throws Exception {
        if(!Boolean.parseBoolean(System.getProperty("mcopt.replay.jfr","true")))return;
        AssetTrace.prepare();
        // Phase markers must not load/transform their JFR class on the first measured frame.
        EventType.getEventType(Marker.class);
        EventType.getEventType(OverlaySample.class);
        EventType.getEventType(PacketTrace.Work.class);
        EventType.getEventType(FirstStarDraw.class);
        recording=new Recording(Configuration.getConfiguration("profile"));
        // Class-loader inventory is a stop-the-world VM operation; it contaminated earlier hitch captures.
        for(String event:List.of("jdk.ClassLoaderStatistics","jdk.ThreadDump","jdk.NativeMemoryUsage",
                "jdk.NativeMemoryUsageTotal","jdk.NativeLibrary"))recording.disable(event);
        for(String event:List.of("mcopt.ReplayPhase","mcopt.MetalOperation","mcopt.MetalCommandBuffer","mcopt.MetalPresentation",
                "mcopt.MetalDrawable","mcopt.MetalPipelineCompile","mcopt.OwnArenaGrowth","mcopt.LodArenaGrowth","mcopt.OwnArenaAllocation",
                "mcopt.LodScheduling","mcopt.LodCaptureQueue","mcopt.LodClientCapture","mcopt.LodDetail",
                "mcopt.LitematicaOverlayBatch","mcopt.ReplayOverlay"))recording.enable(event).withoutStackTrace();
        recording.enable("mcopt.ReplayClientWork").withoutStackTrace();
        recording.enable("mcopt.ReplayAsset").withStackTrace();
        recording.enable("mcopt.ReplayTerrainChange").withoutStackTrace();
        recording.enable("mcopt.ReplayGameplayEffect").withoutStackTrace();
        recording.enable("mcopt.ReplayFirstStarDraw").withoutStackTrace();
        recording.enable("mcopt.MetalOperation").withThreshold(java.time.Duration.ZERO).withoutStackTrace();
        if(traceClassLoading)recording.enable("jdk.ClassLoad").withThreshold(java.time.Duration.ZERO).withStackTrace();
        else if(firstUseTracing)recording.enable("jdk.ClassLoad").withThreshold(java.time.Duration.ofMillis(2)).withStackTrace();
        if(firstUseTracing){
            recording.enable("mcopt.GameplayWork").withThreshold(java.time.Duration.ofMillis(2)).withStackTrace();
            recording.enable("mcopt.GameplayAction").withoutStackTrace();
        }
        if(lightSampling||firstUseTracing){
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(20));
            recording.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(20));
            recording.enable("jdk.ObjectAllocationSample").with("throttle","50/s");
        }
        recording.disable("mcopt.MetalGpuPass");
        recording.setDestination(output.resolve("recording.jfr"));recording.setDumpOnExit(true);recording.start();
    }
    private static JsonObject setup(Minecraft mc) {
        JsonObject o=new JsonObject();
        o.addProperty("routeSha256",routeHash);o.addProperty("clock","wall-clock interpolation, not keyboard/physics playback");
        o.addProperty("framebufferWidth",mc.getWindow().getWidth());o.addProperty("framebufferHeight",mc.getWindow().getHeight());
        o.addProperty("mainTargetWidth",mc.gameRenderer.mainRenderTarget().width);o.addProperty("mainTargetHeight",mc.gameRenderer.mainRenderTarget().height);
        o.addProperty("worldTargetWidth",worldTargetWidth);o.addProperty("worldTargetHeight",worldTargetHeight);
        o.addProperty("renderDistance",mc.options.getEffectiveRenderDistance());o.addProperty("vsync",mc.options.enableVsync().get());
        o.addProperty("maxFps",mc.options.framerateLimit().get());o.addProperty("maxHeapBytes",Runtime.getRuntime().maxMemory());
        o.addProperty("java",System.getProperty("java.version"));o.addProperty("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        o.addProperty("firstUseTracing",firstUseTracing);
        o.addProperty("naturalCaves",naturalCaves);
        o.addProperty("reloadBeforeWarmup",reloadBeforeWarmup);
        o.addProperty("resourceReloadCompleted",reloadFuture!=null && reloadFuture.isDone() && !reloadFuture.isCompletedExceptionally());
        o.addProperty("fallingBlockPreload",Boolean.parseBoolean(System.getProperty("mcopt.preload.fallingBlocks","true")));
        o.addProperty("starPreload",Boolean.parseBoolean(System.getProperty("atmosphericsfixes.starPreload","true")));
        try {o.addProperty("gpu",RenderSystem.getDevice().getDeviceInfo().name());o.addProperty("backend",RenderSystem.getDevice().getDeviceInfo().backendName());}
        catch(Exception ignored) { }
        var mods=new TreeMap<String,String>();FabricLoader.getInstance().getAllMods().forEach(m->mods.put(m.getMetadata().getId(),m.getMetadata().getVersion().getFriendlyString()));
        o.add("mods",JSON.toJsonTree(mods));return o;
    }
    private static void fail(Minecraft mc,Exception error) {
        System.err.println("[mcopt-replay] aborted: "+error);error.printStackTrace();
        if(output!=null)finish(mc,error.toString());else {finished=true;mc.stop();}
    }
    private static void finish(Minecraft mc,String error) {
        if(finished)return;finished=true;
        try {
            if(starsEnabled!=null)starsEnabled.setBoolean(starSettings,originalStarsEnabled);
            if(marker!=null) {marker.end();marker.commit();}
            if(recording!=null) {recording.stop();recording.close();}
            JsonObject report=new JsonObject();report.addProperty("schema",1);report.addProperty("completed",error==null);
            if(error!=null)report.addProperty("error",error);
            if(setup!=null) {setup.addProperty("stableDuringMeasurement",stableSetup);setup.addProperty("minMeasuredRenderDistance",minRenderDistance);setup.addProperty("maxMeasuredRenderDistance",maxRenderDistance);}
            try {report.add("firstRenderPreload",JSON.toJsonTree(Class.forName("mcopt.metal.Preload").getMethod("priorityReport").invoke(null)));}
            catch(ClassNotFoundException | NoSuchMethodException olderRenderer){}
            try {report.addProperty("clientPacketYieldedFrames",(long)Class.forName("mcopt.metal.cpu.PacketWorkBudget").getMethod("yieldedFrames").invoke(null));}
            catch(ClassNotFoundException | NoSuchMethodException olderRenderer){}
            report.add("setup",setup);report.addProperty("frameDefinition","Minecraft.runTick start-to-start; intervals belong to the preceding phase; not scanout FPS");
            JsonArray summaries=new JsonArray();
            for(int p=0;p<segments.size();p++) {
                long[] selected=new long[frames];int count=0,unfocused=0;double maxError=0;
                for(int i=0;i<frames;i++)if(phases[i]==p){selected[count++]=durations[i];if(!focused[i])unfocused++;maxError=Math.max(maxError,errors[i]);}
                selected=Arrays.copyOf(selected,count);Arrays.sort(selected);
                JsonObject s=new JsonObject();s.addProperty("phase",segments.get(p).name);s.addProperty("frames",count);s.addProperty("unfocusedFrames",unfocused);s.addProperty("maxPositionError",maxError);
                if(count>0) {
                    s.addProperty("p50Ms",selected[(int)Math.ceil(count*.50)-1]/1e6);s.addProperty("p95Ms",selected[(int)Math.ceil(count*.95)-1]/1e6);
                    s.addProperty("p99Ms",selected[(int)Math.ceil(count*.99)-1]/1e6);s.addProperty("maxMs",selected[count-1]/1e6);
                    int over=0;for(long n:selected)if(n>50_000_000)over++;s.addProperty("gapsOver50ms",over);
                }
                summaries.add(s);
            }
            report.add("phases",summaries);
            if(terrainChanges)report.add("terrainChanges",JSON.toJsonTree(TerrainChanges.report()));
            if(gameplayEffects)report.add("gameplayEffects",JSON.toJsonTree(GameplayEffects.report()));
            if(starTransition)report.add("starPipeline",JSON.toJsonTree(Map.of("draws",starPipelineDraws,
                "runtimeGateOpened",starGateOpened,
                "firstMeasuredSeconds",firstStarPipelineNanos==0?-1:(firstStarPipelineNanos-measureAt)/1e9)));
            try(var w=Files.newBufferedWriter(output.resolve("frames.csv"))) {
                w.write("phase,elapsedSeconds,frameMs,x,y,z,positionError,focused\n");
                for(int i=0;i<frames;i++)w.write(String.format(Locale.ROOT,"%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.9f,%s%n",
                    segments.get(phases[i]).name,times[i]/1e9,durations[i]/1e6,xs[i],ys[i],zs[i],errors[i],focused[i]));
            }
            Files.writeString(output.resolve("report.json"),JSON.toJson(report)+"\n");
            System.out.println("[mcopt-replay] "+(error==null?"completed":"failed")+" "+frames+" frames; "+output);
        } catch(Exception e) {System.err.println("[mcopt-replay] report failed: "+e);}
        mc.stop();
    }
}
