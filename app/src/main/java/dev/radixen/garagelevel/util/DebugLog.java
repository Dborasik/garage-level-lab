package dev.radixen.garagelevel.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

public final class DebugLog {
    private final Deque<String> entries = new ArrayDeque<>();
    private final int maxEntries;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public DebugLog(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public synchronized void add(String message) {
        entries.addFirst(timeFormat.format(new Date()) + "  " + message);
        while (entries.size() > maxEntries) entries.removeLast();
    }

    public synchronized String render() {
        if (entries.isEmpty()) return "No events";
        StringBuilder b = new StringBuilder();
        for (String entry : entries) {
            if (b.length() > 0) b.append('\n');
            b.append(entry);
        }
        return b.toString();
    }
}
