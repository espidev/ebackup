package dev.espi.ebackup;

import java.util.Calendar;

public class Cron {
    private static long second, minute, hour, day, month, week;

    public static void checkCron() {
        String[] s = eBackup.getPlugin().crontask.split(" ");
        if (s.length == 6 && eBackup.getPlugin().crontask.matches("([0-9]{1,2} |\\* ){5}([0-9]{1,2}|\\*)")) {
            second = s[0].equals("*") ? -1 : Integer.parseInt(s[0]);
            minute = s[1].equals("*") ? -1 : Integer.parseInt(s[1]);
            hour = s[2].equals("*") ? -1 : Integer.parseInt(s[2]);
            day = s[3].equals("*") ? -1 : Integer.parseInt(s[3]);
            month = s[4].equals("*") ? -1 : Integer.parseInt(s[4]);
            week = s[5].equals("*") ? -1 : Integer.parseInt(s[5]);
            if (second != -1 && (second < 0 || second > 59))
                throw new IllegalArgumentException("Error in cron task format \"" + eBackup.getPlugin().crontask + "\": Second (1) must be between 0 and 59");
            if (minute != -1 && (minute < 0 || minute > 59))
                throw new IllegalArgumentException("Error in cron task format \"" + eBackup.getPlugin().crontask + "\": Minute (2) must be between 0 and 59");
            if (hour != -1 && (hour < 0 || hour > 23))
                throw new IllegalArgumentException("Error in cron task format \"" + eBackup.getPlugin().crontask + "\": Hour of the Day (3) must be between 0 and 23");
            if (day != -1 && (day < 1 || day > 31))
                throw new IllegalArgumentException("Error in cron task format \"" + eBackup.getPlugin().crontask + "\": Day of the Month (4) must be between 1 and 31");
            if (month != -1 && (month < 1 || month > 12))
                throw new IllegalArgumentException("Error in cron task format \"" + eBackup.getPlugin().crontask + "\": Month (5) must be between 1 and 12");
            if (week != -1 && (week < 1 || week > 7))
                throw new IllegalArgumentException("Error in cron task format \"" + eBackup.getPlugin().crontask + "\": Day of the Week (6) must be between 1 and 7");
        } else {
            throw new IllegalArgumentException("Invalid cron task format \"" + eBackup.getPlugin().crontask + "\"");
        }
    }

    public static boolean run() {
        Calendar cal = Calendar.getInstance();
        if (second != -1 && second != cal.get(Calendar.SECOND))
            return false;
        if (minute != -1 && minute != cal.get(Calendar.MINUTE))
            return false;
        if (hour != -1 && hour != cal.get(Calendar.HOUR_OF_DAY))
            return false;
        if (day != -1 && day != cal.get(Calendar.DATE))
            return false;
        if (month != -1 && month != cal.get(Calendar.MONTH))
            return false;
        if (week != -1 && week != cal.get(Calendar.DAY_OF_WEEK))
            return false;
        return true;
    }
}
