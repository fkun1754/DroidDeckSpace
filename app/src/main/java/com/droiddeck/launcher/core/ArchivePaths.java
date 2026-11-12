package com.droiddeck.launcher.core;

import java.io.File;
import java.io.IOException;

public final class ArchivePaths {
    private ArchivePaths() {}

    public static File inside(File destination, String name) throws IOException {
        String base = destination.getCanonicalPath();
        if (name == null) return null;
        String relative = name.replace('\\', '/');
        while (relative.startsWith("./")) relative = relative.substring(2);
        // Some official Arch archives store entries with an absolute path; they are still meant to
        // be extracted under the runtime root, so strip the leading slash before containment checks.
        while (relative.startsWith("/")) relative = relative.substring(1);
        File file = new File(destination, relative);
        String path = file.getCanonicalPath();
        if (path.equals(base) || path.startsWith(base + File.separator)) return file;
        return null;
    }
}
