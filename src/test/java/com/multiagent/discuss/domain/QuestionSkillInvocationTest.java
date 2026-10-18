package com.multiagent.discuss.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionSkillInvocationTest {

    @Test
    void keepsOnlyTheLatestThreeInvocationRecordsPerAgent() {
        Question question = new Question("q", "s", "task");
        for (int i = 1; i <= 2; i++) {
            Turn turn = new Turn("agent-1", "Agent", "DOMAIN", Turn.Phase.DISCUSSION, i, "reply");
            turn.setSkillInvocations(List.of(
                    new SkillInvocation("skill-a", "Skill A", "reason-" + i + "a", Instant.now()),
                    new SkillInvocation("skill-b", "Skill B", "reason-" + i + "b", Instant.now())));
            question.addTurn(turn);
        }

        assertEquals(List.of("reason-1b"), question.getTranscript().get(0).getSkillInvocations().stream()
                .map(SkillInvocation::reason).toList());
        assertEquals(List.of("reason-1b", "reason-2a", "reason-2b"), question.getTranscript().stream()
                .flatMap(turn -> turn.getSkillInvocations().stream())
                .map(SkillInvocation::reason)
                .toList());
    }
}
