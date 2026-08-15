package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable, credential-free summaries of the 50 most recent transfers. */
public final class BackupHistoryRepository {
    private static final String PREFS = "private_cloud_history_v1";
    private final SharedPreferences values;

    public BackupHistoryRepository(Context context) {
        values = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void record(String operation, boolean success, String detail) {
        if (!values.getBoolean("recording_enabled", true)) return;
        SharedPreferences.Editor editor = values.edit();
        for (int index = 49; index > 0; index--) {
            String previous = values.getString("entry." + (index - 1), null);
            if (previous == null) editor.remove("entry." + index);
            else editor.putString("entry." + index, previous);
        }
        editor.putString("entry.0", encode(new Entry(
                        System.currentTimeMillis(), safe(operation), success, truncate(detail))))
                .putLong("finished_at", System.currentTimeMillis())
                .putString("operation", safe(operation))
                .putBoolean("success", success)
                .putString("detail", truncate(detail))
                .commit();
    }

    public Entry loadLatest() {
        List<Entry> entries = loadRecent(1);
        if (!entries.isEmpty()) return entries.get(0);
        long finishedAt = values.getLong("finished_at", 0L);
        if (finishedAt == 0L) return null;
        return new Entry(
                finishedAt,
                values.getString("operation", ""),
                values.getBoolean("success", false),
                values.getString("detail", ""));
    }

    public List<Entry> loadRecent(int maximum) {
        if (maximum < 1 || maximum > 50) throw new IllegalArgumentException("maximum must be 1..50");
        ArrayList<Entry> result = new ArrayList<Entry>();
        for (int index = 0; index < maximum; index++) {
            String encoded = values.getString("entry." + index, null);
            if (encoded == null) break;
            Entry entry = decode(encoded);
            if (entry != null) result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    public boolean clear() { return values.edit().clear().commit(); }

    public boolean clearAndDisable() {
        return values.edit().clear().putBoolean("recording_enabled", false).commit();
    }

    public boolean enableRecording() {
        return values.edit().putBoolean("recording_enabled", true).commit();
    }

    private static String truncate(String value) {
        String safe = safe(value);
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String encode(Entry entry) {
        try {
            return new JSONObject().put("time", entry.finishedAt)
                    .put("operation", entry.operation).put("success", entry.success)
                    .put("detail", entry.detail).toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Entry decode(String encoded) {
        try {
            JSONObject value = new JSONObject(encoded);
            return new Entry(value.getLong("time"), value.getString("operation"),
                    value.getBoolean("success"), value.getString("detail"));
        } catch (JSONException damaged) { return null; }
    }

    public static final class Entry {
        public final long finishedAt;
        public final String operation;
        public final boolean success;
        public final String detail;

        Entry(long finishedAt, String operation, boolean success, String detail) {
            this.finishedAt = finishedAt;
            this.operation = operation;
            this.success = success;
            this.detail = detail;
        }
    }
}
