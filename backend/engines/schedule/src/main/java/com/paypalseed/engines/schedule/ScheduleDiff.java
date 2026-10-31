package com.paypalseed.engines.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What changed between two schedule versions, as (due date, amount) slots. Removed slots become the
 * Bryntum "ghost" events; each is paired with the added slot at the same position, which is how the
 * timeline draws its shift arrows.
 */
public record ScheduleDiff(List<Slot> removed, List<Slot> added, List<Move> moves) {

    public record Slot(int seq, LocalDate dueDate, long amountCents) {
    }

    public record Move(Slot from, Slot to) {
    }

    public ScheduleDiff {
        removed = List.copyOf(removed);
        added = List.copyOf(added);
        moves = List.copyOf(moves);
    }

    public static ScheduleDiff between(Schedule before, Schedule after) {
        List<Slot> oldSlots = slots(before);
        List<Slot> newSlots = slots(after);
        List<Slot> removed = new ArrayList<>();
        List<Slot> unmatchedNew = new ArrayList<>(newSlots);
        for (Slot old : oldSlots) {
            Slot match = unmatchedNew.stream()
                    .filter(n -> n.dueDate().equals(old.dueDate()) && n.amountCents() == old.amountCents())
                    .findFirst().orElse(null);
            if (match == null) {
                removed.add(old);
            } else {
                unmatchedNew.remove(match);
            }
        }
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < Math.min(removed.size(), unmatchedNew.size()); i++) {
            moves.add(new Move(removed.get(i), unmatchedNew.get(i)));
        }
        return new ScheduleDiff(removed, unmatchedNew, moves);
    }

    private static List<Slot> slots(Schedule s) {
        return s.instalments().stream().map(i -> new Slot(i.seq(), i.dueDate(), i.amountCents())).toList();
    }
}
