package com.multiagent.discuss.knowledge;

import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.SkillInvocation;
import com.multiagent.discuss.domain.Turn;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillInvocationRetentionTest {
    @Test
    void retainsOnlyThreeRecordsForAnAgentAcrossQuestionsInOneSession() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        Session session = new Session("s", "", false, false, 10, 2);
        repo.saveSession(session);

        for (int q = 1; q <= 2; q++) {
            Question question = new Question("q" + q, "s", "task");
            session.getQuestionIds().add(question.getId());
            repo.saveQuestion(question);
            Turn turn = new Turn("agent", "Agent", "DOMAIN", Turn.Phase.DISCUSSION, 1, "reply");
            turn.setSkillInvocations(List.of(
                    new SkillInvocation("a", "A", "q" + q + "a", Instant.now()),
                    new SkillInvocation("b", "B", "q" + q + "b", Instant.now())));
            question.addTurn(turn);
            repo.trimSkillInvocations("s", "agent");
        }

        assertEquals(List.of("q1b"), repo.requireQuestion("s", "q1").getTranscript().get(0)
                .getSkillInvocations().stream().map(SkillInvocation::reason).toList());
        assertEquals(2, repo.requireQuestion("s", "q2").getTranscript().get(0)
                .getSkillInvocations().size());
    }
}
