package com.multiagent.discuss.domain;

import java.time.Instant;

public record SkillInvocation(String skillUuid, String name, String reason, Instant at) {}
