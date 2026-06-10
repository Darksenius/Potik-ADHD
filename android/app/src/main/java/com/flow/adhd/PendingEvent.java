package com.flow.adhd;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pending_events")
public class PendingEvent {
    @PrimaryKey(autoGenerate = true)
    public long id = 0;

    @ColumnInfo(name = "event")
    public String event;

    @ColumnInfo(name = "created_at")
    public long createdAt = System.currentTimeMillis();

    public PendingEvent(String event) {
        this.event = event;
    }
}
