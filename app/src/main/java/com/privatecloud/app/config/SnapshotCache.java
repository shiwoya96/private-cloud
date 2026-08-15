package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.privatecloud.app.model.SnapshotInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small non-authoritative UI cache; the remote complete marker remains the source of truth. */
public final class SnapshotCache {
    private static final String PREFS = "snapshot_cache_v1";
    private static final int MAX_CACHED = 200;

    private final SharedPreferences preferences;

    public SnapshotCache(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String planId, List<SnapshotInfo> snapshots) {
        JSONArray array = new JSONArray();
        int count = Math.min(snapshots == null ? 0 : snapshots.size(), MAX_CACHED);
        try {
            for (int index = 0; index < count; index++) {
                SnapshotInfo item = snapshots.get(index);
                array.put(new JSONObject()
                        .put("planId", item.getPlanId())
                        .put("snapshotId", item.getSnapshotId())
                        .put("createdAt", item.getCreatedAt())
                        .put("sourceName", item.getSourceName())
                        .put("fileCount", item.getFileCount())
                        .put("directoryCount", item.getDirectoryCount())
                        .put("totalBytes", item.getTotalBytes())
                        .put("manifestSize", item.getManifestSize())
                        .put("manifestSha256", item.getManifestSha256()));
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode snapshot cache", impossible);
        }
        preferences.edit().putString(key(planId), array.toString()).apply();
    }

    public synchronized List<SnapshotInfo> load(String planId) {
        String raw = preferences.getString(key(planId), "[]");
        ArrayList<SnapshotInfo> result = new ArrayList<SnapshotInfo>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            int count = Math.min(array.length(), MAX_CACHED);
            for (int index = 0; index < count; index++) {
                JSONObject item = array.getJSONObject(index);
                result.add(new SnapshotInfo(
                        item.getString("planId"),
                        item.getString("snapshotId"),
                        item.getLong("createdAt"),
                        item.getString("sourceName"),
                        item.getLong("fileCount"),
                        item.getLong("directoryCount"),
                        item.getLong("totalBytes"),
                        item.getLong("manifestSize"),
                        item.getString("manifestSha256")));
            }
        } catch (JSONException invalid) {
            preferences.edit().remove(key(planId)).apply();
            return Collections.emptyList();
        } catch (IllegalArgumentException invalid) {
            preferences.edit().remove(key(planId)).apply();
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean clear() { return preferences.edit().clear().commit(); }

    private static String key(String planId) {
        if (planId == null || !planId.matches("[a-z0-9-]{8,64}")) {
            throw new IllegalArgumentException("Invalid plan id");
        }
        return "plan_" + planId;
    }
}
