package com.multiagent.discuss.api.dto;

import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.domain.Turn;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 汇总单个议题的讨论过程，拼成一段 Markdown（模板由 LLM 填充）。
 */
public class DiscussionDigest {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
            .withZone(ZoneId.systemDefault());

    public final String questionId;
    public final String question;
    public final String createdAt;
    public final List<String> participants = new ArrayList<>();
    public final List<DigestTurn> independentThoughts = new ArrayList<>();
    public final List<DigestRound> rounds = new ArrayList<>();
    public final DigestConsensus consensus = new DigestConsensus();

    public DiscussionDigest(Question q, Session session) {
        this.questionId = q.getId();
        this.question = q.getContent();
        this.createdAt = TS.format(q.getCreatedAt());

        // 参与讨论（非 skipped）的智能体
        q.getThoughts().forEach((aid, t) -> {
            if (t != null && t.getState() != null
                    && t.getState() != com.multiagent.discuss.domain.AgentThought.State.SKIPPED) {
                participants.add(t.getAgentName() != null ? t.getAgentName() : aid);
            }
        });
        if (participants.isEmpty()) {
            session.getAgents().values().forEach(a -> participants.add(a.getName()));
        }

        Map<Integer, DigestRound> byRound = new LinkedHashMap<>();
        for (Turn t : q.getTranscript()) {
            if (t == null) {
                continue;
            }
            if (t.getPhase() == Turn.Phase.INDEPENDENT_THINK) {
                independentThoughts.add(new DigestTurn(t));
            } else if (t.getPhase() == Turn.Phase.DISCUSSION
                    || t.getPhase() == Turn.Phase.USER_INTERJECTION) {
                int r = Math.max(0, t.getRound());
                DigestRound round = byRound.computeIfAbsent(r, DigestRound::new);
                round.turns.add(new DigestTurn(t));
            }
        }
        // 补全缺失轮次编号，保证 markdown 里的 R1/R2 连续
        if (!byRound.isEmpty()) {
            int min = byRound.keySet().stream().mapToInt(Integer::intValue).min().orElse(1);
            int max = byRound.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
            for (int r = min; r <= max; r++) {
                if (byRound.containsKey(r)) {
                    rounds.add(byRound.get(r));
                }
            }
        }
    }

    public void fillConsensus(com.multiagent.discuss.domain.ProfessionalConsensus c) {
        if (c == null) {
            return;
        }
        if (c.getSummary() != null) {
            consensus.summary = c.getSummary();
        }
        if (c.getConclusions() != null) {
            consensus.conclusions.addAll(c.getConclusions());
        }
        if (c.getCorrections() != null) {
            consensus.corrections.addAll(c.getCorrections());
        }
        if (c.getAdditions() != null) {
            consensus.additions.addAll(c.getAdditions());
        }
        if (c.getRawText() != null && consensus.summary == null) {
            consensus.summary = c.getRawText();
        }
    }

    /** 把本轮内容拼给 LLM：每轮一两句、要点列表，不要原文大段粘贴。 */
    public String promptMaterial() {
        StringBuilder sb = new StringBuilder();
        sb.append("独立观点：\n");
        for (DigestTurn t : independentThoughts) {
            sb.append("- ").append(t.speakerName).append("：").append(t.content).append('\n');
        }
        sb.append("\n圆桌讨论：\n");
        for (DigestRound r : rounds) {
            sb.append("第 ").append(r.round).append(" 轮：\n");
            for (DigestTurn t : r.turns) {
                sb.append("  - ").append(t.speakerName).append("：").append(t.content).append('\n');
            }
        }
        return sb.toString();
    }

    public static class DigestTurn {
        public final String speakerName;
        public final String content;

        public DigestTurn(Turn t) {
            this.speakerName = t.getSpeakerName() != null ? t.getSpeakerName() : t.getSpeakerId();
            this.content = t.getContent();
        }
    }

    public static class DigestRound {
        public final int round;
        public final List<DigestTurn> turns = new ArrayList<>();

        public DigestRound(int round) {
            this.round = round;
        }
    }

    public static class DigestConsensus {
        public String summary;
        public final List<String> conclusions = new ArrayList<>();
        public final List<String> corrections = new ArrayList<>();
        public final List<String> additions = new ArrayList<>();

        public String promptText() {
            StringBuilder sb = new StringBuilder();
            if (summary != null) {
                sb.append("共识摘要：").append(summary).append('\n');
            }
            if (!conclusions.isEmpty()) {
                sb.append("结论：").append(String.join("；", conclusions)).append('\n');
            }
            if (!corrections.isEmpty()) {
                sb.append("应修正：").append(String.join("；", corrections)).append('\n');
            }
            if (!additions.isEmpty()) {
                sb.append("应增补：").append(String.join("；", additions)).append('\n');
            }
            return sb.toString();
        }
    }

    public static List<String> participantNames(List<Question> questions) {
        return questions.stream()
                .flatMap(q -> q.getThoughts().values().stream())
                .filter(t -> t != null && t.getAgentName() != null)
                .map(t -> t.getAgentName())
                .distinct()
                .collect(Collectors.toList());
    }
}
