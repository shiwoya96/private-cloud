package com.privatecloud.app.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class ExclusionRulesTest {
    @Test public void matchesDirectoryTreesAndExtensions() {
        ExclusionRules rules = new ExclusionRules("Cache, *.tmp\nDCIM/Thumbnails");
        assertTrue(rules.excludes("Cache", true));
        assertTrue(rules.excludes("Cache/image.jpg", false));
        assertTrue(rules.excludes("notes.tmp", false));
        assertTrue(rules.excludes("DCIM/Thumbnails/a.jpg", false));
        assertFalse(rules.excludes("DCIM/Camera/a.jpg", false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversal() { new ExclusionRules("../Secrets"); }
}
