package file;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Platform-independent update I/O. No Android API calls: exercised by JVM regression tests. */
public final class ContentUpdate {
    private ContentUpdate() {}
    public interface Progress { void update(long done, long total); }
    public static String url(String value) throws IOException {
        String text = value.trim();
        if (text.isEmpty()) throw new IOException("Update URL is empty");
        if (!text.contains("://")) text = "https://" + text;
        URL parsed = new URL(text);
        if (!(parsed.getProtocol().equals("https") || parsed.getProtocol().equals("http"))
                || parsed.getHost().isEmpty() || parsed.getUserInfo() != null)
            throw new IOException("Only HTTP(S) URLs without credentials are accepted");
        return parsed.toExternalForm();
    }
    public static BigInteger revision(String value) throws IOException {
        if (value == null || !value.matches("[0-9]{1,64}")) throw new IOException("Invalid update revision");
        return new BigInteger(value, 10);
    }
    public static Map<String, String> check(String text) throws IOException {
        Map<String, String> result = new HashMap<>();
        String section = "";
        for (String raw : text.replace("\uFEFF", "").split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            if (!(section.isEmpty() || section.equals("build") || section.equals("general") || section.equals("manifest"))) continue;
            int eq = line.indexOf('=');
            if (eq < 1 || line.startsWith("#") || line.startsWith(";")) continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            result.put(key, value);
        }
        revision(result.get("version"));
        revision(result.get("build"));
        return result;
    }
    public static void download(String address, File output, boolean small, String hash,
                                AtomicBoolean cancel, Progress progress) throws Exception {
        URL current = new URL(url(address));
        long deadline = System.currentTimeMillis() + (small ? 10000 : 6 * 3600000L);
        for (int redirect = 0; redirect <= 8; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            try {
                connection.setConnectTimeout(small ? 5000 : 20000);
                connection.setReadTimeout(small ? 5000 : 30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("User-Agent", "ArenaMP-Updater/1");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    URL next = new URL(url(new URL(current, location).toExternalForm()));
                    if (current.getProtocol().equals("https") && !next.getProtocol().equals("https"))
                        throw new IOException("HTTPS downgrade redirect refused");
                    current = next;
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                long total = -1;
                String length = connection.getHeaderField("Content-Length");
                if (length != null) total = Long.parseLong(length);
                if (small && total > 65536) throw new IOException("check.ini is too large");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long done = 0, last = 0;
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(output)) {
                    byte[] buffer = new byte[small ? 4096 : 65536];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (cancel.get()) throw new InterruptedIOException("Canceled");
                        if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("Download timed out");
                        done += n;
                        if (small && done > 65536) throw new IOException("check.ini is too large");
                        out.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        if (System.currentTimeMillis() - last > 250) {
                            progress.update(done, total);
                            last = System.currentTimeMillis();
                        }
                    }
                    out.getFD().sync();
                }
                if (total >= 0 && total != done) throw new IOException("Incomplete download");
                if (hash != null && !hash.isEmpty()) {
                    StringBuilder actual = new StringBuilder();
                    for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
                    if (!hash.matches("[a-fA-F0-9]{64}") || !actual.toString().equalsIgnoreCase(hash))
                        throw new IOException("SHA-256 mismatch");
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }
    private static String safeName(String raw) throws IOException {
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) return name;
        if (name.startsWith("/")) throw new IOException("Absolute archive path");
        for (String part : name.split("/", -1)) {
            if (part.contains(".arena-") || part.isEmpty() || part.equals(".") || part.equals("..") || part.matches(".*[:<>\"|?*\\p{Cntrl}].*")
                    || part.endsWith(" ") || part.endsWith(".")) throw new IOException("Unsafe archive path: " + raw);
        }
        return name;
    }
    private static File inside(File root, String name) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File candidate = new File(canonicalRoot, name);
        // Existing symlink destinations and symlink parents must not be followed.
        if (!candidate.getAbsolutePath().equals(candidate.getCanonicalPath())
                || !candidate.getCanonicalPath().startsWith(canonicalRoot.getPath() + File.separator))
            throw new IOException("Destination link or path traversal: " + name);
        return candidate;
    }
    private static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }
    private static void rename(File src, File dst) throws IOException {
        if (!src.renameTo(dst)) throw new IOException("Cannot rename " + src + " to " + dst);
    }
    public static void deleteTree(File f) throws IOException {
        if (!f.exists()) return;
        // Never recurse into symbolic links, including links introduced externally.
        if (f.isDirectory() && f.getAbsolutePath().equals(f.getCanonicalPath())) {
            File[] children = f.listFiles();
            if (children == null) throw new IOException("Cannot list " + f);
            for (File child : children) deleteTree(child);
        }
        if (!f.delete()) throw new IOException("Cannot delete " + f);
    }
    private static void save(File file, Properties p) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { p.store(out, "Arena update transaction"); out.getFD().sync(); }
        rename(temp, file);
    }
    public static void recover(File journal) throws IOException {
        if (!journal.isFile()) return;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(journal)) { p.load(in); }
        boolean committed = "done".equals(p.getProperty("state"));
        int count = Integer.parseInt(p.getProperty("count", "0"));
        for (int i = count - 1; i >= 0; i--) {
            File dest = new File(p.getProperty(i + ".dest"));
            File backup = new File(p.getProperty(i + ".backup"));
            File temp = new File(p.getProperty(i + ".temp"));
            if (!committed) {
                if (backup.exists()) {
                    if (dest.exists() && !dest.delete()) throw new IOException("Cannot roll back " + dest);
                    rename(backup, dest);
                } else if ("false".equals(p.getProperty(i + ".original"))
                        && "true".equals(p.getProperty(i + ".started")) && dest.exists() && !dest.delete())
                    throw new IOException("Cannot remove new update file " + dest);
            } else if (backup.exists() && !backup.delete()) throw new IOException("Cannot remove backup " + backup);
            if (temp.exists() && !temp.delete()) throw new IOException("Cannot remove temporary file " + temp);
        }
        if (!journal.delete()) throw new IOException("Cannot remove update journal");
    }
    public static File extract(File archive, File stage, AtomicBoolean cancel) throws IOException {
        mkdir(stage);
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.size() == 0 || zip.size() > 500000) throw new IOException("Empty or oversized archive");
            Set<String> names = new HashSet<>();
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            long expanded = 0;
            for (ZipEntry entry : entries) {
                String name = safeName(entry.getName());
                if (name.isEmpty()) continue;
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate/case-colliding ZIP path");
                if (entry.getSize() < 0 || entry.getSize() > 128L * 1024 * 1024 * 1024) throw new IOException("Invalid ZIP size");
                expanded += entry.getSize();
                if (expanded > 128L * 1024 * 1024 * 1024) throw new IOException("Archive exceeds 128 GiB");
            }
            if (stage.getUsableSpace() < expanded + 16 * 1024 * 1024) throw new IOException("Not enough free space");
            for (ZipEntry entry : entries) {
                if (cancel.get()) throw new InterruptedIOException("Canceled");
                String name = safeName(entry.getName());
                if (name.isEmpty()) continue;
                File dest = inside(stage, name);
                if (entry.isDirectory()) { mkdir(dest); continue; }
                mkdir(dest.getParentFile());
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (cancel.get()) throw new InterruptedIOException("Canceled");
                        size += n;
                        if (size > entry.getSize()) throw new IOException("ZIP size mismatch");
                        crc.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("ZIP CRC/size mismatch");
            }
        }
        File[] children = stage.listFiles();
        if (children == null || children.length == 0) throw new IOException("Empty content archive");
        if (children.length == 1 && children[0].isDirectory() && children[0].getName().equalsIgnoreCase("Data Files")) return children[0];
        for (File child : children) if (child.getName().equalsIgnoreCase("Data Files")) throw new IOException("Mixed Data Files archive root");
        return stage;
    }
    private static void collect(File root, File dir, List<File> files) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("Cannot read staged content");
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) collect(root, child, files); else files.add(child);
        }
    }
    public static void install(File payload, File data, File manifest, String version, File journal) throws IOException {
        recover(journal);
        revision(version);
        List<File> files = new ArrayList<>(); collect(payload, payload, files);
        if (files.isEmpty()) throw new IOException("No content files");
        List<File> destinations = new ArrayList<>();
        for (File source : files) {
            String relative = payload.toURI().relativize(source.toURI()).getPath();
            String first = relative.split("/")[0].toLowerCase(Locale.ROOT);
            if (first.equals("build.ini") || first.equals("check.ini") || first.equals("openmw.cfg")
                    || first.equals("update.log") || first.equals("update.log.old")
                    || first.equals("settings.cfg") || first.equals("launcher.cfg") || first.startsWith(".arena-"))
                throw new IOException("Content ZIP contains client configuration");
            destinations.add(inside(data, relative));
        }
        // Version changes participate in the same transaction as content and are always last.
        File versionFile = new File(payload.getParentFile(), "updated-build.ini");
        String updated = revisionText(readText(manifest), "version", version);
        try (OutputStream out = new FileOutputStream(versionFile)) { out.write(updated.getBytes(StandardCharsets.UTF_8)); }
        files.add(versionFile); destinations.add(manifest);
        Properties p = new Properties(); p.setProperty("state", "applying");
        // Persist all rollback intent once before any replacement. This is linear
        // in archive size, including packs with tens of thousands of loose files.
        for (int i = 0; i < files.size(); i++) {
            File dest = destinations.get(i); mkdir(dest.getParentFile());
            if (dest.exists() && !dest.isFile()) throw new IOException("Update destination is not a file");
            File backup = new File(dest.getPath() + ".arena-backup");
            File temp = new File(dest.getPath() + ".arena-new");
            if (backup.exists() || temp.exists()) throw new IOException("Stale update backup");
            p.setProperty(i + ".dest", dest.getAbsolutePath()); p.setProperty(i + ".backup", backup.getAbsolutePath());
            p.setProperty(i + ".temp", temp.getAbsolutePath()); p.setProperty(i + ".original", String.valueOf(dest.exists()));
            p.setProperty(i + ".started", "true");
        }
        p.setProperty("count", String.valueOf(files.size())); save(journal, p);
        try {
            for (int i = 0; i < files.size(); i++) {
                File dest = destinations.get(i);
                File backup = new File(p.getProperty(i + ".backup"));
                File temp = new File(p.getProperty(i + ".temp"));
                try (InputStream in = new FileInputStream(files.get(i)); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (Boolean.parseBoolean(p.getProperty(i + ".original"))) rename(dest, backup);
                rename(temp, dest);
            }
            p.setProperty("state", "done"); save(journal, p);
        } catch (IOException | RuntimeException e) { recover(journal); throw e; }
        recover(journal);
    }
    private static String revisionText(String text, String key, String version) throws IOException {
        revision(version);
        StringBuilder updated = new StringBuilder();
        String section = ""; boolean found = false;
        for (String line : text.split("\\r?\\n", -1)) {
            String trimmed = line.trim().replace("\uFEFF", "");
            if (trimmed.startsWith("[") && trimmed.endsWith("]"))
                section = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase(Locale.ROOT);
            if ((section.isEmpty() || section.equals("build") || section.equals("general") || section.equals("manifest"))
                    && trimmed.matches("(?i)" + key + "\\s*=.*")) { line = key + "=" + version; found = true; }
            updated.append(line).append('\n');
        }
        if (!found) updated.append("\n[Build]\n").append(key).append('=').append(version).append('\n');
        return updated.toString();
    }

    /** Acknowledge the installed APK without regenerating server/content configuration. */
    public static void stampEngineBuild(File manifest, String build) throws IOException {
        String updated = revisionText(readText(manifest), "build", build);
        File temp = new File(manifest.getPath() + ".arena-stamp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(updated.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            rename(temp, manifest);
        } finally { if (temp.exists()) temp.delete(); }
    }

    public static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
