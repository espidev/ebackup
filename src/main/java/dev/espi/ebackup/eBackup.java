package dev.espi.ebackup;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

public class eBackup extends JavaPlugin implements CommandExecutor {

    // lock
    AtomicBoolean isInBackup = new AtomicBoolean(false);
    AtomicBoolean isInUpload = new AtomicBoolean(false);

    // config options
    String crontask, backupFormat, backupDateFormat;
    File backupPath;
    int maxBackups;
    boolean onlyBackupIfPlayersWereOn;
    boolean deleteAfterUpload;
    int compressionLevel;

    String ftpType, ftpHost, ftpUser, ftpPass, ftpPath, sftpPrivateKeyPath, sftpPrivateKeyPassword;
    int ftpPort;
    boolean ftpEnable, useSftpKeyAuth;

    boolean backupPluginJars, backupPluginConfs;
    List<String> filesToIgnore;
    List<File> ignoredFiles = new ArrayList<>();

    BukkitTask bukkitCronTask = null;

    // track if players were on
    AtomicBoolean playersWereOnSinceLastBackup = new AtomicBoolean(false);

    // called on reload and when the plugin first loads
    public void loadPlugin() {
        ignoredFiles.clear();

        saveDefaultConfig();
        getConfig().options().copyDefaults(true);

        if (!getDataFolder().exists())
            getDataFolder().mkdir();

        try {
            getConfig().load(getDataFolder() + "/config.yml");
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }

        // load config data
        crontask = getConfig().getString("crontask");
        backupFormat = getConfig().getString("backup-format");
        backupDateFormat = getConfig().getString("backup-date-format");
        backupPath = new File(getConfig().getString("backup-path"));
        maxBackups = getConfig().getInt("max-backups");
        onlyBackupIfPlayersWereOn = getConfig().getBoolean("only-backup-if-players-were-on");
        deleteAfterUpload = getConfig().getBoolean("delete-after-upload");
        compressionLevel = getConfig().getInt("compression-level");
        if (!getConfig().contains("compression-level") || compressionLevel > 9 || compressionLevel < 0) {
            if (compressionLevel > 9 || compressionLevel < 0) {
                getLogger().warning("Invalid compression level set! Must be between 0-9. Defaulting to 4.");
            }
            compressionLevel = 4;
        }

        ftpEnable = getConfig().getBoolean("ftp.enable");
        ftpType = getConfig().getString("ftp.type");
        ftpHost = getConfig().getString("ftp.host");
        ftpPort = getConfig().getInt("ftp.port");
        ftpUser = getConfig().getString("ftp.user");
        ftpPass = getConfig().getString("ftp.pass");
        useSftpKeyAuth = getConfig().getBoolean("ftp.use-key-auth");
        sftpPrivateKeyPath = getConfig().getString("ftp.private-key");
        sftpPrivateKeyPassword = getConfig().getString("ftp.private-key-password");
        ftpPath = getConfig().getString("ftp.path");
        backupPluginJars = getConfig().getBoolean("backup.pluginjars");
        backupPluginConfs = getConfig().getBoolean("backup.pluginconfs");
        filesToIgnore = getConfig().getStringList("backup.ignore");
        for (String s : filesToIgnore) {
            ignoredFiles.add(new File(s));
        }

        // make sure backup location exists
        if (!backupPath.exists())
            backupPath.mkdir();

        // stop cron task if it is running
        if (bukkitCronTask != null)
            bukkitCronTask.cancel();

        // start cron task
        CronUtil.checkCron();
        bukkitCronTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (CronUtil.run()) {
                if (isInBackup.get()) {
                    getLogger().warning("A backup was scheduled to happen now, but a backup was detected to be in progress. Skipping...");
                } else if (onlyBackupIfPlayersWereOn && !playersWereOnSinceLastBackup.get()) {
                    getLogger().info("No players were detected to have joined since the last backup or server start, skipping backup...");
                } else {
                    BackupUtil.doBackup(true);

                    if (Bukkit.getServer().getOnlinePlayers().size() == 0) {
                        playersWereOnSinceLastBackup.set(false);
                    }
                }
            }
        }, 20, 20);
    }

    @Override
    public void onEnable() {
        getLogger().info("Initializing eBackup...");

        try {
            Metrics metrics = new Metrics(this);
        } catch (NoClassDefFoundError ignored) {
            // ignore if metrics is broken for old versions
        }
        this.getCommand("ebackup").setExecutor(this);

        loadPlugin();

        getLogger().info("Plugin initialized!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled eBackup!");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        playersWereOnSinceLastBackup.set(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "Do /ebackup help for help!");
            return true;
        }

        switch (args[0]) {
            case "help":
                sender.sendMessage(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "=====" + ChatColor.RESET + ChatColor.DARK_AQUA + " eBackup v" + getPlugin().getDescription().getVersion() + " Help " + ChatColor.RESET + ChatColor.GRAY + ChatColor.STRIKETHROUGH + "=====");
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + "/ebackup backup - Starts a backup of the server.");
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + "/ebackup backuplocal - Starts a backup of the server, but does not upload to FTP/SFTP.");
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + "/ebackup list - Lists the backups in the folder.");
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + "/ebackup stats - Shows disk space.");
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + "/ebackup reload - Reloads the plugin settings from the config.");
                break;
            case "backup":
                if (isInBackup.get()) {
                    sender.sendMessage(ChatColor.RED + "A backup is currently in progress!");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Starting backup (check console logs for details)...");
                    Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
                        BackupUtil.doBackup(true);
                        sender.sendMessage(ChatColor.GRAY + "Finished!");
                    });
                }
                break;
            case "backuplocal":
                if (isInBackup.get()) {
                    sender.sendMessage(ChatColor.RED + "A backup is currently in progress!");
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Starting backup (check console logs for details)...");
                    Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
                        BackupUtil.doBackup(false);
                        sender.sendMessage(ChatColor.GRAY + "Finished!");
                    });
                }
                break;
            case "list":
                sender.sendMessage(ChatColor.AQUA + "Local Backups:");
                for (File f : getPlugin().backupPath.listFiles()) {
                    sender.sendMessage(ChatColor.GRAY + "- " + f.getName());
                }
                break;
            case "stats":
                sender.sendMessage(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "=====" + ChatColor.RESET + ChatColor.DARK_AQUA + " Disk Stats " + ChatColor.RESET + ChatColor.GRAY + ChatColor.STRIKETHROUGH + "=====");
                sender.sendMessage(ChatColor.AQUA + "Total size: " + ChatColor.GRAY + (getPlugin().backupPath.getTotalSpace()/1024/1024/1024) + "GB");
                sender.sendMessage(ChatColor.AQUA + "Space usable: " + ChatColor.GRAY + (getPlugin().backupPath.getUsableSpace()/1024/1024/1024) + "GB");
                sender.sendMessage(ChatColor.AQUA + "Space free: " + ChatColor.GRAY + (getPlugin().backupPath.getFreeSpace()/1024/1024/1024) + "GB");
                break;
            case "reload":
                sender.sendMessage(ChatColor.GRAY + "Starting plugin reload...");
                loadPlugin();
                sender.sendMessage(ChatColor.GRAY + "Reloaded eBackup!");
                break;
            default:
                sender.sendMessage(ChatColor.AQUA + "Do /ebackup help for help!");
                break;
        }
        return true;
    }

    public static eBackup getPlugin() {
        return (eBackup) Bukkit.getPluginManager().getPlugin("eBackup");
    }

}
