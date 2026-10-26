package de.trakt.quest.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Objective tree of a quest (F2a composability). A node is either a {@link Leaf} (one
 * measurable {@link QuestGoal}) or a {@link Group} combining its children:
 *
 * <ul>
 *   <li>{@code all_of} — every child must complete (the implicit meaning of the v1
 *       {@code "goals": [...]} array, which parses into an all_of root);</li>
 *   <li>{@code any_of} — at least one child must complete;</li>
 *   <li>{@code sequence} — children must complete in order: a child only credits once
 *       every earlier sibling subtree is complete.</li>
 * </ul>
 *
 * <p>Progress counters stay per leaf (the v1 per-goal keys), so saves and tuning are
 * unaffected by how leaves are grouped. The v1 staged behaviour of the retired
 * smelt/craft classes is preserved exactly: a leaf whose kind carries the staged flag
 * only credits once every earlier sibling <b>within its immediate parent</b> is
 * complete — identical to {@code previousGoalsComplete} on the flat v1 list.
 *
 * <p>Authoring: the tree is written under the {@code objective} key and is mutually
 * exclusive with the v1 {@code goals}/{@code goal} forms. Nesting is capped at three
 * levels and six leaves per quest; explicit groups need 2–6 children.
 */
public sealed interface QuestObjective {

    int MAX_LEAVES = 6;
    int MAX_DEPTH = 3;
    int MAX_CHILDREN = 6;

    /** Composite node. */
    record Group(Operator operator, List<QuestObjective> children) implements QuestObjective {
        public Group {
            children = List.copyOf(children);
        }
    }

    /** Measurable leaf. */
    record Leaf(QuestGoal goal) implements QuestObjective {}

    enum Operator {
        ALL_OF("all_of"),
        ANY_OF("any_of"),
        SEQUENCE("sequence");

        private final String id;

