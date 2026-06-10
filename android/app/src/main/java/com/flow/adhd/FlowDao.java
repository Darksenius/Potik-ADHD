package com.flow.adhd;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface FlowDao {

    @Query("SELECT json FROM app_state WHERE id = 1")
    String getStateJson();

    @Query("SELECT notif_json FROM app_state WHERE id = 1")
    String getNotifJson();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveState(AppState state);

    @Query("SELECT * FROM pending_events ORDER BY created_at ASC")
    List<PendingEvent> getPendingEvents();

    @Insert
    void insertEvent(PendingEvent event);

    @Query("DELETE FROM pending_events")
    void clearEvents();
}
