package file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.*;

/** A snapshot of installed base/split APK assets. File/directory identity comes
 * from ZIP entries, never from AssetManager.list(file), whose result is ambiguous.
 * Keeps APK handles open throughout staging, including across package replacement.
 */
public final class ApkAssetArchive implements Closeable {
    private static final class Asset {
        final ZipFile apk; final ZipEntry entry;
        Asset(ZipFile apk, ZipEntry entry) { this.apk = apk; this.entry = entry; }
    }
    private final List<ZipFile> apks = new ArrayList<>();
    private final SortedMap<String, Asset> files = new TreeMap<>();
    private final SortedSet<String> directories = new TreeSet<>();

    public ApkAssetArchive(List<File> sources) throws IOException {
        try {
            for (File source : sources) {
                ZipFile apk = new ZipFile(source); apks.add(apk);
                Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith("assets/")) continue;
                    String name = entry.getName().substring(7);
                    if (name.isEmpty()) continue;
                    if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                    validate(name);
                    if (entry.isDirectory()) directories.add(name);
                    else files.put(name, new Asset(apk, entry));
                    int slash = name.lastIndexOf('/');
                    while (slash > 0) {
                        directories.add(name.substring(0, slash));
                        slash = name.lastIndexOf('/', slash - 1);
                    }
                }
            }
            for (String dir : directories)
                if (files.containsKey(dir)) throw new IOException("Conflicting file/directory in APK assets: " + dir);
        } catch (IOException | RuntimeException e) {
            try { close(); } catch (IOException ignored) { }
            throw e;
        }
    }
    private static void validate(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0)
            throw new IOException("Invalid APK asset path: " + path);
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("Invalid APK asset path: " + path);
    }
    public boolean isFile(String path) { return files.containsKey(path); }
    public boolean isDirectory(String path) { return directories.contains(path); }
    public void requireFile(String path) throws IOException {
        if (!isFile(path)) throw new FileNotFoundException("APK is missing required asset: " + path);
    }
    public List<String> list(String path) throws IOException {
        if (!isDirectory(path)) throw new FileNotFoundException("APK is missing asset directory: " + path);
        SortedSet<String> children = new TreeSet<>();
        String prefix = path + "/";
        for (String name : directories) addChild(children, prefix, name);
        for (String name : files.keySet()) addChild(children, prefix, name);
        return new ArrayList<>(children);
    }
    private static void addChild(Set<String> children, String prefix, String name) {
        if (!name.startsWith(prefix)) return;
        String relative = name.substring(prefix.length());
        int slash = relative.indexOf('/');
        children.add(slash < 0 ? relative : relative.substring(0, slash));
    }
    public String readText(String path) throws IOException {
        requireFile(path);
        Asset asset = files.get(path);
        try (InputStream in = asset.apk.getInputStream(asset.entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 1024 * 1024) throw new IOException("APK metadata too large: " + path);
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Cannot create asset directory: " + directory);
    }
    public void copy(String path, File target, boolean preserveExisting) throws IOException {
        validate(path);
        if (!isFile(path) && !isDirectory(path))
            throw new FileNotFoundException("APK is missing asset: " + path);
        if (isDirectory(path)) {
            mkdir(target);
            for (String name : list(path)) copy(path + "/" + name, new File(target, name), preserveExisting);
            return;
        }
        if (preserveExisting && target.exists()) return;
        File parent = target.getAbsoluteFile().getParentFile(); mkdir(parent);
        // Never leave a truncated player/default file on I/O failure. New files
        // are published by rename only after the ZIP stream has been verified.
        File temporary = File.createTempFile("arena-asset-", ".tmp", parent);
        try {
            Asset asset = files.get(path); long total = 0; CRC32 crc = new CRC32();
            try (InputStream in = asset.apk.getInputStream(asset.entry);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count); crc.update(buffer, 0, count); total += count;
                }
                out.getFD().sync();
            }
            if (total != asset.entry.getSize() || crc.getValue() != asset.entry.getCrc())
                throw new IOException("Corrupt APK asset: " + path);
            if (preserveExisting && target.exists()) return;
            if (!temporary.renameTo(target)) throw new IOException("Cannot install asset " + path + " to " + target);
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    public String fingerprint(String prefix) throws IOException {
        validate(prefix);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); int count = 0;
            for (Map.Entry<String, Asset> item : files.entrySet()) {
                if (!item.getKey().startsWith(prefix + "/")) continue;
                ZipEntry entry = item.getValue().entry;
                digest.update(("assets/" + item.getKey() + "\0" + entry.getCrc() + ":" + entry.getSize() + "\n")
                    .getBytes(StandardCharsets.UTF_8)); count++;
            }
            if (count == 0) throw new FileNotFoundException("APK assets are missing: " + prefix);
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    @Override public void close() throws IOException {
        IOException failure = null;
        for (ZipFile apk : apks) try { apk.close(); } catch (IOException e) { failure = e; }
        apks.clear();
        if (failure != null) throw failure;
    }
}
