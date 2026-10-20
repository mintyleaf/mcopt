package mcopt.metal.lod;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Region indexing, bounded planning, ticket lifetime and admission independent of a running game. */
public final class LodRefinementChecks {
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var gate = new LodRefineGate<Integer>();
		var releases = new AtomicInteger();
		var future = new CompletableFuture<Integer>();
		gate.start(future, releases::incrementAndGet);
		gate.cancel(); gate.cancel();
		if (!gate.busy() || gate.done() || releases.get() != 1) throw new AssertionError("Cancelled generation lost ownership or ticket");
		try { gate.start(new CompletableFuture<>(), releases::incrementAndGet); throw new AssertionError("Unsettled generation duplicated"); }
		catch (IllegalStateException expected) { }
		future.complete(42);
		if (!gate.done() || gate.result() != 42 || !gate.cancelled()) throw new AssertionError("Cancelled future did not settle");
		gate.finish();
		if (gate.busy() || releases.get() != 1) throw new AssertionError("Ticket released twice");
		gate.start(CompletableFuture.failedFuture(new IllegalArgumentException("fixture")), releases::incrementAndGet);
		try { gate.result(); throw new AssertionError("Failure lost"); }
		catch (java.util.concurrent.CompletionException expected) { }
		finally { gate.finish(); }
		gate.start(CompletableFuture.completedFuture(7), releases::incrementAndGet);
		if (gate.result() != 7 || gate.cancelled()) throw new AssertionError("Cancellation leaked into next job");
		gate.finish();
		if (releases.get() != 3) throw new AssertionError("Failure/success leaked ticket");
		var plan = new LodRefinePlan(8);
		var seen = new HashSet<LodRefinePlan.Offset>();
		LodRefinePlan.Offset p;
		double score = -1;
		while ((p = plan.next(-3, 5, 1, 0)) != null) {
			double next = LodRefinePlan.score(p.x(),p.z(),1,0);
			if (p.x()*p.x()+p.z()*p.z()>64 || !seen.add(p) || next < score) throw new AssertionError("Planner duplicates, escapes radius or loses priority");
			score = next;
		}
		if (seen.size() != 197 || !plan.next(10,10,0,1).equals(new LodRefinePlan.Offset(0,0))) throw new AssertionError("Planner reset/coverage");
		for (int x=0;x<20;x++) {
			p = plan.next(x,0,1,0,4);
			if (p == null || (Math.abs(p.x())<=4 && Math.abs(p.z())<=4)) throw new AssertionError("Flight starves outside-render refinement");
		}
		long gib = 1L << 30;
		checkPacing(gib);
		var ticket = LodRefinement.newTicket();
		if (!ticket.doesLoad() || ticket.doesSimulate() || ticket.persist() || ticket.canExpireIfUnloaded()) throw new AssertionError("Refinement ticket changes simulation/persistence");
		// Vanilla matches ticket types by identity: a previous field's delayed cleanup must not cancel its replacement.
		var replacement = LodRefinement.newTicket();
		var storage = new net.minecraft.world.level.TicketStorage();
		var chunk = new net.minecraft.world.level.ChunkPos(20,20);
		storage.addTicketWithRadius(ticket,chunk,0); storage.addTicketWithRadius(replacement,chunk,0);
		storage.removeTicketWithRadius(ticket,chunk,0);
		if (storage.getTickets(chunk.pack()).size()!=1 || storage.getTickets(chunk.pack()).getFirst().getType()!=replacement)
			throw new AssertionError("Previous refinement cancelled replacement ticket");
		Path root = Files.createTempDirectory("mcopt-region-index-");
		try {
			ByteBuffer header = ByteBuffer.allocate(4096);
			header.putInt((31+31*32)*4, 0x201); // -1,-1
			header.putInt((30+31*32)*4, 0x301); // -2,-1
			header.putInt((29+31*32)*4, 0x100); // invalid offset/empty sector
			Files.write(root.resolve("r.-1.-1.mca"),header.array());
			Files.write(root.resolve("r.0.0.mca"),new byte[8]); // truncated
			Files.write(root.resolve("r.not.a.region.mca"),header.array());
			long[] index = LodSavedIndex.scan(root,0,-1,2,-1,0);
			if (index.length!=2 || index[0]!=LodForest.chunkKey(-1,-1) || index[1]!=LodForest.chunkKey(-2,-1)) throw new AssertionError("Negative region/radius indexing");
			if (LodSavedIndex.scan(root.resolve("missing"),0,0,128,1,0).length!=0 || Files.exists(root.resolve("missing"))) throw new AssertionError("Missing region created");
			if (LodSavedIndex.scan(root,0,-1,1,1,0).length!=1 || Files.size(root.resolve("r.-1.-1.mca"))!=4096) throw new AssertionError("Read-only bounds");
		} finally { try (var paths=Files.walk(root)) { for (Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
		System.out.println("PASS: read-only region headers, negative coordinates, directional bounded plan, loading-only tickets, cancellation/error cleanup and frame/tick/heap admission");
	}

	private static void checkPacing(long gib) {
		var budget = new LodRefineBudget(LodWorkload.BALANCED, 0);
		long now = 1_000_000_000L;
		budget.sample(now,16,20,gib,4*gib,gib);
		if (budget.fullLimit(now)!=1 || budget.probeLimit(now)!=1 || !budget.importReady(now)) throw new AssertionError("Healthy initial work blocked");
		budget.started(now,false);
		if (budget.generationReady(now+1,0,12) || !budget.probeReady(now,0,12)) throw new AssertionError("FULL pacing blocks probes or admits catch-up burst");
		budget.started(now,true);
		for (int i=1;i<=20;i++) {now+=50_000_000L;budget.sample(now,16,20,gib,4*gib,gib);}
		if (budget.fullLimit(now)!=2 || budget.probeLimit(now)!=2 || !budget.generationReady(now,0,12)) throw new AssertionError("Healthy work did not ramp to independent bounded lanes");
		budget.imported();budget.imported();
		if (budget.importReady(now)) throw new AssertionError("Import burst exceeds preset");
		budget.sample(now+1_000_000L,16,20,gib,4*gib,gib);
		if (budget.importReady(now+1_000_000L)) throw new AssertionError("Catch-up ticks refilled imports too fast");
		now+=50_000_000L;budget.sample(now,16,20,gib,4*gib,gib);
		if (!budget.importReady(now)) throw new AssertionError("Imports starved at normal tick cadence");
		int limit=LodRefineBudget.chunkLimit(12);
		if (budget.generationReady(now,limit+1,12) || !budget.generationReady(now,limit,12)) throw new AssertionError("Loaded-chunk pressure ignored");
		// Temporary garbage above the old 80% gate must not impose a multi-second stop.
		now+=50_000_000L;budget.sample(now,16,20,(long)(3.3*gib),4*gib,gib);
		if (!budget.ready(now)) throw new AssertionError("Temporary pre-GC occupancy blocks generation");
		now+=50_000_000L;budget.sample(now,16,20,(long)(3.6*gib),4*gib,gib);
		if (!budget.ready(now) || budget.fullLimit(now)!=1) throw new AssertionError("Transient high heap should reduce concurrency");
		now+=50_000_000L;budget.sample(now,16,20,(long)(3.85*gib),4*gib,gib);
		if (budget.ready(now) || budget.importReady(now)) throw new AssertionError("Near-limit heap not stopped");
		now+=50_000_000L;budget.sample(now,16,20,gib,4*gib,gib);
		if (!budget.ready(now)) throw new AssertionError("Healthy post-GC heap retained arbitrary cooldown");
		now+=50_000_000L;budget.sample(now,16,20,(long)(3.6*gib),4*gib,(long)(3.5*gib));
		if (budget.ready(now) || budget.importReady(now)) throw new AssertionError("Retained heap pressure ignored");
		now+=50_000_000L;budget.sample(now,36,20,gib,4*gib,gib);
		if (budget.ready(now) || !budget.importsAllowed(now)) throw new AssertionError("Cheap imports did not survive mild generation pressure");
		now+=50_000_000L;budget.sample(now,46,20,gib,4*gib,gib);
		if (budget.importsAllowed(now)) throw new AssertionError("Severe frame pressure did not stop imports");
		now+=50_000_000L;budget.sample(now,16,20,gib,4*gib,gib);
		if (!budget.ready(now) || budget.fullLimit(now)!=1) throw new AssertionError("Pressure recovery should resume immediately at a reduced window");
		now+=300_000_000L;budget.sample(now,16,20,gib,4*gib,gib);
		if (budget.ready(now)) throw new AssertionError("Stall ignored");
		for(int i=0;i<4;i++){now+=50_000_000L;budget.sample(now,16,20,gib,4*gib,gib);}
		if (!budget.ready(now)) throw new AssertionError("Brief stall imposed old five-second delay");
		budget.idle();now+=30_000_000_000L;budget.sample(now,16,20,gib,4*gib,gib);
		if (!budget.ready(now)) throw new AssertionError("Normal pause treated as a stall");
		var spaced=new LodRefineBudget(LodWorkload.HIGH,1000);
		spaced.sample(now,16,20,gib,4*gib,gib);spaced.started(now,false);
		if (spaced.generationReady(now+999_000_000L,0,12) || !spaced.generationReady(now+1_000_000_000L,0,12) || !spaced.probeReady(now,0,12)) throw new AssertionError("Explicit FULL start spacing or independent probe lane lost");
		for(var workload:LodWorkload.values()) {
			var b=new LodRefineBudget(workload,0);long t=1_000_000_000L;
			for(int i=0;i<200;i++){b.sample(t,16,20,gib,4*gib,-1);t+=50_000_000L;}
			if (b.fullLimit(t)!=workload.full || b.probeLimit(t)!=workload.probes) throw new AssertionError("Preset cap/ramp mismatch");
		}
		if (LodRefinement.effectiveRadius(128,32,false)!=32 || LodRefinement.effectiveRadius(512,512,true)!=64
			|| LodRefinement.effectiveRadius(128,512,false)!=128) throw new AssertionError("Saved imports exceed visible reach/dimension cap");
		long retained=new LodHeap().retained(System.nanoTime());
		if (retained < -1) throw new AssertionError("Invalid GC-retained sample");
		System.out.println("PASS: adaptive preset caps, independent FULL/probe pacing, bounded imports, GC/transient heap distinction, short stall recovery, pause handling and visible import radius");
	}
}
