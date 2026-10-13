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

/** Shared native projection and eligibility rules for the foreground quest cards. */
final class QuestPolicy {
    static final int MAX_CARDS = 5;
    static final String DEFERRAL_NEXT_ZONE = "next-zone";
    static final String DEFERRAL_NOT_TODAY = "not-today";
    private static final long MINUTE_MS = 60_000L;

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
        long snoozeUntil;
        boolean hasZone;
        boolean done;
        boolean trashed;
        boolean someday;
        boolean completedToday;
        boolean zoneDoneToday;
        boolean counterEnabled;
        boolean reminderEnabled = true;
        boolean firedSched;
        boolean alarmFired;
        boolean[] repeatDays = new boolean[7];
        Deferral deferral;

        static Builder builder(String id) { return new Builder(id); }
        Task copy() {
            Task c = new Task();
            c.id=id; c.title=title; c.type=type; c.repeat=repeat; c.created=created; c.doneDate=doneDate;
            c.completionCreditDate=completionCreditDate;
            c.planDate=planDate; c.schedDate=schedDate; c.schedTime=schedTime; c.alarmTime=alarmTime;
            c.windowStart=windowStart; c.windowEnd=windowEnd; c.zoneOccurrenceKey=zoneOccurrenceKey;
            c.counterUnit=counterUnit; c.counterDay=counterDay; c.zoneId=zoneId; c.priorityIndex=priorityIndex; c.counter=counter;
            c.counterTarget=counterTarget; c.counterStep=counterStep; c.nextRepeatAt=nextRepeatAt;
            c.snoozeUntil=snoozeUntil; c.hasZone=hasZone; c.done=done; c.trashed=trashed; c.someday=someday;
            c.completedToday=completedToday; c.zoneDoneToday=zoneDoneToday; c.counterEnabled=counterEnabled;
            c.reminderEnabled=reminderEnabled; c.firedSched=firedSched; c.alarmFired=alarmFired;
            c.repeatDays=repeatDays.clone(); c.deferral=deferral;
            return c;
        }
        static Task fromJson(JSONObject row) {
            if (row == null) return null;
            Task t = new Task();
            t.id=row.optString("id", ""); t.title=row.optString("title", "Задача");
            t.type=row.optString("type", "simple"); t.repeat=row.optString("repeat", "none");
            t.created=row.optString("created", ""); t.doneDate=row.optString("doneDate", "");
            t.completionCreditDate=row.optString("completionCreditDate", "");
            t.planDate=row.optString("planDate", ""); t.schedDate=row.optString("schedDate", "");
            t.schedTime=row.optString("schedTime", ""); t.alarmTime=row.optString("alarmTime", "");
            t.windowStart=row.optString("windowStart", ""); t.windowEnd=row.optString("windowEnd", "");
            t.zoneOccurrenceKey=row.optString("zoneOccurrenceKey", "");
            t.counterUnit=row.optString("counterUnit", "count"); t.counterDay=row.optString("counterDay", ""); t.zoneId=row.optInt("zoneId", 0);
            t.hasZone=!row.isNull("zoneId") && t.zoneId>0; t.priorityIndex=row.optInt("priorityIndex", -1);
            t.counter=Math.max(0, row.optInt("counter", 0)); t.counterTarget=Math.max(0, row.optInt("counterTarget", 0));
            t.counterStep=Math.max(1, row.optInt("counterStep", 1)); t.nextRepeatAt=row.optLong("nextRepeatAt", 0L);
            t.snoozeUntil=row.optLong("snoozeUntil", 0L); t.done=row.optBoolean("done", false);
            t.trashed=row.optBoolean("trashed", false); t.someday=row.optBoolean("someday", false);
            t.completedToday=row.optBoolean("completedToday", false); t.zoneDoneToday=row.optBoolean("zoneDoneToday", false);
            t.counterEnabled="counter".equals(t.type) || row.optBoolean("counterEnabled", false);
            t.reminderEnabled=!row.has("reminderEnabled")||row.optBoolean("reminderEnabled", true);
            t.firedSched=row.optBoolean("firedSched", false); t.alarmFired=row.optBoolean("alarmFired", false);
            JSONArray days=row.optJSONArray("repeatDays");
            if(days!=null) for(int i=0;i<Math.min(7,days.length());i++) t.repeatDays[i]=days.optBoolean(i,false);
            JSONObject d=row.optJSONObject("questDeferral"); if(d!=null) t.deferral=deferralFromJson(d);
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
        static final int DONE=1, SKIP=2, INCREMENT=3, DECREMENT=4, DEFER=5, CAPTURE_TASK=6;
        final int type; final String taskId; final long createdAt; final Deferral deferral; final String captureTitle;
        Event(int type,String taskId,long at,Deferral deferral) { this(type,taskId,at,deferral,""); }
        Event(int type,String taskId,long at,Deferral deferral,String captureTitle) {
            this.type=type; this.taskId=taskId==null?"":taskId; this.createdAt=at;
            this.deferral=deferral; this.captureTitle=captureTitle==null?"":captureTitle;
        }
        static Event done(String id,long at) { return new Event(DONE,id,at,null); }
        static Event skip(String id,long at) { return new Event(SKIP,id,at,null); }
        static Event increment(String id,long at) { return new Event(INCREMENT,id,at,null); }
        static Event decrement(String id,long at) { return new Event(DECREMENT,id,at,null); }
        static Event defer(String id,long at,Deferral d) { return new Event(DEFER,id,at,d); }
        static Event captureTask(String id,String title,long at) { return new Event(CAPTURE_TASK,id,at,null,title); }
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
        return null;
    }

