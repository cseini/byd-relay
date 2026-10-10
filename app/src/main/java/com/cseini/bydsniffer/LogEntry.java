package com.cseini.bydsniffer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogEntry {
    public long timestampMs;
    public String isoTime;
    public String device;
    public String event;
    public String[] args;

    public LogEntry(long timestampMs, String device, String event, String... args) {
        this.timestampMs = timestampMs;
        this.device = device;
        this.event = event;
        this.args = args;

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        this.isoTime = sdf.format(new Date(timestampMs));
    }

    public String getDisplayLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(isoTime).append(" | ").append(device).append(" | ").append(event);
        if (args != null && args.length > 0) {
            sb.append(" | ");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(args[i]);
            }
        }
        return sb.toString();
    }

    public String getCSVLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestampMs).append(",").append(isoTime).append(",")
          .append(device).append(",").append(event);
        if (args != null && args.length > 0) {
            for (String arg : args) {
                sb.append(",").append(escapeCSV(arg));
            }
        }
        return sb.toString();
    }

    private static String escapeCSV(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
