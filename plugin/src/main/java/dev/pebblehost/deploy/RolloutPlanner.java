package dev.pebblehost.deploy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RolloutPlanner {
    public record RolloutGroup(String name, List<Target> targets) {}

    public record RolloutPlan(List<RolloutGroup> groups, boolean canaryGate, boolean continueAfterCanary) {
        public boolean isLast(RolloutGroup g) {
            return groups.indexOf(g) == groups.size() - 1;
        }
    }

    public static RolloutPlan plan(List<Target> targets, String strategy, boolean canaryGate, boolean continueAfterCanary) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("at least one target server is required");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must be 'flat' or 'groups', got: null");
        }
        for (Target t : targets) {
            if (t == null || t.getServerId().getOrNull() == null || t.getServerId().get().isBlank()) {
                throw new IllegalArgumentException("each target must have a non-blank serverId");
            }
            if (t.getGroup().getOrNull() == null || t.getGroup().get().isBlank()) {
                throw new IllegalArgumentException("each target must have a non-blank group");
            }
        }
        if (strategy.equals("flat")) {
            return new RolloutPlan(List.of(new RolloutGroup("default", targets)), false, continueAfterCanary);
        }
        if (!strategy.equals("groups")) {
            throw new IllegalArgumentException("strategy must be 'flat' or 'groups', got: " + strategy);
        }
        LinkedHashMap<String, List<Target>> byGroup = new LinkedHashMap<>();
        for (Target t : targets) {
            byGroup.computeIfAbsent(t.getGroup().get(), k -> new ArrayList<>()).add(t);
        }
        List<RolloutGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Target>> e : byGroup.entrySet()) {
            groups.add(new RolloutGroup(e.getKey(), e.getValue()));
        }
        return new RolloutPlan(groups, canaryGate, continueAfterCanary);
    }
}
