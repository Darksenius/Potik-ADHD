package com.flow.adhd;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface FlowDao {

    @Query("SELECT json FROM app_state WHERE id = 1")
    String getStateJson();

    @Query("SELECT notif_json FROM app_state WHERE id = 1")
    String getNotifJson();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveState(AppState state);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertStateIfMissing(AppState state);

    @Query("UPDATE app_state SET json = :json WHERE id = 1")
    void updateStateJson(String json);

    @Query("UPDATE app_state SET notif_json = :json WHERE id = 1")
    void updateNotifJson(String json);

    @Transaction
    default void writeState(String json) {
        insertStateIfMissing(new AppState("", ""));
        updateStateJson(json);
    }

    @Transaction
    default void writeNotif(String json) {
        insertStateIfMissing(new AppState("", ""));
        updateNotifJson(json);
    }

    @Query("DELETE FROM pending_events WHERE id IN (:ids)")
    void acknowledgeEvents(List<Long> ids);

    /** The state and exact commands it includes always survive or roll back together. */
    @Transaction
    default void commitEvents(String json, List<Long> ids) {
        writeState(json);
        acknowledgeEvents(ids);
    }

    @Query("SELECT * FROM pending_events ORDER BY id ASC")
    List<PendingEvent> getPendingEvents();

    @Insert
    void insertEvent(PendingEvent event);

    @Query("DELETE FROM pending_events")
    void clearEvents();

    /** Видалити лише вже прочитані події (id <= maxId) — щоб не втратити ті, що
     *  прилетіли між читанням і очищенням (напр. нотатка зі шторки). */
    @Query("DELETE FROM pending_events WHERE id <= :maxId")
    void clearEventsUpTo(long maxId);
}
