package com.muse.lazyspoof.utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs one shell script as root (su) and returns its stdout, or null when
 * root is unavailable, the command fails, or it times out. Used only for
 * read-only scope discovery and for `pm clear` / `am force-stop` on a target
 * app the user explicitly tapped Reset on. Never used to touch other data.
 */
public final class RootShell {

    private RootShell() {}

    public static String run(String script, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            // No stdin input; close it so `su` shells that wait on stdin exit.
            try {
                OutputStream stdin = process.getOutputStream();
                stdin.close();
            } catch (Throwable ignored) {
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = process.getInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            // Read while the process runs; poll in small slices so a stuck
            // shell cannot hang the caller past the timeout.
            while (System.currentTimeMillis() < deadline) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    while (in.available() > 0) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        out.write(buf, 0, n);
                    }
                    break;
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** True when `su` exists and grants a trivial command. */
    public static boolean isRootAvailable() {
        String out = run("id -u", 5000);
        return out != null && out.trim().equals("0");
    }
}
