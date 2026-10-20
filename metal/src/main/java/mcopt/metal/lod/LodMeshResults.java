package mcopt.metal.lod;

import java.util.LinkedHashMap;
import org.lwjgl.system.MemoryUtil;

/** Completed meshes and retained snapshots share one bounded stage, including deferred publication. */
final class LodMeshResults implements AutoCloseable {
	final class Result implements AutoCloseable {
		final long[] data;
		final LodClip.Snapshot snapshot;
		private boolean released;
		Result(long[] data, LodClip.Snapshot snapshot) { this.data=data;this.snapshot=snapshot; }
		@Override public void close() {
			if(this.released) throw new IllegalStateException("Mesh result released twice");
			this.released=true;
			MemoryUtil.nmemFree(this.data[2]); LodClip.release(this.snapshot);
			release(this.data[3]*4);
		}
	}
	private final long limit;
	private final int countLimit;
	private long bytes;
	private int reservations;
	private boolean closed;
	private final LinkedHashMap<Long,Result> ready=new LinkedHashMap<>();

	LodMeshResults(long limit,int countLimit) {
		if(limit<=0 || countLimit<=0)throw new IllegalArgumentException("Mesh result budget must be positive");
		this.limit=limit;this.countLimit=countLimit;
	}

	/** Only workers wait. Credits are acquired before native allocation, and remain held while a result is deferred. */
	synchronized boolean reserve(long bytes) throws InterruptedException {
		if(bytes>this.limit)throw new IllegalArgumentException("Mesh exceeds completed byte budget");
		while(!this.closed && (this.bytes+bytes>this.limit || this.reservations>=this.countLimit))this.wait();
		if(this.closed)return false;
		this.bytes+=bytes;this.reservations++;return true;
	}

	synchronized void release(long bytes) {
		this.bytes-=bytes;this.reservations--;
		if(this.bytes<0 || this.reservations<0)throw new IllegalStateException("Mesh result credits underflow");
		this.notifyAll();
	}

	synchronized void offer(Result result) {
		Result previous=this.ready.get(result.data[0]);
		if(this.closed || previous!=null && previous.data[1]>result.data[1]) {result.close();return;}
		this.ready.put(result.data[0],result);
		if(previous!=null)previous.close();
	}

	synchronized Result poll() {
		var it=this.ready.entrySet().iterator();
		if(!it.hasNext())return null;
		Result result=it.next().getValue();it.remove();return result;
	}
	synchronized int size(){return this.ready.size();}
	synchronized long bytes(){return this.bytes;}
	synchronized int reservations(){return this.reservations;}
	@Override public synchronized void close() {
		this.closed=true;
		for(Result r:this.ready.values())r.close();
		this.ready.clear();this.notifyAll();
	}
}
