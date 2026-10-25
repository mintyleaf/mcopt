package mcopt.metal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Loads, on background threads, the classes Minecraft is about to load one by one on its main thread during boot and
 * world load, in the order it loads them, so reading, Mixin-transforming and defining them overlaps the main thread's
 * own work instead of stalling it. Loading only (no static initializers run), so nothing about the game changes: the
 * same classes end up loaded, only sooner and off the main thread. The list (resources/mcopt/metal/preload.txt.gz)
 * comes from a -Xlog:class+load run of the shipping stack (tools/preloadlist.py); names that don't exist in some
 * other mod set are skipped. A small first-render list precedes the general boot list;
 * -Dmcopt.preload.firstRender=false disables the priority lists;
 * -Dmcopt.preload.gameplay=false disables only the measured gameplay/render helpers.
 * -Dmcopt.preload.fallingBlocks=false / -Dmcopt.preload.caves=false disable their measured class subgroups.
 * -Dmcopt.preload=false turns all preloading off, -Dmcopt.preload.threads=N sets the thread count.
 */
public final class Preload implements PreLaunchEntrypoint {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.preload", "true"));
	private static final int THREADS = Integer.getInteger("mcopt.preload.threads", 2);
	private static final AtomicInteger priorityResolved = new AtomicInteger(), priorityUnavailable = new AtomicInteger();
	private static volatile int priorityRequested;
	public static java.util.Map<String,Integer> priorityReport() {
		return java.util.Map.of("requested",priorityRequested,"resolved",priorityResolved.get(),"unavailable",priorityUnavailable.get());
	}

	@Override
	public void onPreLaunch() {
		PlatformCheck.run();
		if (!ENABLED) return;
		List<String> names = new ArrayList<>();
		try (InputStream in = Preload.class.getResourceAsStream("/mcopt/metal/preload.txt.gz")) {
			if (in == null) return;
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
			for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) names.add(line);
		} catch (IOException e) {
			return;
		}
		List<String> priority = new ArrayList<>();
		if (Boolean.parseBoolean(System.getProperty("mcopt.preload.firstRender", "true"))) {
			appendPriority("/mcopt/metal/preload-first-render.txt", priority);
			if (Boolean.parseBoolean(System.getProperty("mcopt.preload.gameplay", "true"))) {
				appendPriority("/mcopt/metal/preload-gameplay.txt", priority);
				if (Boolean.parseBoolean(System.getProperty("mcopt.preload.fallingBlocks", "true")))
					appendPriority("/mcopt/metal/preload-falling-blocks.txt", priority);
				if (Boolean.parseBoolean(System.getProperty("mcopt.preload.caves", "true")))
					appendPriority("/mcopt/metal/preload-caves.txt", priority);
			}
		}
		priorityRequested = priority.size();
		names.addAll(0, priority);
		ClassLoader loader = Preload.class.getClassLoader();
		AtomicInteger next = new AtomicInteger();
		for (int t = 0; t < THREADS; t++) {
			Thread thread = new Thread(() -> {
				for (int i; (i = next.getAndIncrement()) < names.size(); ) {
					try {
						Class.forName(names.get(i), false, loader);
						if (i < priorityRequested) priorityResolved.incrementAndGet();
					} catch (Throwable absent) {
						// Not in this mod set or environment: the game wouldn't load it either.
						if (i < priorityRequested) priorityUnavailable.incrementAndGet();
					}
				}
			}, "mcopt class preload #" + t);
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void appendPriority(String resource, List<String> priority) {
		try (InputStream in = Preload.class.getResourceAsStream(resource)) {
			if (in == null) return;
			var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = reader.readLine()) != null; )
				if (!line.isBlank() && !line.startsWith("#")) priority.add(line);
		} catch (IOException ignored) { }
	}
}
