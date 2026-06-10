package com.flow.adhd;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
    entities = {AppState.class, PendingEvent.class},
    version = 1,
    exportSchema = false
)
public abstract class FlowDatabase extends RoomDatabase {

    public abstract FlowDao flowDao();

    private static volatile FlowDatabase INSTANCE;

    public static FlowDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (FlowDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            FlowDatabase.class,
                            "flow_db"
                    )
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
