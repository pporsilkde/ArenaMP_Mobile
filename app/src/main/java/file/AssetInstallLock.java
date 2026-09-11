package file;
import java.io.*;
import java.nio.channels.FileLock;

/** Shared by launcher and :arenamp_server; @Synchronized alone is process-local. */
public final class AssetInstallLock implements Closeable {
    private final RandomAccessFile file;
    private final FileLock lock;
    public AssetInstallLock(File root) throws IOException {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())
            throw new IOException("Cannot create asset root: " + root);
        file = new RandomAccessFile(new File(root, ".arena-assets-install.lock"), "rw");
        try { lock = file.getChannel().lock(); }
        catch (IOException | RuntimeException e) { file.close(); throw e; }
    }
    @Override public void close() throws IOException {
        try { lock.release(); } finally { file.close(); }
    }
}
