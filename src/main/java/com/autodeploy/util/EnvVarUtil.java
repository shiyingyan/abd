package com.autodeploy.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for reading environment variables by key.
 * Config records store only the env var key name; the actual value
 * is read from the OS environment at runtime via System.getenv().
 */
public class EnvVarUtil {

    /**
     * Read an environment variable value by key.
     * @return the value, or null if not set
     */
    public static String getValue(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        return System.getenv(key.trim());
    }

    /**
     * Check if an environment variable is set.
     */
    public static boolean isSet(String key) {
        return getValue(key) != null;
    }

    /**
     * Get all configured env var keys and their set status.
     */
    public static List<EnvVarStatus> checkKeys(List<String> keys) {
        List<EnvVarStatus> result = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                result.add(new EnvVarStatus(key, isSet(key)));
            }
        }
        return result;
    }

    public static class EnvVarStatus {
        private String key;
        private boolean set;

        public EnvVarStatus(String key, boolean set) {
            this.key = key;
            this.set = set;
        }

        public String getKey() { return key; }
        public boolean isSet() { return set; }
    }
}
