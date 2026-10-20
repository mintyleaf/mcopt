package mcopt.metal.lod;

import jdk.jfr.*;

/** Background allocation only. Live-span copying and adoption remain on the owner render thread. */
final class LodArenaGrowth implements AutoCloseable {
	@Name("mcopt.LodArenaGrowth") @Label("LOD mesh arena migration") @Category("mcopt") @StackTrace(false)
	static final class Span extends Event {
		String phase;
		long oldBytes,newBytes,copiedBytes;
		boolean success;
	}
	record Prepared(long buffer,long address) { }
	private final Thread worker;
	private volatile Prepared prepared;
	private volatile Throwable failure;
	private boolean adopted;
	final long words;
	int cursor;
	long progress,slotSequence=-1,copied;

	LodArenaGrowth(long ctx,long oldWords,long words) {
		this.words=words;
		this.worker=Thread.ofPlatform().name("mcopt-lod-arena").daemon(true).unstarted(()->{
			Span span=span("prepare",oldWords,words); span.begin();
			long buffer=0;
			try {
				buffer=LodNative.buffer(ctx,words*4);
				if(buffer==0)throw new IllegalStateException("LOD arena allocation failed");
				long address=LodNative.contents(buffer);
				if(address==0)throw new IllegalStateException("LOD arena is not CPU accessible");
				this.prepared=new Prepared(buffer,address);buffer=0;span.success=true;
			} catch(RuntimeException | Error error) { this.failure=error; }
			finally {if(buffer!=0)LodNative.release(buffer);span.end();span.commit();}
		});
		this.worker.setPriority(Thread.NORM_PRIORITY-1);this.worker.start();
	}
	Prepared ready(){return this.prepared;}
	Throwable failure(){return this.failure;}
	void adopt(){this.adopted=true;}
	static Span span(String phase,long oldWords,long words) {
		var event=new Span();event.phase=phase;event.oldBytes=oldWords*4;event.newBytes=words*4;return event;
	}
	@Override public void close() {
		boolean interrupted=false;
		while(this.worker.isAlive())try{this.worker.join();}catch(InterruptedException e){interrupted=true;}
		if(!this.adopted && this.prepared!=null)LodNative.release(this.prepared.buffer);
		this.prepared=null;
		if(interrupted)Thread.currentThread().interrupt();
	}
}
