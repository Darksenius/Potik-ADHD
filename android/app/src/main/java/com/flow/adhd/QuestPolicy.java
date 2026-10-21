package com.flow.adhd;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Shared native projection and eligibility rules for the foreground quest cards. */
final class QuestPolicy {
    static final int MAX_CARDS = 5;
    static final String DEFERRAL_NEXT_ZONE = "next-zone";
    static final String DEFERRAL_NOT_TODAY = "not-today";
    static final String DEFERRAL_TIME = "time";
    private static final long MINUTE_MS = 60_000L;
    private static final long SCHEDULED_DEFERRAL_MS = 60L * MINUTE_MS;
    private static volatile WallTimelineCache cachedWallTimeline;

    private QuestPolicy() {}

    static final class Segment {
        final int zoneId;
        final long startAt;
        final long endAt;
        Segment(int zoneId, long startAt, long endAt) {
            this.zoneId = zoneId; this.startAt = startAt; this.endAt = endAt;
        }
    }

    static final class Deferral {
        final String kind;
        final long requestedAt;
        final int sourceZoneId;
        final int targetZoneId;
        final long startAt;
        final Long endAt;
        Deferral(String kind, long requestedAt, int sourceZoneId, int targetZoneId, long startAt, Long endAt) {
            this.kind = kind; this.requestedAt = requestedAt; this.sourceZoneId = sourceZoneId;
            this.targetZoneId = targetZoneId; this.startAt = startAt; this.endAt = endAt;
        }
        String toJson() {
            StringBuilder json = new StringBuilder(128);
            json.append("{\"kind\":\"").append(kind).append("\",\"requestedAt\":").append(requestedAt)
                    .append(",\"sourceZoneId\":").append(sourceZoneId)
                    .append(",\"targetZoneId\":").append(targetZoneId)
                    .append(",\"startAt\":").append(startAt);
            if (endAt != null) json.append(",\"endAt\":").append(endAt);
            return json.append('}').toString();
        }
        String asEvent(String taskId) { return "task_defer:" + taskId + ":" + toJson(); }
    }

    static final class Task {
        String id = "";
        String title = "Задача";
        String type = "simple";
        String repeat = "none";
        String created = "";
        String doneDate = "";
        String doneAt = "";
        String occurrenceId = "";
        String completionCreditDate = "";
        String planDate = "";
        String schedDate = "";
        String schedTime = "";
        String alarmTime = "";
        String windowStart = "";
        String windowEnd = "";
        String zoneOccurrenceKey = "";
        String counterUnit = "count";
        String counterDay = "";
        int zoneId;
        int priorityIndex = -1;
        int counter;
        int counterTarget;
        int counterStep = 1;
        long nextRepeatAt;
        long repeatMs;
        long snoozeUntil;
        int remindBeforeMinutes;
        boolean hasZone;
        boolean done;
        boolean trashed;
        boolean someday;
        boolean completedToday;
        boolean zoneDoneToday;
        boolean counterEnabled;
        boolean reminderEnabled = true;
        boolean firedSched;
        boolean firedPre;
        boolean alarmFired;
        boolean completionCredited;
        boolean[] repeatDays = new boolean[7];
        Deferral deferral;
        Deferral completionDeferral;

        static Builder builder(String id) { return new Builder(id); }
        Task copy() {
            Task c = new Task();
            c.id=id; c.title=title; c.type=type; c.repeat=repeat; c.created=created; c.doneDate=doneDate;
            c.doneAt=doneAt; c.occurrenceId=occurrenceId;
            c.completionCreditDate=completionCreditDate;
            c.planDate=planDate; c.schedDate=schedDate; c.schedTime=schedTime; c.alarmTime=alarmTime;
            c.windowStart=windowStart; c.windowEnd=windowEnd; c.zoneOccurrenceKey=zoneOccurrenceKey;
            c.counterUnit=counterUnit; c.counterDay=counterDay; c.zoneId=zoneId; c.priorityIndex=priorityIndex; c.counter=counter;
            c.counterTarget=counterTarget; c.counterStep=counterStep; c.repeatMs=repeatMs; c.nextRepeatAt=nextRepeatAt;
            c.snoozeUntil=snoozeUntil; c.hasZone=hasZone; c.done=done; c.trashed=trashed; c.someday=someday;
            c.completedToday=completedToday; c.zoneDoneToday=zoneDoneToday; c.counterEnabled=counterEnabled;
            c.reminderEnabled=reminderEnabled; c.firedSched=firedSched; c.alarmFired=alarmFired;
            c.remindBeforeMinutes=remindBeforeMinutes; c.firedPre=firedPre;
            c.completionCredited=completionCredited;
            c.repeatDays=repeatDays.clone(); c.deferral=deferral; c.completionDeferral=completionDeferral;
            return c;
        }
        static Task fromJson(JSONObject row) {
            if (row == null) return null;
            Task t = new Task();
            t.id=row.optString("id", ""); t.title=row.optString("title", "Задача");
            t.type=row.optString("type", "simple"); t.repeat=row.optString("repeat", "none");
            t.created=row.optString("created", ""); t.doneDate=row.optString("doneDate", "");
            t.doneAt=row.optString("doneAt", ""); t.occurrenceId=row.optString("occurrenceId", "");
            t.completionCreditDate=row.optString("completionCreditDate", "");
            t.planDate=row.optString("planDate", ""); t.schedDate=row.optString("schedDate", "");
            t.schedTime=row.optString("schedTime", ""); t.alarmTime=row.optString("alarmTime", "");
            t.windowStart=row.optString("windowStart", ""); t.windowEnd=row.optString("windowEnd", "");
            t.zoneOccurrenceKey=row.optString("zoneOccurrenceKey", "");
            t.counterUnit=row.optString("counterUnit", "count"); t.counterDay=row.optString("counterDay", ""); t.zoneId=row.optInt("zoneId", 0);
            t.hasZone=!row.isNull("zoneId") && t.zoneId>0; t.priorityIndex=row.optInt("priorityIndex", -1);
            t.counter=Math.max(0, row.optInt("counter", 0)); t.counterTarget=Math.max(0, row.optInt("counterTarget", 0));
            t.counterStep=Math.max(1, row.optInt("counterStep", 1)); t.repeatMs=Math.max(0L, row.optLong("repeatMs", 0L));
            t.nextRepeatAt=row.optLong("nextRepeatAt", 0L);
            t.snoozeUntil=row.optLong("snoozeUntil", 0L); t.done=row.optBoolean("done", false);
            t.trashed=row.optBoolean("trashed", false); t.someday=row.optBoolean("someday", false);
            t.completedToday=row.optBoolean("completedToday", false); t.zoneDoneToday=row.optBoolean("zoneDoneToday", false);
            t.counterEnabled="counter".equals(t.type) || row.optBoolean("counterEnabled", false);
            t.reminderEnabled=!row.has("reminderEnabled")||row.optBoolean("reminderEnabled", true);
            t.firedSched=row.optBoolean("firedSched", false); t.alarmFired=row.optBoolean("alarmFired", false);
            t.firedPre=row.optBoolean("firedPre", false);
            t.completionCredited=row.optBoolean("completionCredited", false);
            t.remindBeforeMinutes=Math.max(0,row.optInt("remindBeforeMinutes", 0));
            JSONArray days=row.optJSONArray("repeatDays");
            if(days!=null) for(int i=0;i<Math.min(7,days.length());i++) t.repeatDays[i]=days.optBoolean(i,false);
            JSONObject d=row.optJSONObject("questDeferral"); if(d!=null) t.deferral=deferralFromJson(d);
            JSONObject completion=row.optJSONObject("completionDeferral");
            if(completion!=null) t.completionDeferral=deferralFromJson(completion);
            return t;
        }
    }

