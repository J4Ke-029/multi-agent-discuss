package com.multiagent.discuss.agent;

import com.multiagent.discuss.config.AppProperties;
import com.multiagent.discuss.domain.DomainAgentConfig;
import com.multiagent.discuss.domain.LlmEndpoint;
import com.multiagent.discuss.domain.Session;
import com.multiagent.discuss.util.OpenAiEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 DomainAgent 独立 ChatClient；Host 使用全局默认模型。
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ChatModel defaultChatModel;
    private final AppProperties props;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    public AgentFactory(ChatModel defaultChatModel, AppProperties props) {
        this.defaultChatModel = defaultChatModel;
        this.props = props;
    }

    /**
     * Host：优先用会话默认网关，否则用全局 spring.ai.openai 默认模型。
     */
    public ChatClient hostClient(Session session) {
        if (session != null && session.getLlmEndpoint() != null && session.getLlmEndpoint().hasCredentials()) {
            LlmEndpoint ep = session.getLlmEndpoint();
            String model = StringUtils.hasText(ep.getModel()) ? ep.getModel() : "gpt-4o-mini";
            String cacheKey = "host:" + session.getId() + ":" + model + ":" + safe(ep.getBaseUrl()) + ":" + mask(ep.getApiKey());
            return clientCache.computeIfAbsent(cacheKey, k -> {
                ChatModel chatModel = buildOpenAiCompatibleModel(ep.getApiKey(), ep.getBaseUrl(), model);
                return ChatClient.builder(chatModel)
                        .defaultSystem(props.getHost().getSystemPrompt())
                        .build();
            });
        }
        return hostClient();
    }

    public ChatClient hostClient() {
        return ChatClient.builder(defaultChatModel)
                .defaultSystem(props.getHost().getSystemPrompt())
                .build();
    }

    public ChatClient forAgent(DomainAgentConfig agent) {
        String cacheKey = agent.getSessionId() + ":" + agent.getId() + ":" + agent.getModel()
                + ":" + safe(agent.getBaseUrl()) + ":" + mask(agent.getApiKey())
                + ":p:" + safe(agent.getPersona()) + ":pe:" + safe(agent.getPersonality())
                + ":m:" + (agent.memoryVersion() == null ? "" : agent.memoryVersion());
        return clientCache.computeIfAbsent(cacheKey, k -> {
            ChatModel model = buildOpenAiCompatibleModel(agent.getApiKey(), agent.getBaseUrl(), agent.getModel());
            String system = buildDomainSystem(agent);
            return ChatClient.builder(model).defaultSystem(system).build();
        });
    }

    /**
     * 「任务型」一次性客户端：用某个席位已解析好的网关，跑一段短任务（目前只有经验总结）。
     *
     * <p>刻意复用 {@code buildOpenAiCompatibleModel} —— 席位在加入会话时就已经把网关解析完了
     * （自己的 Key 或继承会话网关），这里零新增解析逻辑，也就不会出现「讨论走 A 网关、
     * 总结走 B 网关」这种口径分裂。
     */
    public ChatClient taskClient(DomainAgentConfig agent, String systemPrompt) {
        ChatModel model = buildOpenAiCompatibleModel(agent.getApiKey(), agent.getBaseUrl(), agent.getModel());
        return ChatClient.builder(model).defaultSystem(systemPrompt == null ? "" : systemPrompt).build();
    }

    public ChatClient userDocClient(DomainAgentConfig styleCarrierOrNull) {
        if (styleCarrierOrNull != null
                && StringUtils.hasText(styleCarrierOrNull.getApiKey())
                && StringUtils.hasText(styleCarrierOrNull.getModel())) {
            return forAgent(styleCarrierOrNull);
        }
        return ChatClient.builder(defaultChatModel)
                .defaultSystem("""
                        你是用户文档风格智能体（UserDocAgent）。
                        只做风格辅助：在不改变专业含义的前提下，让表述贴合作者文风。
                        不参与专业判断、不否决专业结论、不参与中间讨论。
                        """)
                .build();
    }

    public ChatClient openAiCompatible(String apiKey, String baseUrl, String model, String systemPrompt) {
        ChatModel chatModel = buildOpenAiCompatibleModel(apiKey, baseUrl, model);
        return ChatClient.builder(chatModel).defaultSystem(systemPrompt == null ? "" : systemPrompt).build();
    }

    private ChatModel buildOpenAiCompatibleModel(String apiKey, String baseUrl, String model) {
        String key = sanitizeApiKey(apiKey);
        if (!StringUtils.hasText(key)) {
            key = "sk-placeholder";
        }
        // 不同网关路径不同：OpenAI 官方 /v1、智谱 GLM /v4、各类中转根。
        // 由 OpenAiEndpointResolver 按末尾版本段决定 completionsPath，
        // 避免 /v4 网关被拼成 /v4/v1/chat/completions 而 404。
        OpenAiEndpointResolver.Resolved ep = OpenAiEndpointResolver.resolve(baseUrl);
        String modelName = StringUtils.hasText(model) ? model.trim() : "gpt-4o-mini";

        OpenAiApi api = OpenAiApi.builder()
                .apiKey(key)
                .baseUrl(ep.baseUrl())
                .completionsPath(ep.completionsPath())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.4)
                .build();

        log.info("Build ChatModel model={} baseUrl={} completionsPath={}", modelName, ep.baseUrl(), ep.completionsPath());
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    /**
     * 去掉首尾空白/换行、误粘贴的 Bearer 前缀、包裹引号。
     * HTTP Authorization 头不能含中文、换行等非法字符。
     */
    static String sanitizeApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        String k = apiKey.trim()
                .replace("\r", "")
                .replace("\n", "")
                .replace("\t", "");
        // 去掉用户误贴的 Bearer 前缀
        if (k.regionMatches(true, 0, "Bearer ", 0, 7)) {
            k = k.substring(7).trim();
        }
        // 去掉包裹引号
        if ((k.startsWith("\"") && k.endsWith("\"")) || (k.startsWith("'") && k.endsWith("'"))) {
            k = k.substring(1, k.length() - 1).trim();
        }
        return k;
    }

    /**
     * 组装领域席位的 system prompt。
     *
     * <p>包级可见是为了让 {@code AgentMemoryPromptTest} 直接断言「无记忆时与改动前逐字节相同」——
     * 这条不变量靠三件事同时成立：{@link AgentMemoryPrompt#memoryBlock} 对空输入返回空串、
     * 席位上的记忆字段默认 null、以及这个方法只做 append（空串 append 是 no-op）。
     */
    String buildDomainSystem(DomainAgentConfig agent) {
        StringBuilder sb = new StringBuilder();

        // 1. 性格锁定：放最前面，让模型先记住性格再读其它指令
        if (StringUtils.hasText(agent.getPersonality())) {
            sb.append(personalityBlock(agent.getPersonality()));
        }

        // 2. 人设 / 角色
        String persona = StringUtils.hasText(agent.getPersona())
                ? agent.getPersona()
                : "你是专业领域分析智能体。";
        sb.append("【你的角色】\n").append(persona).append("\n\n");

        // 3. 跨会话经验心得 + 用户钉死的硬前提。
        //    走 system 而不是每轮 user prompt：心得与会话同生命周期，加入时快照即固定，
        //    而且自动覆盖独立思考 / 讨论 / 改稿三条路径，不用逐处加。
        //    空记忆 → 空串 → append 是 no-op → prompt 与改动前逐字节相同。
        sb.append(AgentMemoryPrompt.memoryBlock(
                agent.getAgentMemory(), agent.getAgentPins(), props.getMemory().getInjectMaxChars()));

        // 4. 规则 + 表达硬性约束
        sb.append("""
                【规则】
                1. 仅基于分配给你的私有文献与 Vision 报告作答，不要臆造他人文献内容。
                2. 讨论中你只能看到其他智能体的发言文本，看不到其原始文献。
                3. 输出尽量结构化、可引用页/表/图。
                4. 若对识图结果存疑，请明确写出 doubts。

                【表达硬性约束】
                - 所有发言必须严格遵循【性格锁定】段所设性格
                - 用词、句式、语气都要让读者一眼感受到这种性格
                - 性格只影响表达方式，绝不能编造文献中不存在的事实
                """);
        return sb.toString();
    }

    /**
     * 性格关键词展开表。常见性格会展开成可操作的行为清单，
     * 命中多个关键词就拼接多个清单；自定义词原样保留在原字符串中。
     */
    private static final Map<String, String> PERSONALITY_PRESETS = new LinkedHashMap<>();
    static {
        PERSONALITY_PRESETS.put("严谨", """
                - 用词精确，措辞客观，避免"非常""极其"等夸张副词
                - 任何观点必须给出文献页码 /表号/图号或具体实验依据
                - 论证逻辑链条清晰：先前提、再推论、最后结论
                - 拒绝无据猜测，遇到不确定时说"目前文献未支持"，不说"应该"
                - 不轻易给出态度性判断，只陈述证据
                """);
        PERSONALITY_PRESETS.put("毒舌", """
                - 直白不绕弯，缺点直接点名，不用"可能""也许"打圆场
                - 敢于指出他人观点中的逻辑漏洞、用词不当、证据不足
                - 语气犀利略带讽刺，但仍保留专业依据，绝不进行人身攻击
                - 宁可得罪人也绝不奉承，发现错误就立即反驳
                - 短句优先，避免冗长客套
                """);
        PERSONALITY_PRESETS.put("幽默", """
                - 语气轻松活泼，可以用类比、比喻、生活化例子解释专业概念
                - 适当自嘲或调侃复杂问题，让讨论氛围更轻松
                - 但幽默不能损害专业准确性，关键数据 /结论必须严肃陈述
                - 避免冷笑话或过度卖弄，影响讨论效率
                """);
        PERSONALITY_PRESETS.put("温柔", """
                - 温和有礼，多用共情语言（"这个问题确实有挑战""您提的很有价值"）
                - 避免尖锐批评，给不同意见时先肯定再补充
                - 给出鼓励性反馈（"如果我们一起看…会更清楚"）
                - 用引导式而非命令式表达
                """);
        PERSONALITY_PRESETS.put("固执", """
                - 坚持自己基于文献的判断，不轻易被他人意见左右
                - 当他人质疑时，引用文献反复论证自己的观点
                - 即便多数派反对，也坚持给出自己的依据
                - 但要诚实面对确凿反证，不能为反而反
                """);
        PERSONALITY_PRESETS.put("激进", """
                - 敢于提出大胆假设、挑战领域内主流观点
                - 强调创新和前沿，宁可假设"如果…会怎样"也要探索
                - 对经典文献中的结论也保持开放质疑
                - 但所有激进观点仍需文献或逻辑依据支撑
                """);
        PERSONALITY_PRESETS.put("保守", """
                - 强调稳妥和已被广泛验证的结论
                - 引用经典文献和综述作为主要依据
                - 对新观点持谨慎态度，要求更多数据支持
                - 不轻易下结论，宁可"目前证据不足以判断"
                """);
        PERSONALITY_PRESETS.put("怀疑", """
                - 质疑前提，追问每一条结论背后的依据
                - 要求数据支持，不接受没有实验 /文献依据的论断
                - 反复追问"为什么""你怎么知道"
                - 倾向于给出"目前尚不能确定"而非轻率结论
                """);
    }

    /**
     * 把性格关键词按预设展开成具体行为清单；未命中的关键词原样保留在原字符串中。
     */
    static String expandPersonality(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String p = raw.trim();
        StringBuilder behaviors = new StringBuilder();
        for (String token : p.split("[\\s,，、]+")) {
            String norm = token.trim();
            if (norm.isEmpty()) continue;
            String preset = PERSONALITY_PRESETS.get(norm);
            if (preset != null) {
                behaviors.append("\n【").append(norm).append("行为清单】\n").append(preset);
            }
        }
        return behaviors.length() == 0 ? p : (p + behaviors);
    }

    /**
     * 性格锁定段：作为 system prompt 的最高优先级硬指令。
     * 不依赖预设表——任意性格词（即使没在训练数据里见过）都要求 LLM 自己演绎。
     * 预设表只作为"命中则参考"的补充，不决定性格是否生效。
     */
    static String personalityBlock(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String expanded = expandPersonality(raw);
        return """
                【性格锁定 · 最高优先级】

                你被设定的性格是：%s

                【真实性优先 · 红线】
                性格只决定"怎么说"，**绝不决定"说什么"**：
                - 用词、语气、句式、修辞、比喻、夸张程度——可以随性格调整
                - 事实、数据、文献引用、现象是否存在、机制是否成立——**绝不**因性格改变
                - 即便性格要求"激进""毒舌""傲娇""病娇""调皮"等，也绝不能编造文献中没出现的结论、数据、页码、机制
                - 遇到文献未支持的命题，必须明确写"目前文献未支持""未见依据""无相关数据"
                - 性格演绎出的"行为清单"也不得包含"捏造 /编造 /虚构事实"指令——这是反人格底线的硬约束
                - 引用文献时必须给真实的页 /表 /图 /DOI；不记得就说"未明确页码"，不编一个

                【演绎指令 · 必须先做】
                把上述性格在"内心"演绎成 5-8 条具体行为清单，覆盖：
                - 用词偏好（喜欢 /避免哪些词、词组、语气词）
                - 语气基调（热情 /冷淡 /尖锐 /温和 /严肃 /戏谑 /阴郁 …）
                - 句式偏好（短句 /长句 /倒装 /感叹 /省略 …）
                - 表达禁忌（不主动寒暄 /不用表情 /不道歉 /不客套 /不绕弯 …按性格取舍）
                - 自我校验信号（什么样的句子背离了这一性格）

                接下来独立思考 + 讨论的所有发言都必须严格执行这份演绎清单，但永远受【真实性优先】红线约束——真实性 >性格演绎。

                【任意性格都要生效】
                即便用户输入的性格你没在训练数据里见过（例如"冷峻孤傲""傲娇""大叔""病娇""杀马特""社恐"），也要按你对该词的理解演绎出行为清单并执行。
                **绝不能**因为性格词不常见就退回到中性语气——任何性格词都要在发言中明显体现。

                【性格硬约束】
                - 这是你的"人格底层"，**任何场合下都不改变**
                - 性格只影响表达方式，绝不能编造文献中不存在的事实（与【真实性优先】互为冗余强调）

                【自我校验】
                每次发言生成后，自检：
                1. 语气、用词、句式是否体现所设性格？若偏离，立即在下一句修正。
                2. 提到的数据 /文献 /结论是否真实来自私有文献？若没依据，立即改写或标注"未见依据"。

                """.formatted(expanded);
    }

    /**
     * 拼接在 user prompt 末尾的性格 reminder。每轮发言都强化一次。
     * 不再写具体性格名——直接指向 system 中"性格锁定"段，避免与 system prompt 重复。
     */
    public static String personalityReminder(DomainAgentConfig agent) {
        if (!StringUtils.hasText(agent.getPersonality())) return "";
        return "\n\n【本段发言风格 · 必须】"
                + "延续 system prompt 中【性格锁定】段所设的人格（"
                + agent.getPersonality().trim()
                + "）。"
                + "先用一两句话在心中自检：本句的语气、用词、句式是否符合该性格？"
                + "绝不能退回到中性平淡的表述，"
                + "也不能因为性格词陌生就弱化演绎。"
                + "即便要求输出 JSON，viewpoint /content 字段的语言也要体现这种性格。"
                + "\n\n【真实性红线 · 不可逾越】"
                + "性格只改变表达（用词、语气、句式、修辞），绝不改变内容："
                + "即便该性格要求\"激进\"\"毒舌\"\"傲娇\"\"夸张\"\"不羁\"，"
                + "也绝不能编造文献中没出现的结论、数据、页码或机制；"
                + "遇到文献未支持的命题，写\"目前文献未支持\"\"未见依据\"\"无相关数据\"；"
                + "真实性 >性格演绎——人格再强烈也不能凌驾于事实之上。";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 清掉某会话在缓存里的全部 ChatClient（换网关 / 删会话 / 移除智能体时调用）。
     *
     * <p>键有两种前缀：领域智能体是 {@code <sessionId>:<agentId>:...}，
     * Host 是 {@code host:<sessionId>:...}（见 {@link #hostClient(Session)}）。
     * 只匹配前者会漏掉 Host，导致这些条目永远留在 {@code clientCache} 里
     * ——每个都挂着一个 OpenAiChatModel 与它的连接池，随会话数单调增长。
     */
    public void evictSession(String sessionId) {
        String agentPrefix = sessionId + ":";
        String hostPrefix = "host:" + sessionId + ":";
        clientCache.keySet().removeIf(k -> k.startsWith(agentPrefix) || k.startsWith(hostPrefix));
    }
}
