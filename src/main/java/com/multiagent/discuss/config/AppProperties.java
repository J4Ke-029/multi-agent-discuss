package com.multiagent.discuss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Vision vision = new Vision();
    private final Discuss discuss = new Discuss();
    private final Session session = new Session();
    private final Host host = new Host();
    private final Memory memory = new Memory();

    public Vision getVision() {
        return vision;
    }

    public Discuss getDiscuss() {
        return discuss;
    }

    public Session getSession() {
        return session;
    }

    public Host getHost() {
        return host;
    }

    public Memory getMemory() {
        return memory;
    }

    /**
     * 经验心得的注入预算。
     *
     * <p>心得进的是该智能体<b>每一轮每一次发言</b>的 system prompt，而产品要求「不设字数上限、
     * 由模型自己决定」—— 存储上确实不设限，prompt 里也反复强调精简优先。这个开关是留给
     * 事后观测到「心得膨胀到拖垮 prompt」时的紧急刹车，默认 0 = 不截断。
     */
    public static class Memory {
        /** >0 时只截断心得正文，钉子（硬前提）永不截断。 */
        private int injectMaxChars = 0;

        public int getInjectMaxChars() {
            return injectMaxChars;
        }

        public void setInjectMaxChars(int injectMaxChars) {
            this.injectMaxChars = injectMaxChars;
        }
    }

    public static class Vision {
        private String apiKey = "sk-placeholder";
        private String baseUrl = "https://api.openai.com";
        private String model = "gpt-4o";
        private boolean enabled = true;
        private boolean fallbackWhenUnavailable = true;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFallbackWhenUnavailable() {
            return fallbackWhenUnavailable;
        }

        public void setFallbackWhenUnavailable(boolean fallbackWhenUnavailable) {
            this.fallbackWhenUnavailable = fallbackWhenUnavailable;
        }
    }

    public static class Discuss {
        private int defaultMaxRounds = 2;
        private boolean defaultAutoDiscuss = true;
        private int thinkTimeoutSeconds = 180;

        public int getDefaultMaxRounds() {
            return defaultMaxRounds;
        }

        public void setDefaultMaxRounds(int defaultMaxRounds) {
            this.defaultMaxRounds = defaultMaxRounds;
        }

        public boolean isDefaultAutoDiscuss() {
            return defaultAutoDiscuss;
        }

        public void setDefaultAutoDiscuss(boolean defaultAutoDiscuss) {
            this.defaultAutoDiscuss = defaultAutoDiscuss;
        }

        public int getThinkTimeoutSeconds() {
            return thinkTimeoutSeconds;
        }

        public void setThinkTimeoutSeconds(int thinkTimeoutSeconds) {
            this.thinkTimeoutSeconds = thinkTimeoutSeconds;
        }
    }

    public static class Session {
        private int historyLimit = 20;
        private int visionPageConcurrency = 3;
        private String tempDir = System.getProperty("java.io.tmpdir") + "/multi-agent-discuss";

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }

        public int getVisionPageConcurrency() {
            return visionPageConcurrency;
        }

        public void setVisionPageConcurrency(int visionPageConcurrency) {
            this.visionPageConcurrency = visionPageConcurrency;
        }

        public String getTempDir() {
            return tempDir;
        }

        public void setTempDir(String tempDir) {
            this.tempDir = tempDir;
        }
    }

    public static class Host {
        private String systemPrompt = "你是多智能体讨论的主持人。";

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }

}
