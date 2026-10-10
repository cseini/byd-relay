package com.cseini.bydsniffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventLoggerSingleton {
    private static EventLoggerSingleton instance;
    private List<LogEntry> logs = new CopyOnWriteArrayList<>();
    private List<LogChangeListener> listeners = new CopyOnWriteArrayList<>();
    private String attachedDevices = "";
    private String skippedDevices = "";
    private String csvFilePath = "";

    public interface LogChangeListener {
        void onLogsChanged();
    }

    private EventLoggerSingleton() {
    }

    public static synchronized EventLoggerSingleton getInstance() {
        if (instance == null) {
            instance = new EventLoggerSingleton();
        }
        return instance;
    }

    public void addLog(LogEntry entry) {
        logs.add(0, entry);
        if (logs.size() > 10000) {
            logs.remove(logs.size() - 1);
        }
        notifyListeners();
    }

    public List<LogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    public void clearLogs() {
        logs.clear();
        notifyListeners();
    }

    public void addListener(LogChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LogChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (LogChangeListener listener : listeners) {
            listener.onLogsChanged();
        }
    }

    public void setDeviceStatus(String attached, String skipped) {
        this.attachedDevices = attached;
        this.skippedDevices = skipped;
        notifyListeners();
    }

    public String getAttachedDevices() {
        return attachedDevices;
    }

    public String getSkippedDevices() {
        return skippedDevices;
    }

    public void setCSVPath(String path) {
        this.csvFilePath = path;
        notifyListeners();
    }

    public String getCSVPath() {
        return csvFilePath;
    }

    public void addImpactAlert(long timestampMs, float peakG) {
        LogEntry entry = new LogEntry(timestampMs, "impact", "IMPACT", new String[]{"g=" + peakG});
        addLog(entry);
    }
}
