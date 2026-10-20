package mcopt.metal.lod;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.util.Set;
import java.util.stream.Collectors;

/** Sample GC-retained heap without requesting collection or registering a notification thread. */
final class LodHeap {
	private final java.util.List<java.lang.management.GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
	private final Set<String> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
		.filter(p -> p.getType() == MemoryType.HEAP).map(java.lang.management.MemoryPoolMXBean::getName).collect(Collectors.toSet());
	private long nextSample, lastCollection = -1, retained = -1;
	long retained(long now) {
		if (now < nextSample) return retained;
		nextSample = now + 500_000_000L;
		for (var collector : collectors) {
			if (!(collector instanceof com.sun.management.GarbageCollectorMXBean extended)) continue;
			var info = extended.getLastGcInfo();
			if (info == null || info.getEndTime() <= lastCollection) continue;
			long used = 0; boolean known = false;
			for (var entry : info.getMemoryUsageAfterGc().entrySet()) if (heapPools.contains(entry.getKey())) {
				used += entry.getValue().getUsed(); known = true;
			}
			if (known) { retained = used; lastCollection = info.getEndTime(); }
		}
		return retained;
	}
}
