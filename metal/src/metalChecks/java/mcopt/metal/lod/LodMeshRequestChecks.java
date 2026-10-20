package mcopt.metal.lod;

/** Exploration must not retain versions after consumption or multiply queue entries for reused slots. */
public final class LodMeshRequestChecks {
	public static void main(String[] args) throws Exception {
		var requests = new LodMeshRequests(2, 4);
		for (int n = 0; n < 100_000; n++) {
			requests.offer(0, n - 50_000, 0, n, n * 2L);
			if (requests.size() > 4) throw new AssertionError("Exploration grows request metadata");
		}
		for (int n = 99_996; n < 100_000; n++) {
			var r = requests.take();
			if (r.sequence() != n || r.version() != n * 2L || r.key() != LodTile.key(0,n-50_000,0))
				throw new AssertionError("A newer slot request was lost: " + r);
		}
		if (requests.size() != 0) throw new AssertionError("Consumed versions retained");
		requests.offer(1,-1,-1,1,2); requests.cancel(1,15); requests.offer(1,3,3,3,4);
		if (requests.size() != 1 || requests.take().sequence() != 3) throw new AssertionError("Evicted slot queue entry duplicated");
		requests.offer(0,0,0,1,1); var active=requests.take(); requests.offer(0,0,0,2,2);
		if (active.version()!=1 || requests.take().version()!=2) throw new AssertionError("Active request lost immutable version");
		if (requests.retry(active)) throw new AssertionError("Old worker replaced a newer request");
		requests.offer(0,0,0,3,3); active=requests.take(); requests.cancel(0,0);
		if (requests.retry(active)) throw new AssertionError("Old worker resurrected an evicted slot");
		var worker = Thread.ofPlatform().start(() -> { try { requests.take(); throw new AssertionError("Unexpected request"); }
			catch (InterruptedException expected) { } });
		worker.interrupt(); worker.join(2_000);
		if (worker.isAlive()) throw new AssertionError("Closing cannot interrupt idle worker");
		System.out.println("PASS: 100,000 explored keys retain only ring-slot requests; consumption, eviction and re-request preserve latest versions");
	}
}
