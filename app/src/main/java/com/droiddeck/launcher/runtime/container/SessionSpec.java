package com.droiddeck.launcher.runtime.container;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one container session needs from the App side: the mode the guest init runs, the
 * already-computed SessionService environment (host paths allowed; the env writer translates),
 * positionals after the mode, extra bind mounts and the uid/gid the guest should run as.
 */
public final class SessionSpec {
    public static final String MODE_STEAM = "steam";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_RUN = "run";
    public static final String MODE_SERVICE = "service";

    private final String mode;
    private final String sessionId;
    private final Map<String, String> env;
    private final List<String> args;
    private final List<String> extraBinds;
    private final int uid;
    private final int gid;
    private final int[] groups;
    private final String sessionBin;

    private SessionSpec(String mode, String sessionId, Map<String, String> env, List<String> args,
                        List<String> extraBinds, int uid, int gid, int[] groups, String sessionBin) {
        this.mode = mode == null ? "" : mode;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        this.args = Collections.unmodifiableList(new ArrayList<>(args));
        this.extraBinds = Collections.unmodifiableList(new ArrayList<>(extraBinds));
        this.uid = uid;
        this.gid = gid;
        this.groups = groups == null ? null : groups.clone();
        this.sessionBin = sessionBin == null || sessionBin.isEmpty()
                ? ContainerPaths.GUEST_SESSION : sessionBin;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String mode() {
        return mode;
    }

    public String sessionId() {
        return sessionId;
    }

    public Map<String, String> env() {
        return env;
    }

    public List<String> args() {
        return args;
    }

    public List<String> extraBinds() {
        return extraBinds;
    }

    public int uid() {
        return uid;
    }

    public int gid() {
        return gid;
    }

    public int[] groups() {
        return groups == null ? null : groups.clone();
    }

    public String sessionBin() {
        return sessionBin;
    }

    public boolean service() {
        return MODE_SERVICE.equals(mode);
    }

    public static final class Builder {
        private String mode = "";
        private String sessionId = "";
        private final Map<String, String> env = new LinkedHashMap<>();
        private final List<String> args = new ArrayList<>();
        private final List<String> extraBinds = new ArrayList<>();
        private int uid;
        private int gid;
        private int[] groups;
        private String sessionBin;

        public Builder mode(String value) {
            mode = value;
            return this;
        }

        public Builder sessionId(String value) {
            sessionId = value;
            return this;
        }

        public Builder env(Map<String, String> values) {
            if (values != null) env.putAll(values);
            return this;
        }

        /** Accepts an already {@code KEY=VALUE} list as SessionService computes it today. */
        public Builder env(List<String> keyValueEntries) {
            if (keyValueEntries == null) return this;
            for (String entry : keyValueEntries) {
                if (entry == null) continue;
                int separator = entry.indexOf('=');
                if (separator <= 0) continue;
                env.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
            return this;
        }

        public Builder putEnv(String key, String value) {
            if (key != null && value != null) env.put(key, value);
            return this;
        }

        public Builder args(List<String> values) {
            args.clear();
            if (values != null) args.addAll(values);
            return this;
        }

        public Builder args(String... values) {
            args.clear();
            if (values != null) args.addAll(Arrays.asList(values));
            return this;
        }

        public Builder extraBinds(List<String> values) {
            extraBinds.clear();
            if (values != null) extraBinds.addAll(values);
            return this;
        }

        public Builder uid(int value) {
            uid = value;
            return this;
        }

        public Builder gid(int value) {
            gid = value;
            return this;
        }

        public Builder groups(int[] values) {
            groups = values == null ? null : values.clone();
            return this;
        }

        public Builder sessionBin(String value) {
            sessionBin = value;
            return this;
        }

        public SessionSpec build() {
            return new SessionSpec(mode, sessionId, env, args, extraBinds, uid, gid, groups, sessionBin);
        }
    }
}
