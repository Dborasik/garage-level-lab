package dev.radixen.garagelevel;

import dev.radixen.garagelevel.model.TelemetryState;

public final class AppState {
    private static final AppState INSTANCE = new AppState();
    private TelemetryState state = new TelemetryState();

    private AppState() {}

    public static AppState get() {
        return INSTANCE;
    }

    public synchronized void publish(TelemetryState newState) {
        state = newState.copy();
    }

    public synchronized TelemetryState snapshot() {
        return state.copy();
    }
}
