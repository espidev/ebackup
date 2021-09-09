package dev.espi.ebackup;

import com.jcraft.jsch.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.*;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/*
   Copyright 2020 EspiDev

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
    // run async please
    public static void doBackup(boolean uploadToServer) {
        List<File> tempIgnore = new ArrayList<>();
        eBackup.getPlugin().getLogger().info("Starting backup...");

        // do not backup when plugin is disabled
        if (!eBackup.getPlugin().isEnabled()) {
            eBackup.getPlugin().getLogger().warning("Unable to start a backup, because the plugin is disabled by the server!");
            return;
        }

        // prevent other processes from backing up at the same time
        eBackup.getPlugin().isInBackup.set(true);

        File currentWorkingDirectory = new File(Paths.get(".").toAbsolutePath().normalize().toString());

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
            SimpleDateFormat formatter = new SimpleDateFormat(eBackup.getPlugin().backupDateFormat);
            String fileName = eBackup.getPlugin().backupFormat.replace("{DATE}", formatter.format(new Date()));
            FileOutputStream fos = new FileOutputStream(eBackup.getPlugin().backupPath + "/" + fileName + ".zip");
            ZipOutputStream zipOut = new ZipOutputStream(fos);

            // set zip compression level
            zipOut.setLevel(eBackup.getPlugin().compressionLevel);

            // backup worlds first
            for (World w : Bukkit.getWorlds()) {
                File worldFolder = w.getWorldFolder();

                String worldPath = Paths.get(currentWorkingDirectory.toURI()).relativize(Paths.get(worldFolder.toURI())).toString();
                if (worldPath.endsWith("/.")) {// 1.16 world folders end with /. for some reason
                    worldPath = worldPath.substring(0, worldPath.length() - 2);
                    worldFolder = new File(worldPath);
                }

                // check if world is in ignored list
                boolean skip = false;
                for (File f : eBackup.getPlugin().ignoredFiles) {
                    if (f.getCanonicalPath().equals(worldFolder.getCanonicalPath())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;

                // manually trigger world save (needs to be run sync)
                AtomicBoolean saved = new AtomicBoolean(false);
                Bukkit.getScheduler().runTask(eBackup.getPlugin(), () -> {
                    w.save();
                    saved.set(true);
                });

                // wait until world save is finished
                while (!saved.get()) Thread.sleep(500);

                w.setAutoSave(false); // make sure autosave doesn't screw everything over

                eBackup.getPlugin().getLogger().info("Backing up world " + w.getName() + " " + worldPath + "...");
                zipFile(worldFolder, worldPath, zipOut);

                w.setAutoSave(true);

                // ignore in dfs
                tempIgnore.add(worldFolder);
                eBackup.getPlugin().ignoredFiles.add(worldFolder);
            }

            // dfs all other files
            eBackup.getPlugin().getLogger().info("Backing up other files...");
            zipFile(currentWorkingDirectory, "", zipOut);
            zipOut.close();
            fos.close();

            // upload to ftp/sftp
            if (uploadToServer && eBackup.getPlugin().ftpEnable) {
                File f = new File(eBackup.getPlugin().backupPath + "/" + fileName + ".zip");
                if (eBackup.getPlugin().ftpType.equals("sftp")) {
                    eBackup.getPlugin().getLogger().info("Uploading backup to SFTP server...");
                    uploadSFTP(f);
                } else if (eBackup.getPlugin().ftpType.equals("ftp")) {
                    eBackup.getPlugin().getLogger().info("Uploading backup to FTP server...");
                    uploadFTP(f);
                }

                // if the upload is able to go smoothly, delete local backup
                if (eBackup.getPlugin().deleteAfterUpload) {
                    if (f.delete()) {
                        eBackup.getPlugin().getLogger().info("Successfully deleted local backup zip after upload.");
                    } else {
                        eBackup.getPlugin().getLogger().warning("Unable to delete local backup zip after upload.");
                    }
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

            // unlock
            eBackup.getPlugin().isInBackup.set(false);
        }
        eBackup.getPlugin().getLogger().info("Backup complete!");
    }

    private static void uploadSFTP(File f) throws JSchException, SftpException {
        JSch jsch = new JSch();

        // ssh key auth if enabled
        if (eBackup.getPlugin().useSftpKeyAuth) {
            if (eBackup.getPlugin().sftpPrivateKeyPassword.equals("")) {
                jsch.addIdentity(eBackup.getPlugin().sftpPrivateKeyPath);
            } else {
                jsch.addIdentity(eBackup.getPlugin().sftpPrivateKeyPath, eBackup.getPlugin().sftpPrivateKeyPassword);
            }
        }

        Session session = jsch.getSession(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
        // password auth if using password
        if (!eBackup.getPlugin().useSftpKeyAuth) {
            session.setPassword(eBackup.getPlugin().ftpPass);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        Channel channel = session.openChannel("sftp");
        channel.connect();
        ChannelSftp sftpChannel = (ChannelSftp) channel;
        sftpChannel.put(f.getAbsolutePath(), eBackup.getPlugin().ftpPath + "/" + f.getName());
        sftpChannel.exit();
        session.disconnect();
    }

    private static void uploadFTP(File f) throws IOException {
        FTPClient ftpClient = new FTPClient();
        try (FileInputStream fio = new FileInputStream(f)) {
            ftpClient.connect(eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
            ftpClient.login(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpPass);
            ftpClient.enterLocalPassiveMode();
            ftpClient.setUseEPSVwithIPv4(true);
            ftpClient.changeWorkingDirectory(eBackup.getPlugin().ftpPath);
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.storeFile(f.getName(), fio);
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
        // don't ignore hidden folders
        // if (fileToZip.isHidden() && !fileToZip.getPath().equals(".")) return;

        for (File f : eBackup.getPlugin().ignoredFiles) { // return if it is ignored file
            if (f.getCanonicalPath().equals(fileToZip.getCanonicalPath())) return;
        }

        // fix windows archivers not being able to see files because they don't support / (root) for zip files
        if (fileName.startsWith("/") || fileName.startsWith("\\")) {
            fileName = fileName.substring(1);
        }
        // make sure there won't be a "." folder
        if (fileName.startsWith("./") || fileName.startsWith(".\\")) {
            fileName = fileName.substring(2);
        }
        // truncate \. on windows (from the end of folder names)
        if (fileName.endsWith("/.") || fileName.endsWith("\\.")) {
            fileName = fileName.substring(0, fileName.length()-2);
        }

        if (fileToZip.isDirectory()) { // if it's a directory, recursively search
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
            }
            zipOut.closeEntry();
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
        } else { // if it's a file, store
            try {
                FileInputStream fis = new FileInputStream(fileToZip);
                ZipEntry zipEntry = new ZipEntry(fileName);
                zipOut.putNextEntry(zipEntry);
                byte[] bytes = new byte[1024];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
                fis.close();
            } catch (IOException e) {
                eBackup.getPlugin().getLogger().warning("Error while backing up file " + fileName + ", backup will ignore this file: " + e.getMessage());
            }
        }
    }
}
