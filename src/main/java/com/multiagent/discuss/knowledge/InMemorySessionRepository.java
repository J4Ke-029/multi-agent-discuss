package com.multiagent.discuss.knowledge;

import com.multiagent.discuss.domain.Question;
import com.multiagent.discuss.domain.Session;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionRepository {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Question> questions = new ConcurrentHashMap<>();

    public void saveSession(Session session) {
        sessions.put(session.getId(), session);
    }

    public Optional<Session> findSession(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /**
     * 全部会话的快照（顺序不保证）。
     * 按用户过滤由 {@code SessionService.listVisibleSessions()} 负责 —— 归属口径只放一处。
     */
    public java.util.List<Session> findAll() {
        return new java.util.ArrayList<>(sessions.values());
    }

    public Session requireSession(String id) {
        Session s = sessions.get(id);
        if (s == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        return s;
    }

    public void deleteSession(String id) {
        sessions.remove(id);
        questions.entrySet().removeIf(e -> id.equals(e.getValue().getSessionId()));
    }

    public void saveQuestion(Question question) {
        questions.put(question.getId(), question);
    }

    public Optional<Question> findQuestion(String id) {
        return Optional.ofNullable(questions.get(id));
    }

    public Question requireQuestion(String sessionId, String questionId) {
        Question q = questions.get(questionId);
        if (q == null || !sessionId.equals(q.getSessionId())) {
            throw new IllegalArgumentException("问题不存在: " + questionId);
        }
        return q;
    }

    /** 一场会话内每位智能体只保留最近三条调用记录，跨议题统一裁剪。 */
    public synchronized void trimSkillInvocations(String sessionId, String speakerId) {
        Session session = requireSession(sessionId);
        int remaining = 3;
        for (int i = session.getQuestionIds().size() - 1; i >= 0; i--) {
            Question question = questions.get(session.getQuestionIds().get(i));
            if (question != null) {
                remaining = question.keepMostRecentSkillInvocations(speakerId, remaining);
            }
        }
    }
}
