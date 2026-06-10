package com.flow.adhd;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_state")
public class AppState {
    @PrimaryKey
    public int id = 1;

    @ColumnInfo(name = "json")
    public String json = "";

    @ColumnInfo(name = "notif_json")
    public String notifJson = "";

    @ColumnInfo(name = "updated_at")
    public long updatedAt = System.currentTimeMillis();

    public AppState(String json, String notifJson) {
        this.json = json;
        this.notifJson = notifJson;
        this.updatedAt = System.currentTimeMillis();
    }
}
