package com.cseini.bydsniffer;

import java.util.HashMap;
import java.util.Map;

public class EventFrequencyTracker {
    private static class EventKey {
        String device;
        String event;
        String mainArg;

        EventKey(String device, String event, String mainArg) {
            this.device = device;
            this.event = event;
            this.mainArg = mainArg;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EventKey)) return false;
            EventKey ek = (EventKey) o;
            return device.equals(ek.device) && event.equals(ek.event) && mainArg.equals(ek.mainArg);
        }

        @Override
        public int hashCode() {
            return (device + "|" + event + "|" + mainArg).hashCode();
        }
    }

    private Map<EventKey, Map<String, Integer>> frequencyMap = new HashMap<>();
    private int totalCount = 0;

    public void track(String device, String event, String mainArg, String value) {
        EventKey key = new EventKey(device, event, mainArg);
        if (!frequencyMap.containsKey(key)) {
            frequencyMap.put(key, new HashMap<>());
        }
        Map<String, Integer> valueMap = frequencyMap.get(key);
        valueMap.put(value, valueMap.getOrDefault(value, 0) + 1);
        totalCount++;
    }

    public boolean isRareToggle(String device, String event, String mainArg, String value) {
        EventKey key = new EventKey(device, event, mainArg);
        if (!frequencyMap.containsKey(key)) return false;

        Map<String, Integer> valueMap = frequencyMap.get(key);
        int count = valueMap.getOrDefault(value, 0);
        int occurrencePercentage = totalCount > 0 ? (count * 100) / totalCount : 0;
        return occurrencePercentage < 10;
    }
}