        Operator(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Operator fromId(String raw) {
            for (Operator operator : values()) {
                if (operator.id.equals(raw)) {
                    return operator;
                }
            }
            return null;
        }

        static String idList() {
            List<String> ids = new ArrayList<>();
            for (Operator operator : values()) {
                ids.add(operator.id());
            }
            return String.join("/", ids);
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Parses one node: an object with {@code kind} (leaf) or exactly one operator key (group). */
    static QuestObjective parse(JsonObject node, int depth, List<String> errors) {
        if (node == null) {
            errors.add("objective node must be an object");
            return null;
        }
        if (depth > MAX_DEPTH) {
            errors.add("objective nesting is capped at " + MAX_DEPTH + " levels");
            return null;
        }
        String kindId = QuestGoal.stringOf(node, "kind");
        Operator found = null;
        String operatorKey = null;
        for (Operator operator : Operator.values()) {
            if (node.has(operator.id())) {
                if (found != null) {
                    errors.add("objective node must carry exactly one of " + Operator.idList());
                    return null;
                }
                found = operator;
                operatorKey = operator.id();
            }
        }
        if (kindId != null && found != null) {
            errors.add("objective node must be either a leaf (kind) or a group ("
                    + Operator.idList() + "), not both");
            return null;
        }
        if (kindId != null) {
            List<String> leafErrors = new ArrayList<>();
            QuestGoal goal = QuestGoal.parse(node, leafErrors);
            if (goal == null) {
                errors.addAll(leafErrors);
                return null;
            }
            return new Leaf(goal);
        }
        if (found == null) {
            errors.add("objective node must have kind (leaf) or one of "
                    + Operator.idList() + " (group)");
            return null;
        }
        JsonElement childrenElement = node.get(operatorKey);
        if (childrenElement == null || !childrenElement.isJsonArray()) {
            errors.add(operatorKey + " must be an array of objective nodes");
            return null;
        }
        List<QuestObjective> children = new ArrayList<>();
        for (JsonElement child : childrenElement.getAsJsonArray()) {
            if (!child.isJsonObject()) {
                errors.add(operatorKey + " entries must be objects");
                continue;
            }
            QuestObjective parsed = parse(child.getAsJsonObject(), depth + 1, errors);
            if (parsed != null) {
                children.add(parsed);
            }
        }
        if (children.size() < 2) {
            errors.add(operatorKey + " needs at least 2 children");
            return null;
        }
        if (children.size() > MAX_CHILDREN) {
            errors.add(operatorKey + " supports at most " + MAX_CHILDREN + " children");
            return null;
        }
        return new Group(found, children);
    }

    /** Root from the v1 flat goals list: all_of over the leaves (implicit v1 semantics). */
    static QuestObjective ofGoals(List<QuestGoal> goals) {
        List<QuestObjective> leaves = new ArrayList<>();
        for (QuestGoal goal : goals) {
            leaves.add(new Leaf(goal));
        }
        return new Group(Operator.ALL_OF, leaves);
    }

    // ------------------------------------------------------------------ flattening & completion

    /** Leaves in pre-order — the index order used by progress counters and views. */
    static List<QuestGoal> leaves(QuestObjective root) {
        List<QuestGoal> leaves = new ArrayList<>();
        collectLeaves(root, leaves);
        return leaves;
    }

    private static void collectLeaves(QuestObjective node, List<QuestGoal> leaves) {
        switch (node) {
            case Leaf leaf -> leaves.add(leaf.goal());
            case Group group -> {
                for (QuestObjective child : group.children()) {
                    collectLeaves(child, leaves);
                }
            }
        }
    }

    /**
     * Completion of the subtree. {@code leafDone} receives leaf indexes in pre-order;
     * every child is evaluated so sibling ranges stay correct (no short-circuit).
     */
    static boolean isComplete(QuestObjective root, IntPredicate leafDone) {
        return complete(root, leafDone, new int[1]);
    }

    private static boolean complete(QuestObjective node, IntPredicate leafDone, int[] cursor) {
        return switch (node) {
            case Leaf leaf -> {
                int index = cursor[0]++;
                yield leafDone.test(index);
            }
            case Group group -> {
                // ANY_OF starts false (neutral for OR); ALL_OF/SEQUENCE start true
                // (neutral for AND) — every child is still evaluated so the cursor
                // walks the whole subtree in pre-order.
                boolean result = group.operator() != Operator.ANY_OF;
                for (QuestObjective child : group.children()) {
                    boolean childDone = complete(child, leafDone, cursor);
                    result = group.operator() == Operator.ANY_OF
                            ? result || childDone
                            : result && childDone;
                }
                yield result;
            }
        };
    }

    // ------------------------------------------------------------------ credit gates

    /**
     * Per-leaf gate sets: for leaf {@code i} the set of leaf indexes that must be
     * complete before {@code i} may credit. Contributions:
     * <ol>
     *   <li>every earlier sibling of the leaf <b>within its immediate parent</b>, when
     *       the leaf's kind carries the staged flag (v1 smelt/craft behaviour);</li>
     *   <li>every leaf of earlier sibling subtrees of each {@code sequence} ancestor.</li>
     * </ol>
     */
    static List<Set<Integer>> computeGates(QuestObjective root) {
        // indexTree is the single populator of the leaf list (pre-order); the gates
        // list is sized from it and applyGates writes by those indexes.
        List<QuestGoal> leaves = new ArrayList<>();
        IdentityHashMap<QuestObjective, int[]> childRanges = new IdentityHashMap<>();
        indexTree(root, leaves, childRanges);
        List<Set<Integer>> gates = new ArrayList<>(leaves.size());
        for (int i = 0; i < leaves.size(); i++) {
            gates.add(Set.of());
        }
        applyGates(root, childRanges, List.of(), gates);
        return gates;
    }

    /**
     * Walks the tree in pre-order; recursion assigns leaf indexes through
     * {@link #indexTree}. {@code sequenceGates} carries, per sequence ancestor, the
     * leaf range of that ancestor's earlier siblings relative to the child being
     * descended into.
     */
    private static void applyGates(QuestObjective node, IdentityHashMap<QuestObjective, int[]> childRanges,
                                   List<int[]> sequenceGates, List<Set<Integer>> gates) {
        switch (node) {
            // A bare leaf can only be the root (single "goal" v1 form): no siblings, no ancestors.
            case Leaf ignored -> { }
            case Group group -> {
                int[] perChild = childRanges.get(group);
                for (int p = 0; p < group.children().size(); p++) {
                    QuestObjective child = group.children().get(p);
                    int[] earlierSiblings = {perChild[0], perChild[p * 2]};
                    List<int[]> childSequenceGates = sequenceGates;
                    if (group.operator() == Operator.SEQUENCE) {
                        List<int[]> extended = new ArrayList<>(sequenceGates);
                        extended.add(earlierSiblings);
                        childSequenceGates = extended;
                    }
                    if (child instanceof Leaf leaf) {
                        Set<Integer> gate = new java.util.HashSet<>();
                        if (leaf.goal().kind().stagedGate()) {
                            for (int i = perChild[0]; i < perChild[p * 2]; i++) {
                                gate.add(i);
                            }
                        }
                        for (int[] earlier : childSequenceGates) {
                            for (int i = earlier[0]; i < earlier[1]; i++) {
                                gate.add(i);
                            }
                        }
                        gates.set(perChild[p * 2], gate.isEmpty() ? Set.of() : Set.copyOf(gate));
                    } else {
                        applyGates(child, childRanges, childSequenceGates, gates);
                    }
                }
            }
        }
    }

    /**
     * Assigns pre-order leaf indexes and returns the leaf range {@code [start, end)} of
     * the subtree; per-group child ranges (two ints per child) are collected into
     * {@code childRanges}.
     */
    private static int[] indexTree(QuestObjective node, List<QuestGoal> leaves,
                                   IdentityHashMap<QuestObjective, int[]> childRanges) {
        return switch (node) {
            case Leaf leaf -> {
                int index = leaves.size();
                leaves.add(leaf.goal());
                yield new int[]{index, index + 1};
            }
            case Group group -> {
                int start = leaves.size();
                int[] perChild = new int[group.children().size() * 2];
                for (int i = 0; i < group.children().size(); i++) {
                    int[] childRange = indexTree(group.children().get(i), leaves, childRanges);
                    perChild[i * 2] = childRange[0];
                    perChild[i * 2 + 1] = childRange[1];
                }
                childRanges.put(node, perChild);
                yield new int[]{start, leaves.size()};
            }
        };
    }

    // ------------------------------------------------------------------ serialization

    /** Writes one node: a leaf object or a single-operator group object. */
    static JsonObject toJson(QuestObjective node) {
        return switch (node) {
            case Leaf leaf -> leaf.goal().toJson();
            case Group group -> {
                JsonObject object = new JsonObject();
                JsonArray array = new JsonArray();
                for (QuestObjective child : group.children()) {
                    array.add(toJson(child));
                }
                object.add(group.operator().id(), array);
                yield object;
            }
        };
    }
}
