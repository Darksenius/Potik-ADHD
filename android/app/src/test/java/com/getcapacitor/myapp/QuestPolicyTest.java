package com.flow.adhd;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class QuestPolicyTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long NOW = 1_792_000_000_000L; // 2026-10-14 17:46:40 UTC

    private static List<QuestPolicy.Segment> ordinaryTimeline() {
        return Arrays.asList(
                new QuestPolicy.Segment(1, NOW - 60 * 60_000L, NOW + 30 * 60_000L),
                new QuestPolicy.Segment(2, NOW + 30 * 60_000L, NOW + 90 * 60_000L),
                new QuestPolicy.Segment(1, NOW + 90 * 60_000L, NOW + 150 * 60_000L),
                new QuestPolicy.Segment(0, NOW + 150 * 60_000L, NOW + 200 * 60_000L),
                new QuestPolicy.Segment(1, NOW + 200 * 60_000L, NOW + 220 * 60_000L),
                new QuestPolicy.Segment(0, NOW + 220 * 60_000L, NOW + DAY)
        );
    }

    @Test public void pinnedTasksRankOnlyAfterCurrentZoneEligibilityAndSelectionCapsAtFive() {
        List<QuestPolicy.Segment> timeline = ordinaryTimeline();
        QuestPolicy.Task wrongPinned = QuestPolicy.Task.builder("wrong").zone(2).priority(0).build();
        QuestPolicy.Task first = QuestPolicy.Task.builder("first").zone(1).priority(2).build();
        QuestPolicy.Task second = QuestPolicy.Task.builder("second").zone(1).priority(0).build();
        QuestPolicy.Task third = QuestPolicy.Task.builder("third").zone(1).priority(-1).build();
        QuestPolicy.Task fourth = QuestPolicy.Task.builder("fourth").zone(1).build();
        QuestPolicy.Task fifth = QuestPolicy.Task.builder("fifth").zone(1).build();
        QuestPolicy.Task sixth = QuestPolicy.Task.builder("sixth").zone(1).build();

        QuestPolicy.Selection result = QuestPolicy.select(Arrays.asList(
                        wrongPinned, first, second, third, fourth, fifth, sixth),
                timeline, NOW + DAY, NOW, UTC, 99);

        assertTrue(result.valid);
        assertEquals(5, result.tasks.size());
        assertEquals("second", result.tasks.get(0).id);
        assertEquals("first", result.tasks.get(1).id);
        assertEquals("third", result.tasks.get(2).id);
        assertEquals("fourth", result.tasks.get(3).id);
        assertEquals("fifth", result.tasks.get(4).id);
        assertFalse(result.tasks.contains(wrongPinned));
    }

    @Test public void freeUnplannedCompletableTypesAreUniversalButJournalAndTerminalRowsAreExcluded() {
        List<QuestPolicy.Task> source = Arrays.asList(
                QuestPolicy.Task.builder("simple").type("simple").build(),
                QuestPolicy.Task.builder("check").type("check").build(),
                QuestPolicy.Task.builder("counter").counter(2, 5).build(),
                QuestPolicy.Task.builder("note").type("note").build(),
                QuestPolicy.Task.builder("ctx").type("ctx").build(),
                QuestPolicy.Task.builder("negative").type("negative").build(),
                QuestPolicy.Task.builder("done").done(true).build(),
                QuestPolicy.Task.builder("trash").trashed(true).build(),
                QuestPolicy.Task.builder("later").someday(true).build()
        );

        QuestPolicy.Selection result = QuestPolicy.select(source, ordinaryTimeline(), NOW + DAY, NOW, UTC, 5);

        assertEquals(Arrays.asList("simple", "check", "counter", "note", "ctx"), ids(result.tasks));
    }

    @Test public void scheduleAlarmAndTimeWindowGatesStillApplyWithoutDeferral() {
        QuestPolicy.Task futureSched = QuestPolicy.Task.builder("sched").sched("2026-10-15", "09:00").build();
        QuestPolicy.Task futureAlarm = QuestPolicy.Task.builder("alarm").alarm("23:59").build();
        QuestPolicy.Task closedWindow = QuestPolicy.Task.builder("window").timeWindow("20:00", "21:00").build();

        QuestPolicy.Selection result = QuestPolicy.select(Arrays.asList(futureSched, futureAlarm, closedWindow),
                ordinaryTimeline(), NOW + DAY, NOW, UTC, 5);

        assertTrue(result.tasks.isEmpty());
    }

    @Test public void repeatingSchedUsesTodaysClockButKeepsItsOriginalAnchorDate() {
        long midnight = QuestPolicy.nextLocalMidnight(NOW, UTC);
        long tomorrow = midnight + DAY;
        List<QuestPolicy.Segment> timeline = Collections.singletonList(
                new QuestPolicy.Segment(0, midnight, tomorrow));
        QuestPolicy.Task daily = QuestPolicy.Task.builder("daily-sched")
                .sched("2026-10-14", "09:00").repeat("daily").build();
        QuestPolicy.Task futureAnchor = QuestPolicy.Task.builder("future-anchor")
                .sched("2026-10-16", "09:00").repeat("daily").build();

        QuestPolicy.Selection beforeTodayTime = QuestPolicy.select(Collections.singletonList(daily), timeline,
                tomorrow, midnight + 5 * 60_000L, UTC, 5);
        QuestPolicy.Selection afterTodayTime = QuestPolicy.select(Collections.singletonList(daily), timeline,
                tomorrow, midnight + 9 * 60 * 60_000L + 5 * 60_000L, UTC, 5);
        QuestPolicy.Selection beforeOriginalAnchor = QuestPolicy.select(Collections.singletonList(futureAnchor), timeline,
                tomorrow, midnight + 9 * 60 * 60_000L + 5 * 60_000L, UTC, 5);

        assertTrue(beforeTodayTime.tasks.isEmpty());
        assertEquals(Collections.singletonList("daily-sched"), ids(afterTodayTime.tasks));
        assertTrue(beforeOriginalAnchor.tasks.isEmpty());
    }

    @Test public void expiredTimelineHorizonDoesNotGuessAZoneOrReturnTasks() {
        QuestPolicy.Selection result = QuestPolicy.select(
                Collections.singletonList(QuestPolicy.Task.builder("free").build()),
                ordinaryTimeline(), NOW, NOW, UTC, 5);

        assertFalse(result.valid);
        assertTrue(result.tasks.isEmpty());
        assertTrue(result.hint.contains("Відкрий Потік"));
    }

    @Test public void validDeferralOverridesPermanentScheduleRepeatAndTimeGatesForOneOccurrence() {
        List<QuestPolicy.Segment> timeline = ordinaryTimeline();
        QuestPolicy.Deferral deferral = new QuestPolicy.Deferral(
                QuestPolicy.DEFERRAL_NEXT_ZONE, NOW, 1, 2,
                NOW + 30 * 60_000L, NOW + 90 * 60_000L);
        QuestPolicy.Task deferred = QuestPolicy.Task.builder("deferred")
                .type("timewin").zone(1).planDate("2026-10-15").repeat("weekdays")
                .timeWindow("20:00", "21:00").deferral(deferral).build();

        QuestPolicy.Selection before = QuestPolicy.select(Collections.singletonList(deferred), timeline,
                NOW + DAY, NOW, UTC, 5);
        QuestPolicy.Selection inTarget = QuestPolicy.select(Collections.singletonList(deferred), timeline,
                NOW + DAY, NOW + 45 * 60_000L, UTC, 5);
        QuestPolicy.Selection returned = QuestPolicy.select(Collections.singletonList(deferred), timeline,
                NOW + DAY, NOW + 100 * 60_000L, UTC, 5);
        QuestPolicy.Selection laterSourceOccurrence = QuestPolicy.select(Collections.singletonList(deferred), timeline,
                NOW + DAY, NOW + 210 * 60_000L, UTC, 5);
        QuestPolicy.Selection expiredInWrongZoneSegment = QuestPolicy.select(Collections.singletonList(deferred), Arrays.asList(
                        new QuestPolicy.Segment(1, NOW - 60 * 60_000L, NOW + 10 * 60_000L),
                        new QuestPolicy.Segment(2, NOW + 10 * 60_000L, NOW + 120 * 60_000L)),
                NOW + DAY, NOW + 100 * 60_000L, UTC, 5);

        assertTrue(before.tasks.isEmpty());
        assertEquals(Collections.singletonList("deferred"), ids(inTarget.tasks));
        assertEquals(Collections.singletonList("deferred"), ids(returned.tasks));
        assertEquals(Collections.singletonList("deferred"), ids(laterSourceOccurrence.tasks));
        assertTrue(expiredInWrongZoneSegment.tasks.isEmpty());
    }

    @Test public void nextZoneDeferralRemainsVisibleAfterMidnightWhenItsStartWasPreviousDay() {
        long midnight = QuestPolicy.nextLocalMidnight(NOW, UTC);
        long targetStart = midnight - 60 * 60_000L;
        long targetEnd = midnight + 6 * 60 * 60_000L;
        QuestPolicy.Task deferred = QuestPolicy.Task.builder("overnight").zone(1)
                .deferral(new QuestPolicy.Deferral(QuestPolicy.DEFERRAL_NEXT_ZONE, NOW, 1, 2,
                        targetStart, targetEnd)).build();
        List<QuestPolicy.Segment> refreshedTimeline = Arrays.asList(
                new QuestPolicy.Segment(2, midnight, targetEnd),
                new QuestPolicy.Segment(1, targetEnd, midnight + DAY));

        QuestPolicy.Selection result = QuestPolicy.select(Collections.singletonList(deferred), refreshedTimeline,
                midnight + DAY, midnight + 60 * 60_000L, UTC, 5);

        assertEquals(Collections.singletonList("overnight"), ids(result.tasks));
    }

    @Test public void notTodayUsesTomorrowMidnightForBoundTasksAndTwentyThreeHoursForFreeTasks() {
        QuestPolicy.Task bound = QuestPolicy.Task.builder("bound").zone(1).build();
        QuestPolicy.Task free = QuestPolicy.Task.builder("free").build();
        QuestPolicy.Deferral boundDeferral = QuestPolicy.deferNotToday(bound, ordinaryTimeline(), NOW, UTC);
        QuestPolicy.Deferral freeDeferral = QuestPolicy.deferNotToday(free, ordinaryTimeline(), NOW, UTC);

        assertEquals(QuestPolicy.localDate(QuestPolicy.nextLocalMidnight(NOW, UTC), UTC),
                QuestPolicy.localDate(boundDeferral.startAt, UTC));
        assertEquals(1, boundDeferral.targetZoneId);
        assertEquals(NOW + 23 * 60 * 60_000L, freeDeferral.startAt);
        assertEquals(0, freeDeferral.targetZoneId);
    }

    @Test public void boundNotTodayStaysAvailableInLaterPrimaryOccurrencesOfItsZone() {
        List<QuestPolicy.Segment> timeline = ordinaryTimeline();
        QuestPolicy.Task bound = QuestPolicy.Task.builder("bound").zone(1).build();
        QuestPolicy.Deferral deferral = new QuestPolicy.Deferral(QuestPolicy.DEFERRAL_NOT_TODAY,
                NOW, 1, 1, NOW + 10 * 60_000L, null);
        bound.deferral = deferral;

        QuestPolicy.Selection tooEarly = QuestPolicy.select(Collections.singletonList(bound), timeline,
                NOW + DAY, NOW, UTC, 5);
        QuestPolicy.Selection sameSegmentStartedBeforeDeferral = QuestPolicy.select(Collections.singletonList(bound), timeline,
                NOW + DAY, NOW + 20 * 60_000L, UTC, 5);
        QuestPolicy.Selection firstOccurrence = QuestPolicy.select(Collections.singletonList(bound), timeline,
                NOW + DAY, NOW + 100 * 60_000L, UTC, 5);
        QuestPolicy.Selection laterOccurrence = QuestPolicy.select(Collections.singletonList(bound), timeline,
                NOW + DAY, NOW + 210 * 60_000L, UTC, 5);

        assertTrue(tooEarly.tasks.isEmpty());
        assertEquals(Collections.singletonList("bound"), ids(sameSegmentStartedBeforeDeferral.tasks));
        assertEquals(Collections.singletonList("bound"), ids(firstOccurrence.tasks));
        assertEquals(Collections.singletonList("bound"), ids(laterOccurrence.tasks));
    }

    @Test public void boundNotTodayAllowsAndRemindsAtMidnightInsideMergedZoneSegment() {
        long midnight = QuestPolicy.nextLocalMidnight(NOW, UTC);
        long end = NOW + 3 * DAY;
        List<QuestPolicy.Segment> mergedTimeline = Collections.singletonList(
                new QuestPolicy.Segment(1, NOW - DAY, end));
        QuestPolicy.Task alarm = QuestPolicy.Task.builder("bound-alarm").type("alarm").zone(1)
                .deferral(new QuestPolicy.Deferral(QuestPolicy.DEFERRAL_NOT_TODAY,
                        NOW, 1, 1, midnight, null)).build();

        QuestPolicy.Selection afterMidnight = QuestPolicy.select(Collections.singletonList(alarm), mergedTimeline,
                end, midnight + 5 * 60_000L, UTC, 5);

        assertEquals(Collections.singletonList("bound-alarm"), ids(afterMidnight.tasks));
        assertEquals(midnight, FlowAlarmReceiver.deferredReminderAt(alarm, mergedTimeline));
    }

    @Test public void boundNotTodayReminderWaitsForTheFirstMatchingSegmentAfterItsStart() {
        long midnight = QuestPolicy.nextLocalMidnight(NOW, UTC);
        long segmentStart = midnight + 30 * 60_000L;
        long end = segmentStart + 60 * 60_000L;
        QuestPolicy.Task alarm = QuestPolicy.Task.builder("later-bound-alarm").type("alarm").zone(1)
                .deferral(new QuestPolicy.Deferral(QuestPolicy.DEFERRAL_NOT_TODAY,
                        NOW, 1, 1, midnight, null)).build();
        List<QuestPolicy.Segment> timeline = Collections.singletonList(
                new QuestPolicy.Segment(1, segmentStart, end));

        assertEquals(segmentStart, FlowAlarmReceiver.deferredReminderAt(alarm, timeline));
    }

    @Test public void pendingDoneCounterAndDeferralEventsProjectOntoCopies() {
        QuestPolicy.Task counter = QuestPolicy.Task.builder("counter").counter(1, 10).build();
        QuestPolicy.Task deferred = QuestPolicy.Task.builder("deferred").zone(1).build();
        QuestPolicy.Deferral move = new QuestPolicy.Deferral(
                QuestPolicy.DEFERRAL_NEXT_ZONE, NOW, 1, 2,
                NOW + 30 * 60_000L, NOW + 90 * 60_000L);
        QuestPolicy.Task done = QuestPolicy.Task.builder("done").build();
        List<QuestPolicy.Task> source = Arrays.asList(counter, done, deferred);
        List<QuestPolicy.Event> events = Arrays.asList(
                QuestPolicy.Event.increment("counter", NOW),
                QuestPolicy.Event.done("done", NOW),
                QuestPolicy.Event.defer("deferred", NOW, move));

        List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(source, events, NOW,
                ordinaryTimeline(), UTC);

        assertEquals(2, projected.get(0).counter);
        assertTrue(projected.get(1).done);
        assertFalse(done.done); // projection did not mutate the persisted snapshot
        assertNotNull(projected.get(2).deferral);
        assertNull(deferred.deferral);
    }

    @Test public void stableQuickCaptureProjectsBeforeAppOpenAndAcceptsDoneAndDeferralCommands() {
        String id = "1792000000001024";
        String title = "Shade task: literal title";
        long capturedAt = NOW;
        List<QuestPolicy.Event> captured = Collections.singletonList(
                QuestPolicy.Event.captureTask(id, title, capturedAt));
        List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(Collections.<QuestPolicy.Task>emptyList(),
                captured, capturedAt, ordinaryTimeline(), UTC);

        assertEquals(1, projected.size());
        assertEquals(id, projected.get(0).id);
        assertEquals(title, projected.get(0).title);
        assertFalse(projected.get(0).hasZone);
        assertEquals(Collections.singletonList(id), ids(QuestPolicy.select(projected, ordinaryTimeline(),
                NOW + DAY, NOW, UTC, 5).tasks));

        QuestPolicy.Deferral move = new QuestPolicy.Deferral(QuestPolicy.DEFERRAL_NEXT_ZONE,
                NOW + 1_000L, 1, 2, NOW + 30 * 60_000L, NOW + 90 * 60_000L);
        List<QuestPolicy.Event> afterMoveEvents = Arrays.asList(captured.get(0),
                QuestPolicy.Event.defer(id, NOW + 1_000L, move));
        List<QuestPolicy.Task> moved = QuestPolicy.projectPendingEvents(Collections.<QuestPolicy.Task>emptyList(),
                afterMoveEvents, NOW + 1_000L, ordinaryTimeline(), UTC);
        assertNotNull(moved.get(0).deferral);
        assertEquals(QuestPolicy.DEFERRAL_NEXT_ZONE, moved.get(0).deferral.kind);

        List<QuestPolicy.Event> afterDoneEvents = Arrays.asList(captured.get(0),
                QuestPolicy.Event.defer(id, NOW + 1_000L, move), QuestPolicy.Event.done(id, NOW + 2_000L));
        List<QuestPolicy.Task> done = QuestPolicy.projectPendingEvents(Collections.<QuestPolicy.Task>emptyList(),
                afterDoneEvents, NOW + 2_000L, ordinaryTimeline(), UTC);
        assertTrue(done.get(0).done);
        assertEquals(id, done.get(0).id);
    }

    @Test public void quickCaptureIdAllocationAvoidsSavedAndPendingIdsWithinJsSafeRange() {
        assertEquals(7_001L, FlowBridge.allocateCapturedTaskId(5L, 5_000L,
                new long[] {6_000L}, new long[] {7_000L}));
        assertEquals(1_024_000L, FlowBridge.allocateCapturedTaskId(1_000L, 300L,
                new long[0], new long[0]));
        assertEquals(-1L, FlowBridge.allocateCapturedTaskId(1_000L, 1L,
                new long[] {9_007_199_254_740_991L}, new long[0]));
    }

    @Test public void legacySkipEventProjectsAsNextZoneInsteadOfFifteenMinuteSnooze() {
        QuestPolicy.Task task = QuestPolicy.Task.builder("legacy").zone(1).build();
        List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(Collections.singletonList(task),
                Collections.singletonList(QuestPolicy.Event.skip("legacy", NOW)), NOW, ordinaryTimeline(), UTC);

        assertNotNull(projected.get(0).deferral);
        assertEquals(QuestPolicy.DEFERRAL_NEXT_ZONE, projected.get(0).deferral.kind);
        assertEquals(NOW + 30 * 60_000L, projected.get(0).deferral.startAt);
        assertEquals(0L, projected.get(0).snoozeUntil);
    }

    @Test public void dailyCounterProjectionResetsStaleProgressUnlessTheOccurrenceIsDeferred() {
        QuestPolicy.Task ordinary = QuestPolicy.Task.builder("ordinary").counter(8, 10).repeat("daily").build();
        ordinary.counterDay = "2026-10-13";
        QuestPolicy.Task deferred = QuestPolicy.Task.builder("deferred-counter").counter(8, 10).repeat("daily").build();
        deferred.counterDay = "2026-10-13";
        QuestPolicy.Deferral hold = new QuestPolicy.Deferral(QuestPolicy.DEFERRAL_NOT_TODAY,
                NOW, 1, 0, NOW + 23 * 60 * 60_000L, null);

        List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(
                Arrays.asList(ordinary, deferred), Arrays.asList(
                        QuestPolicy.Event.defer("deferred-counter", NOW, hold),
                        QuestPolicy.Event.increment("ordinary", NOW),
                        QuestPolicy.Event.increment("deferred-counter", NOW)),
                NOW, ordinaryTimeline(), UTC);

        assertEquals(1, projected.get(0).counter);
        assertEquals("2026-10-14", projected.get(0).counterDay);
        assertEquals(9, projected.get(1).counter);
        assertEquals("2026-10-14", projected.get(1).counterDay);
        assertNotNull(projected.get(1).deferral);
    }

    private static List<String> ids(List<QuestPolicy.Task> tasks) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (QuestPolicy.Task task : tasks) result.add(task.id);
        return result;
    }
}