    static final class Builder {
        private final Task task = new Task();
        Builder(String id) { task.id=id; }
        Builder title(String v) { task.title=v; return this; }
        Builder type(String v) { task.type=v; return this; }
        Builder zone(int v) { task.hasZone=v>0; task.zoneId=v; return this; }
        Builder priority(int v) { task.priorityIndex=v; return this; }
        Builder planDate(String v) { task.planDate=v; return this; }
        Builder sched(String d,String t) { task.type="sched"; task.schedDate=d; task.schedTime=t; return this; }
        Builder alarm(String t) { task.type="alarm"; task.alarmTime=t; return this; }
        Builder timeWindow(String a,String b) { task.type="timewin"; task.windowStart=a; task.windowEnd=b; return this; }
        Builder repeat(String v) { task.repeat=v; return this; }
        Builder repeatDays(boolean... v) { task.repeatDays=new boolean[7]; System.arraycopy(v,0,task.repeatDays,0,Math.min(v.length,7)); return this; }
        Builder nextRepeatAt(long v) { task.nextRepeatAt=v; return this; }
        Builder repeatMs(long v) { task.repeatMs=v; return this; }
        Builder created(String v) { task.created=v; return this; }
        Builder counter(int v,int target) { task.type="counter"; task.counterEnabled=true; task.counter=v; task.counterTarget=target; return this; }
        Builder deferral(Deferral v) { task.deferral=v; return this; }
        Builder done(boolean v) { task.done=v; return this; }
        Builder trashed(boolean v) { task.trashed=v; return this; }
        Builder someday(boolean v) { task.someday=v; return this; }
        Builder completedToday(boolean v) { task.completedToday=v; return this; }
        Builder snoozeUntil(long v) { task.snoozeUntil=v; return this; }
        Task build() { return task; }
    }

    static final class Event {
        static final int DONE=1, SKIP=2, INCREMENT=3, DECREMENT=4, DEFER=5, CAPTURE_TASK=6, UNDO=7;
        final int type; final String taskId; final long createdAt; final Deferral deferral; final String captureTitle;
        final Undo undo;
        Event(int type,String taskId,long at,Deferral deferral) { this(type,taskId,at,deferral,"",null); }
        Event(int type,String taskId,long at,Deferral deferral,String captureTitle) { this(type,taskId,at,deferral,captureTitle,null); }
        Event(int type,String taskId,long at,Deferral deferral,String captureTitle,Undo undo) {
            this.type=type; this.taskId=taskId==null?"":taskId; this.createdAt=at;
            this.deferral=deferral; this.captureTitle=captureTitle==null?"":captureTitle; this.undo=undo;
        }
        static Event done(String id,long at) { return new Event(DONE,id,at,null); }
        static Event skip(String id,long at) { return new Event(SKIP,id,at,null); }
        static Event increment(String id,long at) { return new Event(INCREMENT,id,at,null); }
        static Event decrement(String id,long at) { return new Event(DECREMENT,id,at,null); }
        static Event defer(String id,long at,Deferral d) { return new Event(DEFER,id,at,d); }
        static Event captureTask(String id,String title,long at) { return new Event(CAPTURE_TASK,id,at,null,title); }
        static Event undo(Undo value) { return new Event(UNDO, value.id, value.undoneAt, null, "", value); }
    }

    static final class Undo {
        final String id, created, kind;
        final long sourceAt, undoneAt;
        final JSONObject before;
        Undo(String id,String created,String kind,long sourceAt,long undoneAt,JSONObject before) {
            this.id=id; this.created=created; this.kind=kind; this.sourceAt=sourceAt; this.undoneAt=undoneAt; this.before=before;
        }
    }

    static final class Selection {
        final boolean valid; final String hint; final int currentZoneId;
        final Segment currentSegment; final List<Task> tasks;
        private Selection(boolean valid,String hint,int zone,Segment current,List<Task> tasks) {
            this.valid=valid; this.hint=hint; this.currentZoneId=zone; this.currentSegment=current; this.tasks=tasks;
        }
        static Selection invalid(String hint) { return new Selection(false,hint,-1,null,Collections.<Task>emptyList()); }
    }

    static List<Task> tasksFromJson(JSONArray rows) {
        List<Task> result=new ArrayList<>(); if(rows==null)return result;
        for(int i=0;i<rows.length();i++){ Task t=Task.fromJson(rows.optJSONObject(i)); if(t!=null&&!t.id.isEmpty())result.add(t); }
        return result;
    }
    static List<Segment> timelineFromJson(JSONArray rows) {
        List<Segment> result=new ArrayList<>(); if(rows==null)return result;
        for(int i=0;i<rows.length();i++){ JSONObject r=rows.optJSONObject(i); if(r==null)continue; long a=r.optLong("startAt",0L),b=r.optLong("endAt",0L); if(a>0&&b>a)result.add(new Segment(r.optInt("zoneId",0),a,b)); }
        Collections.sort(result,Comparator.comparingLong(s->s.startAt)); return result;
    }

    /** Rebuild absolute segments from resolved local wall-clock profiles. */
    static List<Segment> timelineFromWallDays(JSONArray rows, TimeZone zone) {
        if (rows == null || zone == null) return Collections.emptyList();
        String source = rows.toString();
        long now = System.currentTimeMillis();
        long horizonStart = now - 2L * 24L * 60L * MINUTE_MS;
        long horizonEnd = now + 36L * 24L * 60L * MINUTE_MS;
        String zoneKey = zone.getID() + ":" + zone.getOffset(horizonStart) + ":"
                + zone.getOffset(now) + ":" + zone.getOffset(horizonEnd);
        WallTimelineCache cached = cachedWallTimeline;
        if (cached != null && cached.source.equals(source) && cached.zoneKey.equals(zoneKey)) return cached.timeline;
        synchronized (QuestPolicy.class) {
            cached = cachedWallTimeline;
            if (cached != null && cached.source.equals(source) && cached.zoneKey.equals(zoneKey)) return cached.timeline;
            List<Segment> built = buildTimelineFromWallDays(rows, zone);
            List<Segment> immutable = Collections.unmodifiableList(built);
            cachedWallTimeline = new WallTimelineCache(source, zoneKey, immutable);
            return immutable;
        }
    }

