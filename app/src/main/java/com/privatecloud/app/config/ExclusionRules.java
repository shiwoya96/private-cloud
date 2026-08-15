package com.privatecloud.app.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated newline/comma separated relative paths, directory prefixes and *.extension rules. */
public final class ExclusionRules {
    private final List<String> patterns;

    public ExclusionRules(String raw) {
        ArrayList<String> values = new ArrayList<String>();
        String source = raw == null ? "" : raw;
        if (source.length() > 1024) throw new IllegalArgumentException("排除规则过长");
        for (String item : source.split("[,\\n]")) {
            String value = item.trim();
            if (value.isEmpty()) continue;
            while (value.startsWith("/")) value = value.substring(1);
            if (value.contains("\\") || value.contains("..") || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("无效的排除规则：" + value);
            }
            values.add(value);
        }
        patterns = Collections.unmodifiableList(values);
    }

    public boolean excludes(String path, boolean directory) {
        String safe = path == null ? "" : path;
        for (String pattern : patterns) {
            if (pattern.startsWith("*.")) {
                if (!directory && safe.endsWith(pattern.substring(1))) return true;
            } else if (safe.equals(pattern) || safe.startsWith(pattern + "/")) {
                return true;
            }
        }
        return false;
    }

    public String encode() { return String.join("\n", patterns); }
}
