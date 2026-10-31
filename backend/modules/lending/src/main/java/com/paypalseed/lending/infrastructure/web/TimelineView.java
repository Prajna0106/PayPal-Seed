package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.Slot;
import com.paypalseed.lending.api.dto.LendingDtos.VersionView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bryntum Scheduler Pro payload (05-sponsor-integrations §3): one resource per loan, one event per
 * instalment coloured by derived status, the previous version's moved instalments as read-only ghost
 * events with dependency arrows to where they went, and the grace windows as time ranges.
 */
public record TimelineView(List<Resource> resources, List<Event> events, List<Event> ghostEvents,
                           List<Dependency> dependencies, List<TimeRange> timeRanges) {

    public record Resource(String id, String name) {
    }

    public record Event(String id, String resourceId, LocalDate startDate, LocalDate endDate, String name, String status,
                        String eventColor, boolean readOnly) {
    }

    public record Dependency(String from, String to, String cls) {
    }

    public record TimeRange(String id, LocalDate startDate, LocalDate endDate, String name) {
    }

    private static final Map<String, String> COLOURS = Map.of(
            "PAID", "green", "PAID_LATE", "orange", "PREPAID", "teal", "DUE", "blue", "SCHEDULED", "gray",
            "PARTIALLY_PAID", "cyan", "IN_GRACE", "amber", "LATE", "red");

    static TimelineView of(UUID loanId, ScheduleView current, List<VersionView> versions, int graceDays) {
        String resourceId = "loan-" + loanId;
        String version = "v" + current.version();
        List<Event> events = new ArrayList<>();
        List<TimeRange> ranges = new ArrayList<>();
        for (InstalmentLine line : current.instalments()) {
            events.add(new Event(version + "-s" + line.seq(), resourceId, line.dueDate(), line.dueDate().plusDays(1),
                    "$" + line.amountCents() / 100, line.status(), COLOURS.getOrDefault(line.status(), "gray"), false));
            if (line.remainingCents() > 0) {
                ranges.add(new TimeRange("grace-" + version + "-s" + line.seq(), line.dueDate().plusDays(1),
                        line.dueDate().plusDays(graceDays + 1L), "Grace"));
            }
        }
        List<Event> ghosts = new ArrayList<>();
        List<Dependency> arrows = new ArrayList<>();
        versions.stream().filter(v -> v.version() == current.version()).findFirst()
                .flatMap(VersionView::diffFromPrevious)
                .ifPresent(diff -> {
                    String previous = "v" + (current.version() - 1);
                    for (int i = 0; i < diff.removed().size(); i++) {
                        Slot from = diff.removed().get(i);
                        String ghostId = previous + "-s" + from.seq();
                        ghosts.add(new Event(ghostId, resourceId, from.dueDate(), from.dueDate().plusDays(1),
                                "$" + from.amountCents() / 100, "PREVIOUS_PLAN", "gray", true));
                        if (i < diff.added().size()) {
                            arrows.add(new Dependency(ghostId, version + "-s" + diff.added().get(i).seq(), "shift"));
                        }
                    }
                });
        return new TimelineView(List.of(new Resource(resourceId, "Loan · $" + current.principalCents() / 100)),
                events, ghosts, arrows, ranges);
    }
}
