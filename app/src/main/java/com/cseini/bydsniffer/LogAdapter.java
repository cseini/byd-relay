package com.cseini.bydsniffer;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
    private List<LogEntry> logs = new ArrayList<>();
    private EventFrequencyTracker frequencyTracker;

    public LogAdapter(EventFrequencyTracker frequencyTracker) {
        this.frequencyTracker = frequencyTracker;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView textView = new TextView(parent.getContext());
        textView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        textView.setPadding(16, 8, 16, 8);
        textView.setTextSize(12);
        return new LogViewHolder(textView);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry entry = logs.get(position);
        String displayText = entry.getDisplayLine();

        int textColor = Color.BLACK;
        if (entry.event.contains("LIGHT") || entry.event.contains("Light") || entry.event.contains("TurnLight")) {
            textColor = Color.BLUE;
        } else if (entry.device.equalsIgnoreCase("impact")) {
            textColor = Color.RED;
        } else if (frequencyTracker != null && entry.args.length > 0) {
            if (frequencyTracker.isRareToggle(entry.device, entry.event, entry.args[0],
                entry.args.length > 1 ? entry.args[1] : "")) {
                textColor = Color.RED;
            }
        }

        holder.textView.setText(displayText);
        holder.textView.setTextColor(textColor);
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    public void setLogs(List<LogEntry> newLogs) {
        this.logs = new ArrayList<>(newLogs);
        notifyDataSetChanged();
    }

    public static class LogViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            this.textView = (TextView) itemView;
        }
    }
}
