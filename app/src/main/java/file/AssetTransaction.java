package file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Replace managed asset files/directories, including removal of obsolete files.
 * Stage and target live on the same filesystem. A durable journal protects
 * against process termination between directory renames. User data is never listed.
 */
public final class AssetTransaction {
    private AssetTransaction() {}
    private static void move(File from, File to) throws IOException {
        if (!from.renameTo(to)) throw new IOException("Cannot move asset tree " + from + " to " + to);
    }
    private static void save(File journal, Properties state) throws IOException {
        File tmp = new File(journal.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { state.store(out, "Managed assets"); out.getFD().sync(); }
        move(tmp, journal);
    }
    public static void recover(File root) throws IOException {
        File journal = new File(root, ".arena-assets-journal");
        if (!journal.isFile()) return;
        Properties state = new Properties();
        try (InputStream in = new FileInputStream(journal)) { state.load(in); }
        boolean done = "done".equals(state.getProperty("state"));
        for (int i = Integer.parseInt(state.getProperty("count", "0")) - 1; i >= 0; i--) {
            File target = new File(root, state.getProperty(i + ".path"));
            File backup = new File(target.getPath() + ".arena-assets-old");
            if (done) ContentUpdate.deleteTree(backup);
            else if (backup.exists()) {
                ContentUpdate.deleteTree(target);
                move(backup, target);
            } else if ("false".equals(state.getProperty(i + ".original"))) ContentUpdate.deleteTree(target);
        }
        if (!journal.delete()) throw new IOException("Cannot clear asset journal");
    }
    public static void apply(File root, File stage, List<String> paths, String stampName, String stamp) throws IOException {
        recover(root);
        File stampFile = new File(stage, stampName);
        stampFile.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(stampFile)) { out.write(stamp.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        List<String> operations = new ArrayList<>(paths); operations.add(stampName);
        Properties state = new Properties(); state.setProperty("state", "applying"); state.setProperty("count", "0");
        File journal = new File(root, ".arena-assets-journal"); save(journal, state);
        try {
            for (int i = 0; i < operations.size(); i++) {
                String path = operations.get(i);
                File source = new File(stage, path), target = new File(root, path);
                if (!target.getCanonicalPath().equals(target.getAbsolutePath())
                        || !target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
                    throw new IOException("Invalid managed asset path");
                if (!source.exists()) throw new IOException("Missing staged assets: " + path);
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create asset folder");
                File backup = new File(target.getPath() + ".arena-assets-old");
                if (backup.exists()) throw new IOException("Stale asset backup");
                state.setProperty(i + ".path", path); state.setProperty(i + ".original", String.valueOf(target.exists()));
                state.setProperty("count", String.valueOf(i + 1)); save(journal, state);
                if (target.exists()) move(target, backup);
                move(source, target);
            }
            state.setProperty("state", "done"); save(journal, state);
        } catch (IOException | RuntimeException e) { recover(root); throw e; }
        recover(root);
    }
}