    private static List<Segment> buildTimelineFromWallDays(JSONArray rows, TimeZone zone) {
        List<WallDay> days = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject day = rows.optJSONObject(i);
            if (day == null) continue;
            String date = day.optString("date", "");
            if (parseDate(date, zone) == null) continue;
            JSONArray segments = day.optJSONArray("segments");
            if (segments == null) continue;
            List<WallRange> ranges = new ArrayList<>();
            for (int j = 0; j < segments.length(); j++) {
                JSONObject segment = segments.optJSONObject(j);
                if (segment == null) continue;
                int start = segment.optInt("startMinute", -1);
                int end = segment.optInt("endMinute", -1);
                if (start < 0 || start >= 1440 || end <= start || end > 1440) continue;
                ranges.add(new WallRange(segment.optInt("zoneId", 0), start, end));
            }
            days.add(new WallDay(date, ranges));
        }
        return buildTimelineFromWallDays(days, zone);
    }

    /** Pure wall-profile entry point used by JVM tests; ranges are already resolved by JS. */
    static List<Segment> timelineFromWallProfile(List<WallDay> days, TimeZone zone) {
        if (days == null || zone == null) return Collections.emptyList();
        return buildTimelineFromWallDays(days, zone);
    }

    private static List<Segment> buildTimelineFromWallDays(List<WallDay> days, TimeZone zone) {
        List<Segment> result = new ArrayList<>();
        List<WallDay> orderedDays = new ArrayList<>(days);
        Collections.sort(orderedDays, Comparator.comparing(d -> d.date));
        for (WallDay day : orderedDays) {
            Calendar start = parseDate(day.date, zone);
            if (start == null) continue;
            Calendar end = (Calendar) start.clone();
            end.add(Calendar.DAY_OF_YEAR, 1);
            long cursor = start.getTimeInMillis();
            long dayEnd = end.getTimeInMillis();
            List<WallRange> ranges = day.ranges;
            if (dayEnd - cursor == 24L * 60L * MINUTE_MS) {
                for (WallRange range : ranges) {
                    long from = wallMinuteInstant(start, dayEnd, range.start);
                    long to = range.end == 1440 ? dayEnd : wallMinuteInstant(start, dayEnd, range.end);
                    appendSegment(result, range.zoneId, from, to);
                }
                continue;
            }
            while (cursor < dayEnd) {
                Calendar local = Calendar.getInstance(zone, Locale.US);
                local.setTimeInMillis(cursor);
                int minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
                int zoneId = wallZoneAt(ranges, minute);
                long next = Math.min(cursor + MINUTE_MS, dayEnd);
                appendSegment(result, zoneId, cursor, next);
                cursor = next;
            }
        }
        return result;
    }

    private static long wallMinuteInstant(Calendar dayStart, long dayEnd, int minute) {
        if (minute >= 1440) return dayEnd;
        Calendar value = (Calendar) dayStart.clone();
        value.set(Calendar.HOUR_OF_DAY, minute / 60);
        value.set(Calendar.MINUTE, minute % 60);
        value.set(Calendar.SECOND, 0);
        value.set(Calendar.MILLISECOND, 0);
        return value.getTimeInMillis();
    }

    private static final class WallTimelineCache {
        final String source, zoneKey;
        final List<Segment> timeline;
        WallTimelineCache(String source, String zoneKey, List<Segment> timeline) {
            this.source=source; this.zoneKey=zoneKey; this.timeline=timeline;
        }
    }

    /** Prefer wall-clock profiles when present; old snapshots keep their epoch timeline. */
    static List<Segment> timelineForSnapshot(JSONObject snapshot, TimeZone zone) {
        if (snapshot == null) return Collections.emptyList();
        JSONArray wallDays = snapshot.optJSONArray("questWallDays");
        return wallDays == null ? timelineFromJson(snapshot.optJSONArray("questTimeline"))
                : timelineFromWallDays(wallDays, zone);
    }

    static long timelineUntil(JSONObject snapshot, List<Segment> timeline) {
        if (snapshot != null && snapshot.optJSONArray("questWallDays") == null)
            return snapshot.optLong("questTimelineUntil", 0L);
        return timeline == null || timeline.isEmpty() ? 0L : timeline.get(timeline.size() - 1).endAt;
    }

    /** Stable identity for a task action in one local day/primary-zone occurrence. */
    static String actionGeneration(Task task, List<Segment> timeline, long now, TimeZone zone) {
        if (task == null) return "";
        Segment current = segmentAt(timeline, now);
        StringBuilder value = new StringBuilder(256);
        appendIdentity(value, task.id); appendIdentity(value, task.created); appendIdentity(value, task.type);
        appendIdentity(value, task.planDate); appendIdentity(value, task.schedDate);
        appendIdentity(value, task.schedTime); appendIdentity(value, task.alarmTime);
        appendIdentity(value, task.repeat); appendIdentity(value, task.zoneId + ":" + task.hasZone);
        appendIdentity(value, task.repeatMs + ":" + task.nextRepeatAt + ":" + task.snoozeUntil);
        appendIdentity(value, task.reminderEnabled + ":" + task.remindBeforeMinutes);
        for (boolean day : task.repeatDays) appendIdentity(value, day ? "1" : "0");
        appendIdentity(value, task.deferral == null ? "" : task.deferral.toJson());
        appendIdentity(value, localDate(now, zone));
        appendIdentity(value, current == null ? "no-segment"
                : current.zoneId + ":" + current.startAt + ":" + current.endAt);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception unavailable) {
            return Integer.toHexString(value.toString().hashCode());
        }
    }

    private static void appendIdentity(StringBuilder out, String value) {
        out.append(value == null ? "" : value).append('\u0000');
    }

    static final class WallDay {
        final String date;
        final List<WallRange> ranges;
        WallDay(String date, List<WallRange> ranges) {
            this.date = date;
            this.ranges = ranges == null ? Collections.<WallRange>emptyList() : new ArrayList<>(ranges);
        }
    }

    static final class WallRange {
        final int zoneId, start, end;
        WallRange(int zoneId, int start, int end) { this.zoneId=zoneId; this.start=start; this.end=end; }
    }

    /**
     * Canonical assigned-zone occurrences, kept separate from the resolved
     * primary-zone segments. Overlapping zones can split a primary segment
     * without changing the assigned occurrence that owns a task completion.
     */
    static final class OccurrenceIndex {
        private final List<AssignedOccurrence> wallOccurrences;
        private final List<AbsoluteOccurrence> absoluteOccurrences;
        private final boolean wallClock;

        private OccurrenceIndex(List<AssignedOccurrence> wall, List<AbsoluteOccurrence> absolute,
                                boolean wallClock) {
            wallOccurrences = wall;
            absoluteOccurrences = absolute;
            this.wallClock = wallClock;
        }

        static OccurrenceIndex fromAssignedRanges(List<AssignedOccurrence> ranges) {
            return new OccurrenceIndex(ranges == null ? Collections.<AssignedOccurrence>emptyList()
                    : new ArrayList<>(ranges), Collections.<AbsoluteOccurrence>emptyList(), true);
        }

        static OccurrenceIndex fromAbsoluteRanges(List<AbsoluteOccurrence> ranges) {
            return new OccurrenceIndex(Collections.<AssignedOccurrence>emptyList(),
                    ranges == null ? Collections.<AbsoluteOccurrence>emptyList() : new ArrayList<>(ranges), false);
        }

        static OccurrenceIndex fromSnapshot(JSONObject snapshot, TimeZone zone) {
            if (snapshot == null) return null;
            JSONArray days = snapshot.optJSONArray("questWallDays");
            List<AssignedOccurrence> wall = new ArrayList<>();
            boolean hasWallContract = false;
            if (days != null) {
                for (int i = 0; i < days.length(); i++) {
                    JSONObject day = days.optJSONObject(i);
                    if (day == null || !day.has("assignedOccurrences")) continue;
                    hasWallContract = true;
                    String date = day.optString("date", "");
                    if (parseDate(date, zone) == null) continue;
                    JSONArray rows = day.optJSONArray("assignedOccurrences");
                    if (rows == null) continue;
                    for (int j = 0; j < rows.length(); j++) {
                        JSONObject row = rows.optJSONObject(j);
                        if (row == null) continue;
                        int zoneId = row.optInt("zoneId", -1);
                        int start = row.optInt("startMinute", -1);
                        int end = row.optInt("endMinute", -1);
                        if (zoneId < 0 || start < 0 || start >= 1440 || end <= start || end > 1440) continue;
                        wall.add(new AssignedOccurrence(date, zoneId, start, end));
                    }
                }
            }
            if (hasWallContract) return new OccurrenceIndex(wall, Collections.<AbsoluteOccurrence>emptyList(), true);

            JSONArray absoluteRows = snapshot.optJSONArray("questZoneOccurrences");
            if (absoluteRows == null) return null;
            List<AbsoluteOccurrence> absolute = new ArrayList<>();
            for (int i = 0; i < absoluteRows.length(); i++) {
                JSONObject row = absoluteRows.optJSONObject(i);
                if (row == null) continue;
                int zoneId = row.optInt("zoneId", -1);
                long start = row.optLong("startAt", 0L), end = row.optLong("endAt", 0L);
                String key = row.optString("key", "");
                if (zoneId >= 0 && start > 0 && end > start && !key.isEmpty())
                    absolute.add(new AbsoluteOccurrence(zoneId, start, end, key));
            }
            return new OccurrenceIndex(Collections.<AssignedOccurrence>emptyList(), absolute, false);
        }

        String keyAt(int zoneId, long at, TimeZone zone) {
            if (zoneId < 0 || at <= 0) return "";
            if (!wallClock) {
                for (AbsoluteOccurrence occurrence : absoluteOccurrences)
                    if (occurrence.zoneId == zoneId && at >= occurrence.startAt && at < occurrence.endAt)
                        return occurrence.key;
                return "";
            }
            TimeZone localZone = zone == null ? TimeZone.getDefault() : zone;
            Calendar calendar = Calendar.getInstance(localZone, Locale.US);
            calendar.setTimeInMillis(at);
            String date = localDate(at, localZone);
            int minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
            for (AssignedOccurrence occurrence : wallOccurrences) {
                if (occurrence.zoneId == zoneId && occurrence.date.equals(date)
                        && minute >= occurrence.startMinute && minute < occurrence.endMinute) {
                    return date + ":" + zoneId + ":" + String.format(Locale.US, "%02d:%02d",
                            occurrence.startMinute / 60, occurrence.startMinute % 60);
                }
            }
            return "";
        }
    }

    static final class AssignedOccurrence {
        final String date;
        final int zoneId, startMinute, endMinute;
        AssignedOccurrence(String date, int zoneId, int startMinute, int endMinute) {
            this.date = date; this.zoneId = zoneId; this.startMinute = startMinute; this.endMinute = endMinute;
        }
    }

    static final class AbsoluteOccurrence {
        final int zoneId;
        final long startAt, endAt;
        final String key;
        AbsoluteOccurrence(int zoneId, long startAt, long endAt, String key) {
            this.zoneId = zoneId; this.startAt = startAt; this.endAt = endAt; this.key = key;
        }
    }

    private static int wallZoneAt(List<WallRange> ranges, int minute) {
        if (ranges != null) for (WallRange range : ranges)
            if (minute >= range.start && minute < range.end) return range.zoneId;
        return 0;
    }

    private static void appendSegment(List<Segment> result, int zoneId, long start, long end) {
        if (end <= start) return;
        if (!result.isEmpty()) {
            Segment previous = result.get(result.size() - 1);
            if (previous.zoneId == zoneId && previous.endAt == start) {
                result.set(result.size() - 1, new Segment(zoneId, previous.startAt, end));
                return;
            }
        }
        result.add(new Segment(zoneId, start, end));
    }
    static List<Event> eventsFromJson(JSONArray rows) {
        List<Event> result=new ArrayList<>(); if(rows==null)return result;
        for(int i=0;i<rows.length();i++){ JSONObject r=rows.optJSONObject(i); if(r!=null){ Event e=eventFromEnvelope(r); if(e!=null)result.add(e); } }
        return result;
    }
    private static Event eventFromEnvelope(JSONObject row) {
        String raw=row.optString("event",""); long at=row.optLong("createdAt",0L);
        String capturePrefix="capture_task_v2:";
        if(raw.startsWith(capturePrefix)){
            int cut=raw.indexOf(':',capturePrefix.length());
            if(cut<=capturePrefix.length()||cut==raw.length()-1)return null;
            String id=raw.substring(capturePrefix.length(),cut);
            if(!id.matches("[0-9]{1,16}"))return null;
            try{
                long numericId=Long.parseLong(id);
                if(numericId<=0||numericId>=9_007_199_254_740_991L)return null;
                JSONObject payload=new JSONObject(raw.substring(cut+1));
                String title=payload.optString("title","");
                long capturedAt=payload.optLong("createdAt",at);
                if(title.trim().isEmpty()||capturedAt<=0)return null;
                return Event.captureTask(id,title,capturedAt);
            }catch(Exception ignored){return null;}
        }
        if(raw.startsWith("task_done:"))return Event.done(raw.substring(10),at);
        if(raw.startsWith("task_skip:"))return Event.skip(raw.substring(10),at);
        if(raw.startsWith("task_inc:"))return Event.increment(raw.substring(9),at);
        if(raw.startsWith("task_dec:"))return Event.decrement(raw.substring(9),at);
        String prefix="task_defer:";
        if(raw.startsWith(prefix)){ int cut=raw.indexOf(':',prefix.length()); if(cut<=prefix.length()||cut==raw.length()-1)return null; try{return Event.defer(raw.substring(prefix.length(),cut),at,deferralFromJson(new JSONObject(raw.substring(cut+1))));}catch(Exception ignored){return null;} }
        if(raw.startsWith("task_undo:")) {
            try {
                JSONObject json = new JSONObject(raw.substring("task_undo:".length()));
                String rawId = json.optString("id", "");
                if (!rawId.matches("[1-9][0-9]{0,15}")) return null;
                long numericId = Long.parseLong(rawId);
                if (numericId >= 9_007_199_254_740_991L) return null;
                String created = json.optString("created", "");
                String kind = json.optString("kind", "");
                long sourceAt = json.optLong("sourceAt", 0L);
                long undoneAt = json.optLong("undoneAt", 0L);
                JSONObject before = json.optJSONObject("before");
                if (created.isEmpty() || created.length() > 128 || !("done".equals(kind) || "defer".equals(kind))
                        || sourceAt <= 0 || undoneAt < sourceAt || undoneAt - sourceAt > 10_000L
                        || at <= 0 || Math.abs(at - undoneAt) > 1000L || !validUndoBefore(before)
                        || "done".equals(kind) && !Boolean.FALSE.equals(before.opt("done"))) return null;
                return Event.undo(new Undo(rawId, created, kind, sourceAt, undoneAt, before));
            } catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static final String[] UNDO_RUNTIME_FIELDS = {"done", "doneDate", "doneAt", "questDeferral",
            "completionDeferral", "snoozeUntil", "firedSched", "firedPre", "alarmFired", "completedToday",
            "zoneDoneToday", "nextRepeatAt", "occurrenceId", "completionCredited", "completionCreditDate"};

    private static boolean validUndoBefore(JSONObject before) {
        if (before == null || before.length() != UNDO_RUNTIME_FIELDS.length) return false;
        for (String field : UNDO_RUNTIME_FIELDS) if (!before.has(field)) return false;
        if (!nullableBoolean(before, "done") || !nullableBoolean(before, "firedSched")
                || !nullableBoolean(before, "firedPre") || !nullableBoolean(before, "alarmFired")
                || !nullableBoolean(before, "completedToday") || !nullableBoolean(before, "zoneDoneToday")
                || !nullableBoolean(before, "completionCredited")) return false;
        if (!nullableString(before, "doneDate") || !nullableString(before, "doneAt")
                || !nullableString(before, "occurrenceId") || !nullableString(before, "completionCreditDate")) return false;
        if (!nullableNumber(before, "snoozeUntil") || !nullableNumber(before, "nextRepeatAt")) return false;
        if (!nullableDeferral(before, "questDeferral") || !nullableDeferral(before, "completionDeferral")) return false;
        return true;
    }

    private static boolean nullableBoolean(JSONObject object,String key) {
        Object value=object.opt(key); return value==null||value==JSONObject.NULL||value instanceof Boolean;
    }
    private static boolean nullableString(JSONObject object,String key) {
        Object value=object.opt(key); return value==null||value==JSONObject.NULL||value instanceof String;
    }
    private static boolean nullableNumber(JSONObject object,String key) {
        Object value=object.opt(key); return value==null||value==JSONObject.NULL
                ||value instanceof Number&&Double.isFinite(((Number)value).doubleValue());
    }
    private static boolean nullableDeferral(JSONObject object,String key) {
        Object value=object.opt(key);
        return value==null||value==JSONObject.NULL||value instanceof JSONObject&&deferralFromJson((JSONObject)value)!=null;
    }

    /** Projects Room commands onto a copy so actions remain correct while WebView is closed. */
    static List<Task> projectPendingEvents(List<Task> source,List<Event> events,long now,List<Segment> timeline,TimeZone zone) {
        return projectPendingEvents(source, events, now, timeline, zone, null);
    }

    static List<Task> projectPendingEvents(List<Task> source,List<Event> events,long now,List<Segment> timeline,
                                           TimeZone zone,OccurrenceIndex occurrences) {
        List<Task> result=new ArrayList<>(); Map<String,Task> byId=new HashMap<>();
        String today=localDate(now,zone);
        Set<String> pendingDeferrals=new HashSet<>();
        if(events!=null)for(Event event:events)if(event!=null&&(event.type==Event.DEFER||event.type==Event.SKIP
                ||event.type==Event.UNDO&&event.undo!=null&&event.undo.before!=null
                &&event.undo.before.optJSONObject("questDeferral")!=null))pendingDeferrals.add(event.taskId);
        if(source!=null)for(Task row:source)if(row!=null){Task copy=row.copy();
            if(isCalendarCounter(copy)&&copy.deferral==null&&!pendingDeferrals.contains(copy.id)
                    &&!copy.counterDay.isEmpty()&&copy.counterDay.compareTo(today)<0)copy.counter=0;
            result.add(copy);byId.put(copy.id,copy);}
        // A quick task is represented by its durable capture command until JS
        // commits the same ID into AppState. Seed it before projecting later
        // Done/deferral commands so the whole pending command chain is visible.
        if(events!=null)for(Event event:events)if(event!=null&&event.type==Event.CAPTURE_TASK
                &&event.createdAt>0&&!event.taskId.isEmpty()&&!event.captureTitle.trim().isEmpty()
                &&!byId.containsKey(event.taskId)){
            Task captured=new Task();captured.id=event.taskId;captured.title=event.captureTitle;captured.type="simple";
            captured.created=isoUtc(event.createdAt);result.add(captured);byId.put(captured.id,captured);
        }
        if(events==null)return result;
        for(Event e:events){
            if(e==null||e.createdAt<=0)continue; Task t=byId.get(e.taskId);
            if(e.type==Event.UNDO) {
                if(t!=null&&e.undo!=null&&!createdAfter(t,e.undo.undoneAt,zone)
                        &&canApplyUndo(t,e.undo,zone)) applyUndo(t,e.undo);
                continue;
            }
            if(t==null||"negative".equals(t.type)||createdAfter(t,e.createdAt,zone)
                    ||isCurrentlyDone(t,now,timeline,zone,occurrences))continue;
            switch(e.type){
                case Event.DONE:
                    if(!completionApplies(t,e.createdAt,now,timeline,zone,occurrences))break;
                    t.completionDeferral=t.deferral;
                    t.done=true;t.doneDate=localDate(e.createdAt,zone);
                    t.doneAt=localTime(e.createdAt,zone);
                    if(!t.completionCredited)t.completionCreditDate=t.doneDate;
                    t.completionCredited=true;
                    t.occurrenceId=taskOccurrenceId(t,e.createdAt,timeline,zone,occurrences);
                    t.snoozeUntil=0L;t.deferral=null;
                    if("timewin".equals(t.type))t.completedToday=sameLocalDate(e.createdAt,now,zone);
                    if("zonelinked".equals(t.type)){
                        t.zoneDoneToday=sameLocalDate(e.createdAt,now,zone);
                        t.zoneOccurrenceKey=occurrenceKeyAt(t, e.createdAt, timeline, zone, occurrences);
                    }
                    if("everyzone".equals(t.repeat))
                        t.zoneOccurrenceKey=occurrenceKeyAt(t, e.createdAt, timeline, zone, occurrences);
                    if("interval".equals(t.repeat)&&t.repeatMs>0L&&e.createdAt<=Long.MAX_VALUE-t.repeatMs)
                        t.nextRepeatAt=e.createdAt+t.repeatMs;
                    break;
                // Legacy task_skip entries came from the old 15-minute action.
                // Interpret them as the current "Не зараз" contract instead.
                case Event.SKIP: {
                    Deferral moved=deferNextZone(t,timeline,e.createdAt,zone);
                    if(moved!=null)t.deferral=moved;
                    break;
                }
                case Event.INCREMENT: case Event.DECREMENT:
                    if(sameLocalDate(e.createdAt,now,zone)&&isCounter(t)){long d=e.type==Event.INCREMENT?t.counterStep:-t.counterStep;t.counter=(int)Math.max(0L,Math.min(Integer.MAX_VALUE,(long)t.counter+d));t.counterDay=localDate(e.createdAt,zone);} break;
                case Event.DEFER: if(e.deferral!=null){
                    t.occurrenceId=taskOccurrenceId(t,e.deferral.requestedAt,timeline,zone,occurrences);
                    t.deferral=e.deferral;t.completionDeferral=null;t.snoozeUntil=0L;t.firedSched=false;t.firedPre=false;t.alarmFired=false;
                }break;
                default: break;
            }
        }
        return result;
    }

    private static boolean canApplyUndo(Task task, Undo undo, TimeZone zone) {
        if (!task.id.equals(undo.id) || !task.created.equals(undo.created)) return false;
        if ("done".equals(undo.kind)) {
            if (!task.done || !task.doneDate.isEmpty() && !task.doneDate.equals(localDate(undo.sourceAt, zone))) return false;
            return task.doneAt.isEmpty() || task.doneAt.equals(localTime(undo.sourceAt, zone));
        }
        return task.deferral != null && task.deferral.requestedAt == undo.sourceAt;
    }

    private static void applyUndo(Task task, Undo undo) {
        JSONObject before=undo.before;
        task.done=before.optBoolean("done",false);
        task.doneDate=undoString(before,"doneDate"); task.doneAt=undoString(before,"doneAt");
        task.deferral=undoDeferral(before,"questDeferral");
        task.completionDeferral=undoDeferral(before,"completionDeferral");
        task.snoozeUntil=before.optLong("snoozeUntil",0L);
        task.firedSched=before.optBoolean("firedSched",false); task.firedPre=before.optBoolean("firedPre",false);
        task.alarmFired=before.optBoolean("alarmFired",false); task.completedToday=before.optBoolean("completedToday",false);
        task.zoneDoneToday=before.optBoolean("zoneDoneToday",false); task.nextRepeatAt=before.optLong("nextRepeatAt",0L);
        task.occurrenceId=undoString(before,"occurrenceId");
        // Native projection cannot reverse XP/history. Keep the current credit
        // marker until JS applies the same action snapshot and its gamification path.
    }

    private static String undoString(JSONObject json,String key) {
        Object value=json.opt(key); return value==null||value==JSONObject.NULL?"":String.valueOf(value);
    }

    private static Deferral undoDeferral(JSONObject json,String key) {
        JSONObject value=json.optJSONObject(key); return value==null?null:deferralFromJson(value);
    }

    private static String taskOccurrenceId(Task task,long at,List<Segment> timeline,TimeZone zone,
                                           OccurrenceIndex occurrences) {
        if (task.occurrenceId != null && !task.occurrenceId.isEmpty()) return task.occurrenceId;
        long origin = task.deferral == null ? at : task.deferral.requestedAt;
        String day = !task.counterDay.isEmpty() ? task.counterDay
                : !task.planDate.isEmpty() ? task.planDate : localDate(origin, zone);
        String suffix;
        if ("everyzone".equals(task.repeat)) {
            String zoneKey = task.zoneOccurrenceKey;
            if (zoneKey.isEmpty() && task.hasZone)
                zoneKey = occurrenceKeyAt(task, origin, timeline, zone, occurrences);
            suffix = "zone:" + (!zoneKey.isEmpty() ? zoneKey : day + ":" + task.zoneId);
        } else if ("interval".equals(task.repeat)) suffix = "interval:" + at;
        else if (task.repeat != null && !task.repeat.isEmpty() && !"none".equals(task.repeat)) suffix = "day:" + day;
        else suffix = "once";
        return task.id + "@" + task.created + "|" + suffix;
    }

    static String localTime(long at,TimeZone zone) {
        Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);
        c.setTimeInMillis(at);
        return String.format(Locale.US,"%02d:%02d",c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE));
    }

    static Selection select(List<Task> source,List<Segment> timeline,long timelineUntil,long now,TimeZone zone,int limit) {
        return select(source, timeline, timelineUntil, now, zone, limit, null);
    }

    static Selection select(List<Task> source,List<Segment> timeline,long timelineUntil,long now,TimeZone zone,
                            int limit,OccurrenceIndex occurrences) {
        if(timeline==null||timeline.isEmpty()||timelineUntil<=now)return Selection.invalid("Відкрий Потік, щоб оновити квести");
        Segment current=segmentAt(timeline,now); if(current==null)return Selection.invalid("Відкрий Потік, щоб оновити зони");
        List<Ranked> candidates=new ArrayList<>();
        if(source!=null)for(int i=0;i<source.size();i++){Task t=source.get(i);if(t!=null&&eligible(t,current,timeline,now,zone,occurrences))candidates.add(new Ranked(t,i));}
        Collections.sort(candidates,(a,b)->{int ap=a.task.priorityIndex<0?Integer.MAX_VALUE:a.task.priorityIndex;int bp=b.task.priorityIndex<0?Integer.MAX_VALUE:b.task.priorityIndex;int p=Integer.compare(ap,bp);return p!=0?p:Integer.compare(a.index,b.index);});
        List<Task> selected=new ArrayList<>();int max=Math.max(0,Math.min(MAX_CARDS,limit));for(Ranked r:candidates){if(selected.size()>=max)break;selected.add(r.task);}
        return new Selection(true,"",current.zoneId,current,selected);
    }
    private static final class Ranked {final Task task;final int index;Ranked(Task t,int i){task=t;index=i;}}

    private static boolean eligible(Task t,Segment current,List<Segment> timeline,long now,TimeZone zone,
                                     OccurrenceIndex occurrences) {
        if(t.id.isEmpty()||t.trashed||t.someday||"negative".equals(t.type)
                ||isCurrentlyDone(t,now,timeline,zone,occurrences))return false;
        if("everyzone".equals(t.repeat)&&!t.hasZone)return false;
        if("zonelinked".equals(t.type)&&!t.hasZone)return false;
        if(t.snoozeUntil>now)return false;
        // An explicit deferral describes one occurrence. During that primary
        // segment it bypasses the permanent date, recurrence, zone, and clock
        // gates; after the segment ends, those permanent rules resume.
        int deferred=deferralState(t.deferral,current,timeline,now);
        if(deferred>0)return true;
        if(deferred<0)return false;
        String today=localDate(now,zone),plan=taskPlanDate(t);if(plan!=null&&plan.compareTo(today)>0)return false;
        if(!repeatDue(t,today,now,current.zoneId,zone))return false;
        if(!zoneAllowed(t,current,now))return false;
        return timeGateAllows(t,today,now,zone);
    }

    /** Returns 1 while the one deferred occurrence is active, 0 after it, -1 while waiting. */
    private static int deferralState(Deferral d,Segment current,List<Segment> timeline,long now) {
        if(d==null)return 0;
        if(DEFERRAL_TIME.equals(d.kind))return now<d.startAt?-1:1;
        if(current==null||now<d.startAt)return -1;
        if(DEFERRAL_NEXT_ZONE.equals(d.kind)) {
            if(d.endAt==null||d.endAt<=d.startAt)return 0;
            if(now<d.endAt)return current.zoneId==d.targetZoneId?1:-1;
            return current.zoneId==d.sourceZoneId&&current.startAt>=d.endAt?1:-1;
        }
        if(DEFERRAL_NOT_TODAY.equals(d.kind)) {
            if(d.targetZoneId==0)return 1;
            // A merged primary-zone segment can begin before local midnight
            // and continue across the deferral boundary. Once the segment
            // overlaps startAt, the task is available in its assigned zone.
            return current.zoneId==d.targetZoneId&&current.endAt>d.startAt?1:-1;
        }
        return 0;
    }

    private static boolean zoneAllowed(Task t,Segment current,long now) {
        Deferral d=t.deferral;if(d==null)return !t.hasZone||t.zoneId==current.zoneId;
        if(DEFERRAL_NEXT_ZONE.equals(d.kind)){
            if(now<d.startAt)return false;
            if(d.endAt!=null&&now<d.endAt)return current.zoneId==d.targetZoneId;
            return d.endAt!=null&&current.zoneId==d.sourceZoneId&&current.startAt>=d.endAt;
        }
        if(DEFERRAL_NOT_TODAY.equals(d.kind)){if(now<d.startAt)return false;return !t.hasZone||current.zoneId==d.targetZoneId;}
        return !t.hasZone||t.zoneId==current.zoneId;
    }
    private static boolean timeGateAllows(Task t,String today,long now,TimeZone zone) {
        if("timewin".equals(t.type)&&!inTimeWindow(t.windowStart,t.windowEnd,now,zone))return false;
        if("sched".equals(t.type)) {
            // A repeating scheduled task keeps its original date as the
            // recurrence anchor, but its clock gate belongs to today's
            // occurrence. The anchor's minimum date was already enforced
            // before repeatDue(), so using it here would make yesterday's
            // clock time appear due just after midnight while the WebView is
            // closed.
            String occurrenceDate=calendarRepeat(t.repeat)?today:taskPlanDate(t);
            if(localDateTime(occurrenceDate,t.schedTime,zone)>now)return false;
        }
        if("alarm".equals(t.type)&&localDateTime(today,t.alarmTime,zone)>now)return false;
        return true;
    }

    private static boolean repeatDue(Task t,String today,long now,int currentZone,TimeZone zone) {
        String repeat=t.repeat;if(repeat==null||repeat.isEmpty()||"none".equals(repeat))return true;
        // Every-zone recurrence is a zone constraint, and may be overridden by
        // an active deferral just like any other permanent zone assignment.
        if("everyzone".equals(repeat)||"interval".equals(repeat))return true;
        if(!calendarRepeat(repeat))return true;
        String anchor=taskPlanDate(t);if(anchor==null)anchor=createdLocalDate(t.created,zone);if(anchor!=null&&today.compareTo(anchor)<0)return false;
        Calendar date=parseDate(today,zone);if(date==null)return false;int day=(date.get(Calendar.DAY_OF_WEEK)+5)%7;
        switch(repeat){
            case "daily":return true;
            case "weekly":Calendar first=anchor==null?parseCreated(t.created,zone):parseDate(anchor,zone);return first==null||first.get(Calendar.DAY_OF_WEEK)==date.get(Calendar.DAY_OF_WEEK);
            case "weekdays":return day<5;
            case "weekend":return day>=5;
            case "custom":return day<t.repeatDays.length&&t.repeatDays[day];
            default:return true;
        }
    }

    static boolean isCurrentlyDone(Task t,long now,List<Segment> timeline,TimeZone zone) {
        return isCurrentlyDone(t, now, timeline, zone, null);
    }

    static boolean isCurrentlyDone(Task t,long now,List<Segment> timeline,TimeZone zone,
                                   OccurrenceIndex occurrences) {
        String today=localDate(now,zone);
        String creditDate=t.doneDate.isEmpty()?t.completionCreditDate:t.doneDate;
        if("timewin".equals(t.type)&&t.completedToday&&(creditDate.isEmpty()||today.equals(creditDate)))return true;
        if("zonelinked".equals(t.type)&&t.zoneDoneToday){String key=occurrenceKeyAt(t,now,timeline,zone,occurrences);if(t.zoneOccurrenceKey.isEmpty()||t.zoneOccurrenceKey.equals(key))return true;}
        if(!t.done)return false;
        if(calendarRepeat(t.repeat)&&repeatDue(t,today,now,t.zoneId,zone))
            return creditDate.isEmpty()||today.compareTo(creditDate)<=0;
        if("everyzone".equals(t.repeat)) {
            String occurrence=occurrenceKeyAt(t,now,timeline,zone,occurrences);
            if(!t.zoneOccurrenceKey.isEmpty()&&!occurrence.isEmpty()&&!t.zoneOccurrenceKey.equals(occurrence))return false;
        }
        if("interval".equals(t.repeat)&&t.nextRepeatAt>0&&now>=t.nextRepeatAt)return false;
        // everyzone is reset by the app lifecycle when its next occurrence is materialized.
        return true;
    }
    static boolean completionApplies(Task t,long eventAt,long now,List<Segment> timeline,TimeZone zone) {
        return completionApplies(t, eventAt, now, timeline, zone, null);
    }

    static boolean completionApplies(Task t,long eventAt,long now,List<Segment> timeline,TimeZone zone,
                                     OccurrenceIndex occurrences) {
        if(t.done&&"interval".equals(t.repeat)&&t.nextRepeatAt>0&&eventAt<t.nextRepeatAt)return false;
        if(calendarRepeat(t.repeat))return sameLocalDate(eventAt,now,zone);
        if("everyzone".equals(t.repeat)||"zonelinked".equals(t.type)){
            if(!sameLocalDate(eventAt,now,zone))return false;
            if (occurrences != null) {
                String eventKey = occurrenceKeyAt(t, eventAt, timeline, zone, occurrences);
                String nowKey = occurrenceKeyAt(t, now, timeline, zone, occurrences);
                return !eventKey.isEmpty() && eventKey.equals(nowKey);
            }
            Segment a=segmentAt(timeline,eventAt),b=segmentAt(timeline,now);
            return a==null||b==null||(a.zoneId==b.zoneId&&a.startAt==b.startAt);
        }
        return true;
    }

    private static String occurrenceKeyAt(Task task,long at,List<Segment> timeline,TimeZone zone,
                                          OccurrenceIndex occurrences) {
        if (occurrences != null) return occurrences.keyAt(task.zoneId, at, zone);
        return occurrenceKey(task.zoneId, segmentAt(timeline, at), at, zone);
    }
    private static boolean createdAfter(Task t,long eventAt,TimeZone zone){long created=parseCreatedAt(t.created);return created>0&&eventAt<created;}

    static Deferral deferNextZone(Task task,List<Segment> timeline,long now,TimeZone zone) {
        Segment source=segmentAt(timeline,now);
        if(task!=null&&"sched".equals(task.type)) {
            if(now<=0||now>Long.MAX_VALUE-SCHEDULED_DEFERRAL_MS)return null;
            return new Deferral(DEFERRAL_TIME,now,source==null?0:source.zoneId,
                    task.hasZone?task.zoneId:0,now+SCHEDULED_DEFERRAL_MS,null);
        }
        if(source==null)return null;
        List<Segment> ordered=new ArrayList<>(timeline);Collections.sort(ordered,Comparator.comparingLong(s->s.startAt));
        for(Segment s:ordered){if(s.startAt<source.endAt||s.endAt<=now)continue;if(!sameLocalDate(s.startAt,now,zone))break;if(s.zoneId>0&&s.zoneId!=source.zoneId)return new Deferral(DEFERRAL_NEXT_ZONE,now,source.zoneId,s.zoneId,s.startAt,s.endAt);}
        return deferNotToday(task,timeline,now,zone);
    }
    static Deferral deferNotToday(Task task,List<Segment> timeline,long now,TimeZone zone) {
        Segment current=segmentAt(timeline,now);if(current==null)return null;int target=task!=null&&task.hasZone?task.zoneId:0;
        long start=target>0?nextLocalMidnight(now,zone):now+23L*60L*MINUTE_MS;
        return new Deferral(DEFERRAL_NOT_TODAY,now,current.zoneId,target,start,null);
    }
    static Segment segmentAt(List<Segment> timeline,long time){if(timeline!=null)for(Segment s:timeline)if(s!=null&&time>=s.startAt&&time<s.endAt)return s;return null;}

    static String localDate(long at,TimeZone zone){Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);c.setTimeInMillis(at);return String.format(Locale.US,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
    static long nextLocalMidnight(long now,TimeZone zone){Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);c.setTimeInMillis(now);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);c.add(Calendar.DAY_OF_YEAR,1);return c.getTimeInMillis();}
    static boolean sameLocalDate(long a,long b,TimeZone zone){return localDate(a,zone).equals(localDate(b,zone));}
    private static boolean calendarRepeat(String r){return "daily".equals(r)||"weekly".equals(r)||"weekdays".equals(r)||"weekend".equals(r)||"custom".equals(r);}
    private static boolean isCounter(Task t){return t!=null&&!"negative".equals(t.type)&&("counter".equals(t.type)||t.counterEnabled);}
    private static boolean isCalendarCounter(Task t){return isCounter(t)&&calendarRepeat(t.repeat);}
    static String taskPlanDate(Task t){if("sched".equals(t.type)&&validDate(t.schedDate))return t.schedDate;return validDate(t.planDate)?t.planDate:null;}
    private static boolean validDate(String d){return parseDate(d,TimeZone.getDefault())!=null;}
    private static Calendar parseDate(String d,TimeZone zone){if(d==null||!d.matches("\\d{4}-\\d{2}-\\d{2}"))return null;try{Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);c.clear();c.setLenient(false);c.set(Integer.parseInt(d.substring(0,4)),Integer.parseInt(d.substring(5,7))-1,Integer.parseInt(d.substring(8,10)),0,0,0);c.getTimeInMillis();return c;}catch(Exception ignored){return null;}}
    static String createdLocalDate(String created,TimeZone zone){Calendar c=parseCreated(created,zone);return c==null?null:String.format(Locale.US,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
    private static Calendar parseCreated(String created,TimeZone zone){long at=parseCreatedAt(created);if(at==Long.MIN_VALUE)return null;Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);c.setTimeInMillis(at);return c;}
    private static long parseCreatedAt(String created){if(created==null||created.isEmpty())return Long.MIN_VALUE;String[] patterns={"yyyy-MM-dd'T'HH:mm:ss.SSSX","yyyy-MM-dd'T'HH:mm:ssX"};for(String p:patterns){try{SimpleDateFormat f=new SimpleDateFormat(p,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));Date d=f.parse(created);if(d!=null)return d.getTime();}catch(ParseException ignored){}}return Long.MIN_VALUE;}
    static long localDateTime(String date,String hm,TimeZone zone){Calendar c=parseDate(date,zone);int[] t=parseTime(hm);if(c==null||t==null)return Long.MIN_VALUE;c.set(Calendar.HOUR_OF_DAY,t[0]);c.set(Calendar.MINUTE,t[1]);return c.getTimeInMillis();}
    private static int[] parseTime(String text){if(text==null||!text.matches("\\d{2}:\\d{2}"))return null;try{int h=Integer.parseInt(text.substring(0,2)),m=Integer.parseInt(text.substring(3,5));return h<=23&&m<=59?new int[]{h,m}:null;}catch(Exception ignored){return null;}}
    private static boolean inTimeWindow(String start,String end,long now,TimeZone zone){int[] a=parseTime(start),b=parseTime(end);if(a==null||b==null)return true;Calendar c=Calendar.getInstance(zone,Locale.US);c.setTimeInMillis(now);int m=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE),from=a[0]*60+a[1],to=b[0]*60+b[1];return from<=to?m>=from&&m<=to:m>=from||m<=to;}
    private static String occurrenceKey(int zoneId,Segment segment,long now,TimeZone zone){if(segment==null||zoneId<=0||segment.zoneId!=zoneId)return "";Calendar c=Calendar.getInstance(zone,Locale.US);c.setTimeInMillis(segment.startAt);return localDate(now,zone)+":"+zoneId+":"+String.format(Locale.US,"%02d:%02d",c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE));}
    private static String isoUtc(long at){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(at));}
    private static Deferral deferralFromJson(JSONObject row){
        String kind=row.optString("kind","");
        if(!DEFERRAL_NEXT_ZONE.equals(kind)&&!DEFERRAL_NOT_TODAY.equals(kind)&&!DEFERRAL_TIME.equals(kind))return null;
        long requested=row.optLong("requestedAt",0),start=row.optLong("startAt",0);
        Long end=row.has("endAt")&&!row.isNull("endAt")?row.optLong("endAt",0):null;
        int source=row.optInt("sourceZoneId",-1),target=row.optInt("targetZoneId",-1);
        if(requested<=0||start<=0||source<0||target<0)return null;
        if(DEFERRAL_TIME.equals(kind)&&(end!=null||start<=requested))return null;
        if(DEFERRAL_NEXT_ZONE.equals(kind)&&(end==null||end<=start))return null;
        if(DEFERRAL_NOT_TODAY.equals(kind)&&end!=null)return null;
        return new Deferral(kind,requested,source,target,start,end);
    }
}
