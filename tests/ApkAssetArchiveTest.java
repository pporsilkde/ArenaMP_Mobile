import file.ApkAssetArchive;
import file.AssetInstallLock;
import file.AssetTransaction;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

public class ApkAssetArchiveTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void text(File file, String value) throws IOException {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static File apk(File root, String name, String... entries) throws IOException {
        File file = new File(root, name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry("assets/" + entries[i]));
                if (!entries[i].endsWith("/")) zip.write(entries[i+1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("lock-child")) {
            System.out.println("waiting"); System.out.flush();
            try (AssetInstallLock lock = new AssetInstallLock(new File(args[1]))) { System.out.println("acquired"); }
            return;
        }
        File root = Files.createTempDirectory("arena-apk-test").toFile();
        String prefix = "arenamp-server";
        File base = apk(root,"base.apk",
            prefix + "/server/scripts/serverCore.lua", "core-v1",
            prefix + "/server/scripts/config.lua", "config-default",
            prefix + "/resources/version", "v1",
            prefix + "/resources/папка с пробелами/empty.txt", "",
            prefix + "/server/empty-directory/", "",
            prefix + "/server/data/player/example.json", "default-player",
            prefix + "/server/data/banlist.json", "[]",
            prefix + "/tes3mp-server-default.cfg", "server-config",
            "libopenmw/resources/version", "client-v1");
        File stage = new File(root, "stage"), runtime = new File(root,"runtime");
        runtime.mkdirs();
        text(new File(runtime,"server/data/player/example.json"),"saved-player");
        text(new File(runtime,"server/scripts/obsolete.lua"),"obsolete");
        text(new File(runtime,"config/server-config.lua"),"saved-config");
        String oldFingerprint;
        try (ApkAssetArchive assets = new ApkAssetArchive(Collections.singletonList(base));
             AssetInstallLock lock = new AssetInstallLock(runtime)) {
            assets.requireFile(prefix + "/server/scripts/serverCore.lua");
            check(assets.isDirectory(prefix + "/server/scripts"), "implicit directory");
            check(assets.isDirectory(prefix + "/server/empty-directory"), "explicit empty directory");
            check(assets.isFile(prefix + "/resources/папка с пробелами/empty.txt"), "empty file is a file");
            oldFingerprint = assets.fingerprint(prefix);
            assets.copy(prefix + "/server/scripts",new File(stage,"server/scripts"),false);
            assets.copy(prefix + "/server/empty-directory",new File(stage,"server/empty-directory"),false);
            assets.copy(prefix + "/resources",new File(stage,"resources"),false);
            assets.copy(prefix + "/server/data",new File(runtime,"server/data"),true);
            AssetTransaction.apply(runtime,stage,Arrays.asList("server/scripts","server/empty-directory","resources"),"server-assets.sha256",oldFingerprint);
            check(text(new File(runtime,"server/scripts/serverCore.lua")).equals("core-v1"),"compressed file copied");
            check(!new File(runtime,"server/scripts/obsolete.lua").exists(),"managed obsolete removed");
            check(new File(runtime,"server/empty-directory").isDirectory(),"empty directory installed");
            check(text(new File(runtime,"server/data/player/example.json")).equals("saved-player"),"saved player preserved");
            check(text(new File(runtime,"config/server-config.lua")).equals("saved-config"),"persistent config preserved");
            check(text(new File(runtime,"server/data/banlist.json")).equals("[]"),"missing default copied");
            boolean missing=false;
            try { assets.copy(prefix + "/missing",new File(stage,"missing"),false); }
            catch(FileNotFoundException e) { missing=e.getMessage().contains(prefix+"/missing"); }
            check(missing,"missing asset reports precise path");
            check(!new File(stage,"missing").exists(),"missing file not invented");
        }
        File split = apk(root,"split.apk",prefix + "/server/scripts/serverCore.lua","core-v2");
        try (ApkAssetArchive assets = new ApkAssetArchive(Arrays.asList(base,split))) {
            check(assets.readText(prefix + "/server/scripts/serverCore.lua").equals("core-v2"),"split overlay");
            check(!oldFingerprint.equals(assets.fingerprint(prefix)),"split changes fingerprint");
            check(assets.fingerprint("libopenmw").length()==64,"other asset root independent");
        }
        File broken=apk(root,"broken.apk","arenamp-server/../outside","bad");
        boolean rejected=false;
        try(ApkAssetArchive ignored=new ApkAssetArchive(Collections.singletonList(broken))) {}
        catch(IOException e) { rejected=true; }
        check(rejected,"unsafe APK name rejected");
        Process child;
        try(AssetInstallLock lock=new AssetInstallLock(runtime)) {
            child = new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),
                "-cp",System.getProperty("java.class.path"),"ApkAssetArchiveTest","lock-child",runtime.getPath())
                .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));
            check("waiting".equals(reader.readLine()),"second process started");
            check(!child.waitFor(250,TimeUnit.MILLISECONDS),"second process blocked during installation");
        }
        check(child.waitFor(5,TimeUnit.SECONDS) && child.exitValue()==0,"second process resumes after lock release");
        System.out.println("ApkAssetArchiveTest OK: APK files/directories, staging, defaults, splits, missing assets, process lock");
    }
}