    /** Projects Room commands onto a copy so actions remain correct while WebView is closed. */
    static List<Task> projectPendingEvents(List<Task> source,List<Event> events,long now,List<Segment> timeline,TimeZone zone) {
        List<Task> result=new ArrayList<>(); Map<String,Task> byId=new HashMap<>();
        String today=localDate(now,zone);
        Set<String> pendingDeferrals=new HashSet<>();
        if(events!=null)for(Event event:events)if(event!=null&&(event.type==Event.DEFER||event.type==Event.SKIP))pendingDeferrals.add(event.taskId);
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
            if(t==null||"negative".equals(t.type)||createdAfter(t,e.createdAt,zone)||isCurrentlyDone(t,now,timeline,zone))continue;
            switch(e.type){
                case Event.DONE:
                    if(!completionApplies(t,e.createdAt,now,timeline,zone))break;
                    t.done=true;t.doneDate=localDate(e.createdAt,zone);
                    if("timewin".equals(t.type))t.completedToday=sameLocalDate(e.createdAt,now,zone);
                    if("zonelinked".equals(t.type))t.zoneDoneToday=sameLocalDate(e.createdAt,now,zone);
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
                case Event.DEFER: if(e.deferral!=null)t.deferral=e.deferral;break;
                default: break;
            }
        }
        return result;
    }

    static Selection select(List<Task> source,List<Segment> timeline,long timelineUntil,long now,TimeZone zone,int limit) {
        if(timeline==null||timeline.isEmpty()||timelineUntil<=now)return Selection.invalid("Відкрий Потік, щоб оновити квести");
        Segment current=segmentAt(timeline,now); if(current==null)return Selection.invalid("Відкрий Потік, щоб оновити зони");
        List<Ranked> candidates=new ArrayList<>();
        if(source!=null)for(int i=0;i<source.size();i++){Task t=source.get(i);if(t!=null&&eligible(t,current,timeline,now,zone))candidates.add(new Ranked(t,i));}
        Collections.sort(candidates,(a,b)->{int ap=a.task.priorityIndex<0?Integer.MAX_VALUE:a.task.priorityIndex;int bp=b.task.priorityIndex<0?Integer.MAX_VALUE:b.task.priorityIndex;int p=Integer.compare(ap,bp);return p!=0?p:Integer.compare(a.index,b.index);});
        List<Task> selected=new ArrayList<>();int max=Math.max(0,Math.min(MAX_CARDS,limit));for(Ranked r:candidates){if(selected.size()>=max)break;selected.add(r.task);}
        return new Selection(true,"",current.zoneId,current,selected);
    }
    private static final class Ranked {final Task task;final int index;Ranked(Task t,int i){task=t;index=i;}}

    private static boolean eligible(Task t,Segment current,List<Segment> timeline,long now,TimeZone zone) {
        if(t.id.isEmpty()||t.trashed||t.someday||"negative".equals(t.type)||isCurrentlyDone(t,now,timeline,zone))return false;
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
        if(current==null||now<d.startAt)return -1;
        if(DEFERRAL_NEXT_ZONE.equals(d.kind)) {
            if(d.endAt==null||d.endAt<=d.startAt)return 0;
            if(now<d.endAt)return current.zoneId==d.targetZoneId?1:-1;
            return current.zoneId==d.sourceZoneId&&current.startAt>=d.endAt?1:-1;
        }
        if(DEFERRAL_NOT_TODAY.equals(d.kind)) {
            if(d.targetZoneId==0)return 1;
            return current.zoneId==d.targetZoneId&&current.startAt>=d.startAt?1:-1;
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
        if("sched".equals(t.type)&&localDateTime(taskPlanDate(t),t.schedTime,zone)>now)return false;
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

    private static boolean isCurrentlyDone(Task t,long now,List<Segment> timeline,TimeZone zone) {
        String today=localDate(now,zone);
        String creditDate=t.doneDate.isEmpty()?t.completionCreditDate:t.doneDate;
        if("timewin".equals(t.type)&&t.completedToday&&(creditDate.isEmpty()||today.equals(creditDate)))return true;
        if("zonelinked".equals(t.type)&&t.zoneDoneToday){String key=occurrenceKey(t.zoneId,segmentAt(timeline,now),now,zone);if(t.zoneOccurrenceKey.isEmpty()||t.zoneOccurrenceKey.equals(key))return true;}
        if(!t.done)return false;
        if(calendarRepeat(t.repeat)&&repeatDue(t,today,now,t.zoneId,zone))
            return creditDate.isEmpty()||today.compareTo(creditDate)<=0;
        if("everyzone".equals(t.repeat)) {
            String occurrence=occurrenceKey(t.zoneId,segmentAt(timeline,now),now,zone);
            if(!t.zoneOccurrenceKey.isEmpty()&&!occurrence.isEmpty()&&!t.zoneOccurrenceKey.equals(occurrence))return false;
        }
        if("interval".equals(t.repeat)&&t.nextRepeatAt>0&&now>=t.nextRepeatAt)return false;
        // everyzone is reset by the app lifecycle when its next occurrence is materialized.
        return true;
    }
    private static boolean completionApplies(Task t,long eventAt,long now,List<Segment> timeline,TimeZone zone) {
        if(calendarRepeat(t.repeat))return sameLocalDate(eventAt,now,zone);
        if("everyzone".equals(t.repeat)||"zonelinked".equals(t.type)){
            if(!sameLocalDate(eventAt,now,zone))return false;Segment a=segmentAt(timeline,eventAt),b=segmentAt(timeline,now);
            return a==null||b==null||(a.zoneId==b.zoneId&&a.startAt==b.startAt);
        }
        return true;
    }
    private static boolean createdAfter(Task t,long eventAt,TimeZone zone){long created=parseCreatedAt(t.created);return created>0&&eventAt<created;}

    static Deferral deferNextZone(Task task,List<Segment> timeline,long now,TimeZone zone) {
        Segment source=segmentAt(timeline,now);if(source==null)return null;
        List<Segment> ordered=new ArrayList<>(timeline);Collections.sort(ordered,Comparator.comparingLong(s->s.startAt));
        for(Segment s:ordered){if(s.startAt<source.endAt||s.endAt<=now)continue;if(!sameLocalDate(s.startAt,now,zone))break;if(s.zoneId!=source.zoneId)return new Deferral(DEFERRAL_NEXT_ZONE,now,source.zoneId,s.zoneId,s.startAt,s.endAt);}
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
    private static String taskPlanDate(Task t){if("sched".equals(t.type)&&validDate(t.schedDate))return t.schedDate;return validDate(t.planDate)?t.planDate:null;}
    private static boolean validDate(String d){return parseDate(d,TimeZone.getDefault())!=null;}
    private static Calendar parseDate(String d,TimeZone zone){if(d==null||!d.matches("\\d{4}-\\d{2}-\\d{2}"))return null;try{Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);c.clear();c.setLenient(false);c.set(Integer.parseInt(d.substring(0,4)),Integer.parseInt(d.substring(5,7))-1,Integer.parseInt(d.substring(8,10)),0,0,0);c.getTimeInMillis();return c;}catch(Exception ignored){return null;}}
    private static String createdLocalDate(String created,TimeZone zone){Calendar c=parseCreated(created,zone);return c==null?null:String.format(Locale.US,"%04d-%02d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
    private static Calendar parseCreated(String created,TimeZone zone){long at=parseCreatedAt(created);if(at<=0)return null;Calendar c=Calendar.getInstance(zone==null?TimeZone.getDefault():zone,Locale.US);c.setTimeInMillis(at);return c;}
    private static long parseCreatedAt(String created){if(created==null||created.isEmpty())return 0;String[] patterns={"yyyy-MM-dd'T'HH:mm:ss.SSSX","yyyy-MM-dd'T'HH:mm:ssX"};for(String p:patterns){try{SimpleDateFormat f=new SimpleDateFormat(p,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));Date d=f.parse(created);if(d!=null)return d.getTime();}catch(ParseException ignored){}}return 0;}
    private static long localDateTime(String date,String hm,TimeZone zone){Calendar c=parseDate(date,zone);int[] t=parseTime(hm);if(c==null||t==null)return Long.MIN_VALUE;c.set(Calendar.HOUR_OF_DAY,t[0]);c.set(Calendar.MINUTE,t[1]);return c.getTimeInMillis();}
    private static int[] parseTime(String text){if(text==null||!text.matches("\\d{2}:\\d{2}"))return null;try{int h=Integer.parseInt(text.substring(0,2)),m=Integer.parseInt(text.substring(3,5));return h<=23&&m<=59?new int[]{h,m}:null;}catch(Exception ignored){return null;}}
    private static boolean inTimeWindow(String start,String end,long now,TimeZone zone){int[] a=parseTime(start),b=parseTime(end);if(a==null||b==null)return true;Calendar c=Calendar.getInstance(zone,Locale.US);c.setTimeInMillis(now);int m=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE),from=a[0]*60+a[1],to=b[0]*60+b[1];return from<=to?m>=from&&m<=to:m>=from||m<=to;}
    private static String occurrenceKey(int zoneId,Segment segment,long now,TimeZone zone){if(segment==null||zoneId<=0||segment.zoneId!=zoneId)return "";Calendar c=Calendar.getInstance(zone,Locale.US);c.setTimeInMillis(segment.startAt);return localDate(now,zone)+":"+zoneId+":"+String.format(Locale.US,"%02d:%02d",c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE));}
    private static String isoUtc(long at){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(at));}
    private static Deferral deferralFromJson(JSONObject row){String kind=row.optString("kind","");if(!DEFERRAL_NEXT_ZONE.equals(kind)&&!DEFERRAL_NOT_TODAY.equals(kind))return null;long start=row.optLong("startAt",0);if(start<=0)return null;Long end=row.has("endAt")&&!row.isNull("endAt")?row.optLong("endAt",0):null;return new Deferral(kind,row.optLong("requestedAt",0),row.optInt("sourceZoneId",0),row.optInt("targetZoneId",0),start,end);}
}
