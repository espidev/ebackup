package dev.espi.ebackup;

import com.jcraft.jsch.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/*
   Copyright 2019 EstiNet

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

public class BackupUtil {

    // delete old backups (when limit reached)
    private static void checkMaxBackups() {
        if (eBackup.getPlugin().maxBackups <= 0) return;

        int backups = 0;
        SortedMap<Long, File> m = new TreeMap<>(); // oldest files to newest

        for (File f : eBackup.getPlugin().backupPath.listFiles()) {
            if (f.getName().endsWith(".zip")) {
                backups++;
                m.put(f.lastModified(), f);
            }
        }

        // delete old backups
        while (backups-- >= eBackup.getPlugin().maxBackups) {
            m.get(m.firstKey()).delete();
            m.remove(m.firstKey());
        }
    }

    // actually do the backup
    // run async plz
    public static void doBackup(boolean uploadToServer) {
        List<File> tempIgnore = new ArrayList<>();
        eBackup.getPlugin().getLogger().info("Starting backup...");
        try {
            // find plugin data to ignore
            for (File f : new File("plugins").listFiles()) {
                if ((!eBackup.getPlugin().backupPluginJars && f.getName().endsWith(".jar")) || (!eBackup.getPlugin().backupPluginConfs && f.isDirectory())) {
                    tempIgnore.add(f);
                    eBackup.getPlugin().ignoredFiles.add(f);
                }
            }

            // delete old backups
            checkMaxBackups();

            // zip
            String fileName = eBackup.getPlugin().backupFormat.replace("{DATE}", new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date()));
            FileOutputStream fos = new FileOutputStream(eBackup.getPlugin().backupPath + "/" + fileName + ".zip");
            ZipOutputStream zipOut = new ZipOutputStream(fos);

            // backup worlds first
            for (World w : Bukkit.getWorlds()) {
                File world = new File(w.getName());
                // check if world is in ignored list
                boolean skip = false;
                for (File f : eBackup.getPlugin().ignoredFiles) {
                    if (f.getAbsolutePath().equals(world.getAbsolutePath())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;

                AtomicBoolean saved = new AtomicBoolean(false);
                Bukkit.getScheduler().runTask(eBackup.getPlugin(), () -> {
                    w.save();
                    saved.set(true);
                });

                while (!saved.get()) Thread.sleep(500);

                w.setAutoSave(false); // make sure autosave doesn't screw everything over
                eBackup.getPlugin().getLogger().info("Backing up world " + world.getName() + "...");
                zipFile(world, world.getName(), zipOut);
                w.setAutoSave(true);

                // ignore in dfs
                tempIgnore.add(world);
                eBackup.getPlugin().ignoredFiles.add(world);
            }

            // dfs all other files
            eBackup.getPlugin().getLogger().info("Backing up other files...");
            zipFile(new File(Paths.get(".").toAbsolutePath().normalize().toString()), "", zipOut);
            zipOut.close();
            fos.close();

            // upload to ftp/sftp
            if (uploadToServer && eBackup.getPlugin().ftpEnable) {
                if (eBackup.getPlugin().ftpType.equals("sftp")) {
                    eBackup.getPlugin().getLogger().info("Uploading backup to SFTP server...");
                    uploadSFTP(new File(eBackup.getPlugin().backupPath + "/" + fileName + ".zip"));
                } else if (uploadToServer && eBackup.getPlugin().ftpType.equals("ftp")) {
                    eBackup.getPlugin().getLogger().info("Uploading backup to FTP server...");
                    uploadFTP(new File(eBackup.getPlugin().backupPath + "/" + fileName + ".zip"));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            for (World w : Bukkit.getWorlds()) {
                w.setAutoSave(true);
            }
            // restore tempignore
            for (File f : tempIgnore) {
                eBackup.getPlugin().ignoredFiles.remove(f);
            }
        }
        eBackup.getPlugin().getLogger().info("Backup complete!");
    }

    private static void uploadSFTP(File f) throws JSchException, SftpException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
        session.setPassword(eBackup.getPlugin().ftpPass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();
        Channel channel = session.openChannel("sftp");
        channel.connect();
        ChannelSftp sftpChannel = (ChannelSftp) channel;
        sftpChannel.put(f.getAbsolutePath(), eBackup.getPlugin().ftpPath + "/" + f.getName());
        sftpChannel.exit();
        session.disconnect();
    }

    private static void uploadFTP(File f) {
        FTPClient ftpClient = new FTPClient();
        try (FileInputStream fio = new FileInputStream(f)) {
            ftpClient.connect(eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
            ftpClient.login(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpPass);
            ftpClient.enterLocalPassiveMode();
            ftpClient.setUseEPSVwithIPv4(true);
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.storeFile(f.getName(), fio);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                ftpClient.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // recursively compress files and directories
    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden() && !fileToZip.getPath().equals(".")) return;
        for (File f : eBackup.getPlugin().ignoredFiles) { // return if it is ignored file
            if (f.getAbsolutePath().equals(fileToZip.getAbsolutePath())) return;
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
