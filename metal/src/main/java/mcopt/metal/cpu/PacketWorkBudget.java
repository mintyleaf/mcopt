package mcopt.metal.cpu;

/** Client frame scope only. A yield leaves the original FIFO queue untouched; one whole packet always progresses. */
public final class PacketWorkBudget {
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    private static volatile long yieldedFrames;
    public static long yieldedFrames(){return yieldedFrames;}
    public static final class Scope {
        final long start,nanos;
        int handled;
        boolean yielded;
        Scope(long start,long nanos){this.start=start;this.nanos=nanos;}
    }
    private PacketWorkBudget() { }
    public static Scope begin(long nanos){return begin(System.nanoTime(),nanos);}
    static Scope begin(long now,long nanos) {
        if(nanos<=0 || ACTIVE.get()!=null)return null;
        Scope scope=new Scope(now,nanos);ACTIVE.set(scope);return scope;
    }
    public static void end(Scope scope){if(scope!=null && ACTIVE.get()==scope){if(scope.yielded)yieldedFrames++;ACTIVE.remove();}}
    public static void handled(){Scope scope=ACTIVE.get();if(scope!=null)scope.handled++;}
    public static boolean exhausted(boolean empty){return exhaustedAt(empty,System.nanoTime());}
    static boolean exhaustedAt(boolean empty,long now) {
        Scope scope=ACTIVE.get();
        if(empty)return true;
        boolean stop=scope!=null && scope.handled>0 && now-scope.start>=scope.nanos;
        if(stop)scope.yielded=true;
        return stop;
    }
}
