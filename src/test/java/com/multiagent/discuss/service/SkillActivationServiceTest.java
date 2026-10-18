package com.multiagent.discuss.service;

import com.multiagent.discuss.db.entity.SkillEntity;
import com.multiagent.discuss.db.repo.SkillFileRepository;
import com.multiagent.discuss.db.repo.SkillRepository;
import com.multiagent.discuss.domain.DomainAgentConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillActivationServiceTest {

    @Test
    void selectsAtMostTwoRelevantSkillsAndSkipsUnrelatedOnes() {
        SkillRepository skills = mock(SkillRepository.class);
        SkillFileRepository files = mock(SkillFileRepository.class);
        SkillEntity frontend = skill("front", "前端审查助手", "审查前端页面的可访问性与交互质量");
        SkillEntity requirements = skill("requirements", "需求分析", "发现产品需求中的遗漏与冲突");
        SkillEntity database = skill("database", "数据库调优", "优化 SQL 索引与查询性能");
        when(skills.findByOwnerUserIdAndSkillUuid(42L, "front")).thenReturn(Optional.of(frontend));
        when(skills.findByOwnerUserIdAndSkillUuid(42L, "requirements")).thenReturn(Optional.of(requirements));
        when(skills.findByOwnerUserIdAndSkillUuid(42L, "database")).thenReturn(Optional.of(database));
        DomainAgentConfig agent = new DomainAgentConfig("a", "s", "前端专家", null, null, null, "persona");
        agent.setSkillUuids(List.of("front", "requirements", "database"));

        SkillActivationService.Activation result = new SkillActivationService(skills, files)
                .activate(42L, agent, "请审查这个前端页面的交互和无障碍问题，并检查需求是否遗漏");

        assertEquals(2, result.invocations().size());
        assertEquals(List.of("front", "requirements"),
                result.invocations().stream().map(SkillActivationService.Invocation::skillUuid).toList());
        assertFalse(result.promptAddition().contains("数据库调优"));
    }

    private static SkillEntity skill(String uuid, String name, String description) {
        SkillEntity skill = new SkillEntity();
        ReflectionTestUtils.setField(skill, "id", (long) Math.abs(uuid.hashCode()));
        skill.setSkillUuid(uuid);
        skill.setOwnerUserId(42L);
        skill.setName(name);
        skill.setDescription(description);
        skill.setSkillMd("---\nname: " + name + "\ndescription: " + description + "\n---\n遵循此技能。");
        skill.setSourceType("WEB");
        return skill;
    }
}
