package mcopt.metal.lod;

import jdk.jfr.*;

/** One opt-in sample per second; captures chunk backlog without requiring the F3 overlay or per-frame disk writes. */
final class LodScheduling {
	private static final boolean ENABLED = Boolean.getBoolean("mcopt.metal.jfr");
	private static final EventType TYPE = EventType.getEventType(Sample.class);
	private static long nextSample;

	@Name("mcopt.LodScheduling")
	@Label("LOD generation admission")
	@Category("mcopt")
	@StackTrace(false)
	static final class Sample extends Event {
		String dimension;
		int missingChunks, totalChunks, nearMissingChunks, coarseRunning, fineRunning, waitingWorkers;
		boolean generationPressure, populationPressure, memoryPressure;
		long heapUsedBytes, heapMaxBytes;
		int meshCompileQueued, lodTilesMissing, lodTilesNeeded, lodJobsPending, lodJobsQueued, lodResultsReady;
		long lodTilesGenerated, lodTilesLoaded;
		boolean severeChunkPressure, streamingAllowed;
		long streamingStarts, lodStaleJobsDropped;
		int lodCacheChecksQueued, lodFallbackQueued, lodVisibleQueued, lodDeferredQueued;
		double cameraX, cameraY, cameraZ;
	}

	static void sample(long now, net.minecraft.resources.ResourceKey<?> dimension, LodGenStats.Coverage coverage, LodGenerationBudget budget,
		boolean generationPressure, boolean populationPressure, int waiting,
		net.minecraft.client.Minecraft mc, LodField field, double cameraX, double cameraY, double cameraZ) {
		if (!ENABLED || now < nextSample || !TYPE.isEnabled()) return;
		nextSample = now + 1_000_000_000L;
		var e = new Sample();
		e.dimension = dimension.identifier().toString();
		e.missingChunks = coverage.missing(); e.totalChunks = coverage.total(); e.nearMissingChunks = coverage.nearMissing();
		e.coarseRunning = budget.coarse(); e.fineRunning = budget.fine(); e.waitingWorkers = waiting;
		e.generationPressure = generationPressure; e.populationPressure = populationPressure;
		e.memoryPressure = LodMemoryBudget.pressured();
		Runtime runtime = Runtime.getRuntime();
		e.heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(); e.heapMaxBytes = runtime.maxMemory();
		// Vanilla's queue-size getter is an approximate, lock-free read. It includes rebuild/resort tasks,
		// not running compiles or completed uploads: a diagnostic backlog, not an exact count of invisible chunks.
		var dispatcher = mc == null || mc.levelRenderer == null ? null : mc.levelRenderer.sectionRenderDispatcher();
		e.meshCompileQueued = dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
		e.cameraX = cameraX; e.cameraY = cameraY; e.cameraZ = cameraZ;
		e.lodTilesMissing = field == null ? -1 : field.missing;
		e.lodTilesNeeded = field == null ? -1 : field.needed;
		e.lodJobsPending = field == null ? -1 : field.pendingJobs();
		e.lodJobsQueued = field == null ? -1 : field.queued();
		e.lodResultsReady = field == null ? -1 : field.pendingResults();
		e.lodTilesGenerated = field == null ? -1 : field.generated.get();
		e.lodTilesLoaded = field == null ? -1 : field.loaded.get();
		e.severeChunkPressure = LodYield.severeChunkPressure; e.streamingAllowed = LodYield.streamingAllowed();
		e.streamingStarts = LodYield.streamingStarts.get();
		e.lodStaleJobsDropped = field == null ? -1 : field.staleJobsDropped.get();
		e.lodCacheChecksQueued = field == null ? -1 : field.queuedLane(LodJobQueue.CACHE);
		e.lodFallbackQueued = field == null ? -1 : field.queuedLane(LodJobQueue.COARSE);
		e.lodVisibleQueued = field == null ? -1 : field.queuedLane(LodJobQueue.VISIBLE);
		e.lodDeferredQueued = field == null ? -1 : field.queuedLane(LodJobQueue.OTHER);
		e.commit();
	}
}
