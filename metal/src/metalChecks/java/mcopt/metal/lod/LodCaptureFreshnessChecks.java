package mcopt.metal.lod;

import java.util.concurrent.*;

/** Unload reuse is permitted only for a completed capture whose token survived every subsequent invalidation. */
public final class LodCaptureFreshnessChecks {
	public static void main(String[] args) throws Exception {
		System.setProperty("mcopt.metal.jfr","true");
		var state=new LodCaptureFreshness(); long key=LodForest.chunkKey(-26,21);
		if(state.takeReady(key)) throw new AssertionError("Uncaptured chunk reused");
		var incomplete=state.begin(key);
		if(state.takeReady(key)) throw new AssertionError("Pending capture reused");
		incomplete.ready=true;
		if(state.size()!=0 || state.takeReady(key)) throw new AssertionError("Unloaded callback resurrected a coordinate");
		var old=state.begin(key); var newer=state.begin(key); old.ready=true;
		if(state.takeReady(key)) throw new AssertionError("Old completion made a newer capture reusable");
		var valid=state.begin(key); valid.ready=true;
		if(!state.takeReady(key) || state.takeReady(key) || state.size()!=0) throw new AssertionError("Completed unchanged capture not retired once");
		var edit=state.begin(key); edit.ready=true; state.invalidate(key);
		if(state.takeReady(key)) throw new AssertionError("Edited blocks reused");
		var beforeLight=state.begin(key); var changed=new CountDownLatch(1);
		Thread light=new Thread(() -> { state.invalidate(key); changed.countDown(); },"capture-light-update");
		light.start(); if(!changed.await(2,TimeUnit.SECONDS)) throw new AssertionError("Light update stalled");
		beforeLight.ready=true; light.join();
		if(state.takeReady(key)) throw new AssertionError("Late completion ignored an off-thread light update");
		var previousWorld=state.begin(key); state.clear(); var nextWorld=state.begin(key); previousWorld.ready=true;
		if(state.takeReady(key)) throw new AssertionError("World/reload callback made a new chunk reusable");
		for(int i=0;i<10000;i++) { var token=state.begin(i); token.ready=true; if(!state.takeReady(i)) throw new AssertionError(); }
		if(state.size()!=0) throw new AssertionError("Travel retained unloaded chunk tokens");
		checkEditedReuse();
		var output=args.length==0?java.nio.file.Files.createTempFile("mcopt-capture-span-",".jfr"):java.nio.file.Path.of(args[0]);
		try(var recording=new jdk.jfr.Recording()) {
			recording.enable("mcopt.LodClientCapture").withThreshold(java.time.Duration.ZERO);recording.start();
			var event=LodCaptureQueue.beginCapture(-26,21,"unload");
			if(event==null) throw new AssertionError("Capture timing disabled");
			event.freezeMs=12.5;event.fallback=true;event.end();event.commit();
			recording.stop();recording.dump(output);
		}
		var events=jdk.jfr.consumer.RecordingFile.readAllEvents(output).stream().filter(e->e.getEventType().getName().equals("mcopt.LodClientCapture")).toList();
		if(events.size()!=1 || events.getFirst().getInt("chunkX")!=-26 || !events.getFirst().getString("reason").equals("unload")
			|| events.getFirst().getDouble("freezeMs")!=12.5 || !events.getFirst().getBoolean("fallback")) throw new AssertionError("Capture JFR span changed");
		System.out.println("PASS: real capture timing event preserves coordinates, reason, freeze duration and fallback: "+output);
		System.out.println("PASS: pending/older captures, edits, off-thread light, unload/reload/world reset and late callbacks cannot authorize stale reuse; travel retains no unloaded tokens");
	}
	private static void checkEditedReuse() {
		var state = new LodCaptureFreshness();
		var edits = new ConcurrentHashMap<Long, Long>(); long key = -12345, now = 1_000_000, quiet = 100;
		var captured = state.begin(key); captured.ready = true; edits.put(key, now - quiet);
		if (state.pollEdited(edits, now, quiet) != null || !edits.isEmpty() || state.reusedEdits != 1 || !state.takeReady(key))
			throw new AssertionError("Redundant settled edit copied again or consumed the unload token");
		var pending = state.begin(key); edits.put(key, now - quiet);
		if (!Long.valueOf(key).equals(state.pollEdited(edits, now, quiet)) || state.reusedEdits != 1)
			throw new AssertionError("Incomplete capture suppressed a required edit");
		pending.ready = true; state.invalidate(key); edits.put(key, now - quiet);
		if (!Long.valueOf(key).equals(state.pollEdited(edits, now, quiet))) throw new AssertionError("Edit/light invalidation reused old geometry");
		var valid = state.begin(key); valid.ready = true; edits.put(key, now);
		if (state.pollEdited(edits, now, quiet) != null || !edits.containsKey(key)) throw new AssertionError("Quiet interval was bypassed");
		state.clear();
		if (!Long.valueOf(key).equals(state.pollEdited(edits, now + quiet, quiet))) throw new AssertionError("World reset retained edit reuse");
		var raced = new ConcurrentHashMap<Long, Long>() {
			@Override public boolean remove(Object k, Object v) {
				this.put((Long) k, now); state.invalidate((Long) k); return false;
			}
		};
		state.begin(key).ready = true; raced.put(key, now - quiet);
		if (state.pollEdited(raced, now, quiet) != null || raced.get(key) != now)
			throw new AssertionError("Concurrent newer edit was discarded");
		System.out.println("PASS: settled queued edits covered by a completed current capture are retired; pending, invalidated, recent, world-reset and raced edits still require fresh snapshots");
	}
}
