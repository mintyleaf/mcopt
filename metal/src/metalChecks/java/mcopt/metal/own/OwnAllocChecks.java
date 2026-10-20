package mcopt.metal.own;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Reserve isolation under a held shared lock, accounting, and concurrent best-fit/free/growth correctness. */
public final class OwnAllocChecks {
	public static void main(String[] args) throws Exception {
		System.setProperty("mcopt.metal.jfr","true");
		var owner = new AtomicReference<Thread>();
		var render = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"allocator-render"); owner.set(t); return t; });
		var held = new CountDownLatch(1); var release = new CountDownLatch(1);
		var alloc = new OwnAlloc(256);
		if (alloc.alloc(8) != 0) throw new AssertionError("Required prefix moved");
		render.submit(() -> {}).get(); alloc.reserveFor(owner.get(),64);
		int sharedToFree=alloc.alloc(80);
		Thread worker = new Thread(() -> { try (var ignored = alloc.locked()) {
			held.countDown(); try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { throw new AssertionError(e); }
		}}, "allocator-blocked-worker");
		try {
			worker.start(); if (!held.await(2,TimeUnit.SECONDS)) throw new AssertionError("Worker failed to acquire monitor");
			int start = render.submit(() -> {
				int a=alloc.alloc(24); alloc.free(a,24); return alloc.alloc(48);
			}).get(2,TimeUnit.SECONDS);
			if (start!=8 || release.getCount()!=1 || alloc.reserveUsed()!=48) throw new AssertionError("Render allocation did not use independent reserve");
			// Freeing a reserve range is also independent of the held shared lock.
			render.submit(() -> alloc.free(start,48)).get(2,TimeUnit.SECONDS);
			render.submit(() -> alloc.free(sharedToFree,80)).get(2,TimeUnit.SECONDS);
			release.countDown(); worker.join(2000);
			if (alloc.used()!=8 || alloc.capacity()!=256) throw new AssertionError("Idle reserve counted as live data or increased capacity");
			int fallback = render.submit(() -> alloc.alloc(80)).get();
			if (fallback!=72 || alloc.reserveMisses()!=1) throw new AssertionError("Reserve overflow lost shared fallback");
			alloc.free(fallback,80); alloc.grow(512);
			int all = alloc.alloc(440);
			if (all!=72 || alloc.used()!=448) throw new AssertionError("Shared tail did not coalesce after growth");
			alloc.free(all,440); alloc.free(0,8);
			if (alloc.used()!=0) throw new AssertionError("Prefix or fallback leaked");
			telemetry(alloc,args);
		} finally { release.countDown(); worker.join(2000); render.shutdownNow(); }
		stress();
		System.out.println("PASS: render reserve allocation and retired local/shared frees bypass a held worker lock; prefix, fallback, coalescing, growth and live accounting preserved");
	}

	private static void telemetry(OwnAlloc alloc,String[] args) throws Exception {
		var output=args.length==0?java.nio.file.Files.createTempFile("mcopt-alloc-reserve-",".jfr"):java.nio.file.Path.of(args[0]);
		try(var recording=new jdk.jfr.Recording()) {
			recording.enable("mcopt.OwnArenaAllocation");recording.start();
			long next=OwnGrowth.allocation(100,0,alloc,0);
			if(OwnGrowth.allocation(101,next,alloc,0)!=next) throw new AssertionError("Reserve sampling not throttled");
			recording.stop();recording.dump(output);
		}
		var events=jdk.jfr.consumer.RecordingFile.readAllEvents(output).stream().filter(e->e.getEventType().getName().equals("mcopt.OwnArenaAllocation")).toList();
		if(events.size()!=1 || events.getFirst().getLong("reserveBytes")!=64L*OwnTerrain.ARENA_QUAD
			|| events.getFirst().getLong("reserveUsedBytes")!=0 || events.getFirst().getLong("reserveHits")!=2
			|| events.getFirst().getLong("sharedFallbacks")!=1) throw new AssertionError("Reserve JFR counters changed");
		System.out.println("PASS: actual throttled reserve telemetry and live accounting: "+output);
	}

	private static void stress() throws Exception {
		var alloc = new OwnAlloc(32768); var occupied = new BitSet(32768);
		var failed = new AtomicReference<Throwable>(); var threads = new ArrayList<Thread>();
		for (int id=0;id<6;id++) {
			final int seed=id;
			threads.add(new Thread(() -> {
				var random=new Random(seed); var ranges=new ArrayList<int[]>();
				try {
					for(int i=0;i<4000;i++) {
						if(ranges.size()<48 && (ranges.isEmpty() || random.nextBoolean())) {
							int n=1+random.nextInt(32),start=alloc.alloc(n);
							if(start<0) throw new AssertionError("Unexpected exhaustion");
							synchronized(occupied) {
								int hit=occupied.nextSetBit(start); if(hit>=0 && hit<start+n) throw new AssertionError("Overlapping live allocations");
								occupied.set(start,start+n);
							}
							ranges.add(new int[]{start,n});
						} else {
							int[] r=ranges.remove(random.nextInt(ranges.size()));
							synchronized(occupied) { occupied.clear(r[0],r[0]+r[1]); } alloc.free(r[0],r[1]);
						}
					}
					for(var r:ranges) { synchronized(occupied) { occupied.clear(r[0],r[0]+r[1]); } alloc.free(r[0],r[1]); }
				} catch(Throwable e) { failed.compareAndSet(null,e); }
			},"allocator-stress-"+id));
		}
		alloc.reserveFor(threads.getFirst(),1024);
		for(var thread:threads) thread.start();
		for(var thread:threads) { thread.join(10000); if(thread.isAlive()) throw new AssertionError("Allocator deadlock"); }
		if(failed.get()!=null || !occupied.isEmpty() || alloc.used()!=0) throw new AssertionError("Concurrent allocator corruption",failed.get());
		alloc.grow(65536); int tail=alloc.alloc(64512);
		if(tail!=1024) throw new AssertionError("Free ranges did not fully coalesce after stress/growth");
		alloc.free(tail,64512);
	}
}
