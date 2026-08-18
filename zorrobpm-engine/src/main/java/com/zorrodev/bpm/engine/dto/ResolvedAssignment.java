package com.zorrodev.bpm.engine.dto;

import java.util.List;

public record ResolvedAssignment(String assignee, List<String> candidateUsers, List<String> candidateGroups) {

    public static final ResolvedAssignment EMPTY = new ResolvedAssignment(null, List.of(), List.of());
}
