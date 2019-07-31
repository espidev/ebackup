package net.espi.ebackup;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class eBackup extends JavaPlugin implements CommandExecutor {

    public static boolean backupInProgress = false;

    // config options

    private String crontask, backupFormat;
    private File backupPath;
    private int maxBackups;

    private String ftpType, ftpMode, ftpHost, ftpUser, ftpPass, ftpPath;
    private int ftpPort;

    private boolean backupPluginJars, backupPluginConfs;
    private List<String> filesToIgnore;
    private List<File> ignoredFiles = new ArrayList<>();

    @Override
    public void onEnable() {
        getLogger().info("Initializing eBackup...");

        getConfig().options().copyDefaults(true);
        crontask = getConfig().getString("crontask");
        backupFormat = getConfig().getString("backup-format");
        backupPath = new File(getConfig().getString("backup-path"));
        maxBackups = getConfig().getInt("max-backups");
        ftpMode = getConfig().getString("ftp.mode");
        ftpType = getConfig().getString("ftp.type");
        ftpHost = getConfig().getString("ftp.host");
        ftpPort = getConfig().getInt("ftp.port");
        ftpUser = getConfig().getString("ftp.user");
        ftpPass = getConfig().getString("ftp.pass");
        ftpPath = getConfig().getString("ftp.path");
        backupPluginJars = getConfig().getBoolean("backup.pluginjars");
        backupPluginConfs = getConfig().getBoolean("backup.pluginconfs");
        filesToIgnore = getConfig().getStringList("backup.ignore");
        for (String s : filesToIgnore) {
            File f = new File(s);
            ignoredFiles.add(f);
        }
        this.getCommand("ebackup").setExecutor(new eBackup());

        getLogger().info("Plugin initialized!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled eBackup!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        return true;
    }

    public static eBackup getPlugin() {
        return (eBackup) Bukkit.getPluginManager().getPlugin("eBackup");
    }

    private static void checkMaxBackups() {
        if (getPlugin().maxBackups <= 0) return;

        int backups = 0;
        SortedMap<Long, File> m = new TreeMap<>(); // oldest files to newest

        for (File f : getPlugin().backupPath.listFiles()) {
            if (f.getName().endsWith(".zip")) {
                backups++;
                m.put(f.lastModified(), f);
            }
        }

        // delete old backups
        while (backups-- > getPlugin().maxBackups) {
            m.get(m.firstKey()).delete();
            m.remove(m.firstKey());
        }
    }

    public static void doBackup() {
        List<File> tempIgnore = new ArrayList<>();
        try {
            // find plugin data to ignore
            for (File f : new File("plugins").listFiles()) {
                if ((!getPlugin().backupPluginJars && f.getName().endsWith(".jar")) || (!getPlugin().backupPluginConfs && f.isDirectory())) {
                    tempIgnore.add(f);
                    getPlugin().ignoredFiles.add(f);
                }
            }

            // delete old backups
            checkMaxBackups();

            // zip
            String fileName = getPlugin().backupFormat.replace("{DATE}", new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date()));
            FileOutputStream fos = new FileOutputStream(getPlugin().backupPath + "/" + fileName);
            ZipOutputStream zipOut = new ZipOutputStream(fos);

            // backup worlds first
            for (World w : Bukkit.getWorlds()) {
                File world = new File(w.getName());
                // check if world is in ignored list
                boolean skip = false;
                for (File f : getPlugin().ignoredFiles) {
                    if (f.getPath().equals(world.getPath())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;

                // ignore in dfs
                tempIgnore.add(world);
                getPlugin().ignoredFiles.add(world);

                w.save();
                w.setAutoSave(false); // make sure autosave doesn't screw everything over
                zipFile(world, world.getName(), zipOut);
                w.setAutoSave(true);
            }

            // dfs all other files
            zipFile(Bukkit.getWorldContainer(), Bukkit.getWorldContainer().getName(), zipOut);
            zipOut.close();
            fos.close();

            // upload to ftp/sftp

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            for (World w : Bukkit.getWorlds()) {
                w.setAutoSave(true);
            }

            // restore tempignore
            for (File f : tempIgnore) {
                getPlugin().ignoredFiles.remove(f);
            }
        }
    }

    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) return;
        for (File f : getPlugin().ignoredFiles) { // return if it is ignored file
            if (f.getPath().equals(fileToZip.getPath())) return;
        }

        if (fileToZip.isDirectory()) { // recursively search
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }
}
