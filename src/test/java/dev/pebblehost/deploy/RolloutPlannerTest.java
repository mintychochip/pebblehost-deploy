package dev.pebblehost.deploy;

import org.gradle.api.model.ObjectFactory;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RolloutPlannerTest {
    private Target target(String id, String group) {
        ObjectFactory objects = ProjectBuilder.builder().build().getObjects();
        Target t = objects.newInstance(Target.class);
        t.getServerId().set(id);
        t.getGroup().set(group);
        return t;
    }

    @Test
    void flatStrategyYieldsSingleDefaultGroup() {
        List<Target> ts = List.of(target("a", "canary"), target("b", "prod"));
        RolloutPlanner.RolloutPlan p = RolloutPlanner.plan(ts, "flat", true, false);
        assertEquals(1, p.groups().size());
        assertEquals("default", p.groups().get(0).name());
        assertEquals(2, p.groups().get(0).targets().size());
        assertFalse(p.canaryGate());
    }

    @Test
    void groupsStrategyPreservesOrderAndGroups() {
        List<Target> ts = List.of(target("a", "prod"), target("b", "canary"), target("c", "prod"));
        RolloutPlanner.RolloutPlan p = RolloutPlanner.plan(ts, "groups", true, false);
        assertEquals(List.of("prod", "canary"), p.groups().stream().map(RolloutPlanner.RolloutGroup::name).toList());
        assertEquals(2, p.groups().get(0).targets().size());
        assertEquals(1, p.groups().get(1).targets().size());
    }

    @Test
    void canaryGateIsLastDetection() {
        List<Target> ts = List.of(target("a", "canary"), target("b", "prod"));
        RolloutPlanner.RolloutPlan p = RolloutPlanner.plan(ts, "groups", true, false);
        assertTrue(p.canaryGate());
        assertFalse(p.isLast(p.groups().get(0)));
        assertTrue(p.isLast(p.groups().get(1)));
    }

    @Test
    void rejectsEmptyTargets() {
        assertThrows(IllegalArgumentException.class,
            () -> RolloutPlanner.plan(List.of(), "groups", true, false));
        assertThrows(IllegalArgumentException.class,
            () -> RolloutPlanner.plan(null, "groups", true, false));
    }

    @Test
    void rejectsUnknownStrategy() {
        List<Target> ts = List.of(target("a", "default"));
        assertThrows(IllegalArgumentException.class,
            () -> RolloutPlanner.plan(ts, "bogus", true, false));
        assertThrows(IllegalArgumentException.class,
            () -> RolloutPlanner.plan(ts, null, true, false));
    }

    @Test
    void rejectsBlankServerIdOrGroup() {
        ObjectFactory objects = ProjectBuilder.builder().build().getObjects();
        Target blankServer = objects.newInstance(Target.class);
        blankServer.getServerId().set("  ");
        blankServer.getGroup().set("default");
        assertThrows(IllegalArgumentException.class,
            () -> RolloutPlanner.plan(List.of(blankServer), "groups", true, false));

        Target blankGroup = objects.newInstance(Target.class);
        blankGroup.getServerId().set("s1");
        blankGroup.getGroup().set("");
        assertThrows(IllegalArgumentException.class,
            () -> RolloutPlanner.plan(List.of(blankGroup), "groups", true, false));
    }
}
