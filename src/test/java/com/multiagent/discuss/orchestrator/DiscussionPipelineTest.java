package com.multiagent.discuss.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiagent.discuss.agent.AgentFactory;
import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.QuestionStatus;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.Turn;
import com.multiagent.discuss.knowledge.AgentKnowledgeStore;
import com.multiagent.discuss.knowledge.InMemorySessionRepository;
import com.multiagent.discuss.db.repo.SkillFileRepository;
import com.multiagent.discuss.db.repo.SkillRepository;
import com.multiagent.discuss.service.SkillActivationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 知识注入走哪条分支 —— 2.5.0 把判据从 `isMentor()` 放宽到「导师 **或** 带 uuid 的常驻席位」。
 *
 * <p>这条判定是本次改动里**唯一 prompt 可见**的行为变更，也是「不能造假或者虚构」那条要求的
 * 真正落点：
 *
 * <ul>
 *   <li>走检索版 → 页头是「《文件名》第 N 页」，模型手上有**真实存在**的来源可引；</li>
 *   <li>走页序版 → 页头只有「第 N 页」，**不带文件名**。而 prompt 要求
 *       「引用文献时必须给真实的页/表/图/DOI」—— 模型唯一能满足这条要求的办法就是**编一个**。
 *       多文档时还会只拿到第一个文件的头几页，后面的文件在讨论中完全不可见。</li>
 * </ul>
 *
 * <p>反向的约束同样重要：**不带 uuid 的普通席位必须逐字不变**（页序前 N 页、无来源），
 * 这是本次改动的回归底线。所以这里四种组合全钉一遍。
 */
class DiscussionPipelineTest {

    @Test
    void promptSectionsKeepTheirAssignedContentAndPriorityOrder() {
        String independent = DiscussionPipeline.independentPrompt(
                "QUESTION_MARKER", "EXCERPT_MARKER", "KNOWLEDGE_MARKER", "HISTORY_MARKER",
                "ROLE_MARKER", "SKILL_MARKER");
        assertTrue(independent.contains("用户问题：\nQUESTION_MARKER\n用户原文相关摘录（仅供对照，非你的文献）：\nEXCERPT_MARKER"));
        assertTrue(independent.contains("你的私有文献/Vision 知识：\nKNOWLEDGE_MARKER"));
        assertTrue(independent.contains("近期会话历史（可选）：\nHISTORY_MARKER"));
        assertTrue(independent.indexOf("ROLE_MARKER") < independent.indexOf("SKILL_MARKER"));

        String discussion = DiscussionPipeline.discussionPrompt(2, "QUESTION_MARKER", "FOCUS_MARKER",
                "LOG_MARKER", "KNOWLEDGE_MARKER", "ROLE_MARKER", "SKILL_MARKER");
        assertTrue(discussion.contains("目前讨论记录：\nLOG_MARKER"));
        assertTrue(discussion.contains("你的私有知识（仅你可见）：\nKNOWLEDGE_MARKER"));
        assertTrue(discussion.indexOf("ROLE_MARKER") < discussion.indexOf("SKILL_MARKER"));
        assertTrue(discussion.startsWith("【专业讨论 第 2 轮】"));
    }

    @Test
    void plainSeatKeepsTheOldBranch() {
        // 普通注册的临时席位：没有 uuid、不是导师 —— 行为必须与改动前完全相同
        assertFalse(DiscussionPipeline.useRetrievalFor(seat(false, null)),
                "★ 回归底线：普通席位走 buildKnowledgeContext，一个字节都不该变");
    }

    @Test
    void mentorAlwaysRetrieves() {
        assertTrue(DiscussionPipeline.useRetrievalFor(seat(true, null)),
                "导师库里混着多篇论文，取页序前 N 页只会拿到第一篇的开头");
    }

    @Test
    void persistentSeatRetrieves() {
        assertTrue(DiscussionPipeline.useRetrievalFor(seat(false, "uuid-1")),
                "常驻席位可能已经从账号知识库灌回了多份资料，且必须能引用到具体来源");
    }

    @Test
    void persistentMentorIsStillRetrieval() {
        assertTrue(DiscussionPipeline.useRetrievalFor(seat(true, "uuid-1")));
    }

    @Test
    void interjectionIsRecordedAndReturnsToTheSameRoundBoundary() {
        InMemorySessionRepository repo = new InMemorySessionRepository();
        Session session = new Session("sess-1", null, true, false, 20, 2);
        session.setAllowInterjections(true);
        repo.saveSession(session);
        Question question = new Question("q-1", "sess-1", "原问题");
        question.setCompletedDiscussionRounds(1);
        question.setStatus(QuestionStatus.WAITING_FOR_INTERJECTION);
        repo.saveQuestion(question);
        AppProperties properties = new AppProperties();
        DiscussionPipeline pipeline = new DiscussionPipeline(repo,
                new AgentKnowledgeStore(), new AgentFactory(null, properties), properties,
                new ObjectMapper(), new SkillActivationService(
                        mock(SkillRepository.class), mock(SkillFileRepository.class)), Runnable::run);

        Question updated = pipeline.addInterjection("sess-1", "q-1", "请补充执行成本", null);

        assertEquals(QuestionStatus.WAITING_FOR_INTERJECTION, updated.getStatus());
        assertEquals(1, updated.getCompletedDiscussionRounds(), "插话轮不占正常讨论轮次");
        assertEquals(1, updated.getTranscript().size());
        assertEquals(Turn.Phase.USER_INTERJECTION, updated.getTranscript().get(0).getPhase());
        assertEquals("用户补充", updated.getTranscript().get(0).getSpeakerName());
    }

    private static DomainAgentConfig seat(boolean mentor, String agentUuid) {
        DomainAgentConfig a = new DomainAgentConfig(
                "agent-1", "sess-1", "材料专家", "sk-x", "https://gw", "m", "persona");
        a.setMentor(mentor);
        a.setAgentUuid(agentUuid);
        return a;
    }
}
