package mcopt.replay;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.management.ManagementFactory;
import jdk.jfr.*;

/** Replay-only ordered queue experiment. No packet is removed by the budget check. */
public final class PacketTrace {
    private static final java.lang.management.ThreadMXBean CPU=ManagementFactory.getThreadMXBean();
    private static Scope current;
    @Name("mcopt.ReplayClientWork") @Label("Replay client work") @Category("mcopt") @StackTrace(false)
    public static final class Work extends Event {
        public String operation;
        public int packets;
        public boolean yielded;
        public double budgetMs,cpuMs;
    }
    public static final class Scope {
        final long start=System.nanoTime(),cpu=CPU.isCurrentThreadCpuTimeSupported()?CPU.getCurrentThreadCpuTime():-1;
        final long budget;
        final Work event=new Work();
        int packets;
        boolean yielded;
        Scope(String operation,long budget){this.budget=budget;event.operation=operation;event.budgetMs=budget/1e6;event.begin();}
    }
    public static Scope beginDrain() {
        if(!active() || current!=null)return null;
        return current=new Scope("packetDrain",Replay.packetBudgetNanos());
    }
    private static boolean active(){return RenderSystem.isOnRenderThread() && Replay.traceClientWork();}
    public static Scope beginWork(String operation){return active()?new Scope(operation,0):null;}
    public static Scope beginLight(){return beginWork("lightUpdates");}
    public static void handled(){if(RenderSystem.isOnRenderThread() && current!=null)current.packets++;}
    public static boolean emptyOrBudget(boolean empty) {
        if(empty || !RenderSystem.isOnRenderThread() || current==null)return empty;
        if(current.budget>0 && current.packets>0 && System.nanoTime()-current.start>=current.budget){current.yielded=true;return true;}
        return false;
    }
    public static void end(Scope scope) {
        if(scope==null)return;
        scope.event.end();scope.event.packets=scope.packets;scope.event.yielded=scope.yielded;
        scope.event.cpuMs=scope.cpu<0?-1:(CPU.getCurrentThreadCpuTime()-scope.cpu)/1e6;
        if(scope.packets>0 || !scope.event.operation.equals("packetDrain"))scope.event.commit();
        if(current==scope)current=null;
    }
}
