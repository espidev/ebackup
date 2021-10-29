package dev.espi.ebackup;

import com.jcraft.jsch.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class UploadUtill {
    public static void uploadSFTP(File f) throws JSchException, SftpException {
        eBackup.getPlugin().isInUpload.set(true);
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
        deleteAfterUpload(f);
        eBackup.getPlugin().isInUpload.set(false);
    }

    public static void uploadFTP(File f) throws IOException {
        eBackup.getPlugin().isInUpload.set(true);
        FTPClient ftpClient = new FTPClient();
        try (FileInputStream fio = new FileInputStream(f)) {
            ftpClient.setDataTimeout(300 * 1000);
            ftpClient.setConnectTimeout(300 * 1000);
            ftpClient.setDefaultTimeout(300 * 1000);
            ftpClient.setControlKeepAliveTimeout(60);

            ftpClient.connect(eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
            ftpClient.enterLocalPassiveMode();

            ftpClient.login(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpPass);
            //ftpClient.setUseEPSVwithIPv4(true);

            ftpClient.changeWorkingDirectory(eBackup.getPlugin().ftpPath);
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setBufferSize(1024 * 1024 * 8);
            if (ftpClient.storeFile(f.getName(), fio)) {
                eBackup.getPlugin().getLogger().info("Upload " + f.getName() + " Success.");
                deleteAfterUpload(f);
            } else
                eBackup.getPlugin().getLogger().warning("Upload " + f.getName() + " Failed.");
        } finally {
            try {
                ftpClient.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        eBackup.getPlugin().isInUpload.set(false);
    }

    public static void deleteAfterUpload(File f) {
        if (eBackup.getPlugin().deleteAfterUpload) {
            Bukkit.getScheduler().runTaskAsynchronously(eBackup.getPlugin(), () -> {
                if (f.delete()) {
                    eBackup.getPlugin().getLogger().info("Successfully deleted " + f.getName() + " after upload.");
                } else {
                    eBackup.getPlugin().getLogger().warning("Unable to delete " + f.getName() + " after upload.");
                }
            });
        }
    }
}
