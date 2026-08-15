package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import com.privatecloud.app.model.ManifestEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

/** Small local cache used to present paths for selective restore without retaining file data. */
public final class SnapshotContentsCache {
    private static final int MAX_PATHS = 500;
    private final SharedPreferences values;

    public SnapshotContentsCache(Context context) {
        values = context.getApplicationContext().getSharedPreferences(
                "private_cloud_snapshot_contents_v1", Context.MODE_PRIVATE);
    }

    public void save(String planId, String snapshotId, List<ManifestEntry> entries) {
        JSONArray paths = new JSONArray();
        int count = Math.min(entries.size(), MAX_PATHS);
        for (int index = 0; index < count; index++) paths.put(entries.get(index).displayPath());
        values.edit().putString("paths", paths.toString()).putString("plan", planId)
                .putString("snapshot", snapshotId).putBoolean("truncated", entries.size() > count)
                .commit();
    }

    public List<String> load(String planId, String snapshotId) {
        if (!planId.equals(values.getString("plan", ""))
                || !snapshotId.equals(values.getString("snapshot", ""))) {
            return Collections.emptyList();
        }
        try {
            JSONArray paths = new JSONArray(values.getString("paths", "[]"));
            ArrayList<String> result = new ArrayList<String>(paths.length());
            for (int index = 0; index < paths.length(); index++) result.add(paths.getString(index));
            return Collections.unmodifiableList(result);
        } catch (JSONException damaged) { return Collections.emptyList(); }
    }

    public boolean clear() { return values.edit().clear().commit(); }
}
