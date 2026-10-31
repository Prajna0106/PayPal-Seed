package com.paypalseed.lending.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LoanRowTest {

    /** Close of business splits the 64 virtual shards over any worker count that divides 64. */
    @Test
    void shardGroupsPartitionAllVirtualShards() {
        for (int shardCount : new int[] {1, 2, 4, 8, 16, 32, 64}) {
            List<Integer> all = new ArrayList<>();
            for (int group = 0; group < shardCount; group++) {
                all.addAll(LoanRow.shardsOfGroup(group, shardCount));
            }
            assertThat(all).as("shard count %d", shardCount).containsExactlyInAnyOrderElementsOf(
                    IntStream.range(0, 64).boxed().toList());
        }
    }

    @Test
    void shardGroupMatchesTheModuloRuleTheIndexReplaces() {
        assertThat(LoanRow.shardsOfGroup(3, 8)).containsExactly(3, 11, 19, 27, 35, 43, 51, 59)
                .allMatch(shard -> shard % 8 == 3);
    }

    @Test
    void everyLoanLandsInExactlyOneGroup() {
        for (int i = 0; i < 1_000; i++) {
            int shard = LoanRow.shardOf(UUID.randomUUID());
            long groups = IntStream.range(0, 8).filter(g -> LoanRow.shardsOfGroup(g, 8).contains(shard)).count();
            assertThat(groups).isEqualTo(1);
        }
    }
}
