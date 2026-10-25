package mcopt.probe;

import jdk.jfr.*;
import net.fabricmc.api.ClientModInitializer;

/** Passive, separately packaged diagnostics. No routes, world edits, resource preload or owned caches. */
public final class GameplayProbe implements ClientModInitializer {
    @Name("mcopt.GameplayWork") @Label("Slow gameplay resource or subsystem") @Category("mcopt")
    @Enabled(false) @StackTrace(true) @Threshold("2 ms")
    public static final class Work extends Event {
        public String operation, subject, implementation;
    }
    @Name("mcopt.GameplayAction") @Label("Gameplay input context") @Category("mcopt")
    @Enabled(false) @StackTrace(false)
    public static final class Action extends Event { public String operation; }

    private static final EventType WORK = EventType.getEventType(Work.class);
    private static final EventType ACTION = EventType.getEventType(Action.class);

    @Override public void onInitializeClient() {
        // Resolve/register diagnostic classes before entering the world, not at the first hitch.
        new Work();
        new Action();
    }
    public static boolean enabled() { return WORK.isEnabled(); }
    public static Work begin(String operation) {
        if (!enabled()) return null;
        Work event = new Work();
        event.operation = operation;
        event.begin();
        return event;
    }
    /** End before computing identifiers; wrappers only stringify metadata for retained events. */
    public static boolean end(Work event) {
        if (event == null) return false;
        event.end();
        return event.shouldCommit();
    }
    public static void action(String operation) {
        if (!ACTION.isEnabled()) return;
        Action event = new Action();
        event.operation = operation;
        event.commit();
    }
}
