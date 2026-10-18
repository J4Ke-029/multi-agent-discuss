package com.multiagent.discuss.util;

import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 把底层异常的英文 message 提炼成简短中文 + 「该怎么做」的归类器。
 *
 * <p>注意：这里只做"翻译 + 归类"，不要尝试覆盖所有错误码；找不到匹配就返回 null，
 * 由 {@link com.multiagent.discuss.api.ApiExceptionHandler} 退回到默认 hint。
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    /**
     * 根据业务异常 message 猜测对应的中文 hint。
     * 仅处理业务代码里抛出的 IllegalArgumentException / IllegalStateException。
     */
    public static String hintFor(String msg) {
        if (msg == null) return null;
        String m = msg.toLowerCase();

        // —— 智能体相关 ——
        if (m.contains("智能体不存在")) return "请刷新页面获取最新智能体列表，或在左侧重新注册该智能体。";
        if (m.contains("至少需要 1 个 knowledgeready") || m.contains("至少 1 个 knowledgeready") || m.contains("知识未就绪"))
            return "请先给至少一位智能体上传并完成文献处理（状态变为「知识就绪」），再提问。";
        if (m.contains("当前状态不允许开始讨论")) return "请先等待全部智能体独立思考结束，再点击「开启圆桌讨论」。";
        if (m.contains("独立思考尚未全部完成")) return "请等待剩余智能体独立思考结束后再开讨论（避免栅栏未齐套）。";
        if (m.contains("没有完成独立思考的智能体")) return "所有智能体独立思考均失败，无法开讨论。请检查 API Key 或更换模型。";
        if (m.contains("doceditmode 已开但用户文档未就绪") || m.contains("当前会话未开启 doceditmode"))
            return "请在创建会话时勾选「改稿模式」后再上传用户文档。";
        if (m.contains("用户文档处理失败") || m.contains("用户原文不可用"))
            return "请检查用户文档是否完整、可解析；DOCX 需可正常打开。";
        if (m.contains("文献处理失败")) return "请确认 PDF / DOCX 文件未损坏；PDF 大小建议 < 50MB。";

        // —— 网关 / Key ——
        if (m.contains("未提供 apikey") && m.contains("会话未配置默认网关"))
            return "请在创建会话时填写 llmApiKey，或在注册智能体时单独提供 apiKey。";
        if (m.contains("api key"))
            return "请检查 API Key 是否完整、是否被多余空格或 Bearer 前缀污染（不要加 Bearer）。";

        // —— 总结 ——
        if (m.contains("当前会话还没有议题") || m.contains("没有可总结的议题"))
            return "请先在底部提出至少一个议题并完成讨论，再生成总结。";

        // —— 改稿 ——
        if (m.contains("尚无 revisionplan")) return "请先等待修改建议生成完毕。";
        if (m.contains("请先确认至少一项修改")) return "请在修改建议中至少勾选一项要改的条目。";

        return null;
    }

    /** 包装了技术根因的状态异常只返回稳定的业务摘要，避免路径、响应体或凭据泄漏。 */
    public static String publicStateMessage(IllegalStateException ex) {
        String message = ex.getMessage();
        if (ex.getCause() == null) return message == null || message.isBlank() ? "当前操作无法完成" : message;
        if (message != null) {
            for (String prefix : new String[]{"Vision 失败", "文献处理失败", "用户文档处理失败",
                    "导师论文处理失败", "归档内容解析失败", "归档失败", "GitHub 下载失败"}) {
                if (message.startsWith(prefix)) return prefix;
            }
        }
        return "当前操作无法完成";
    }

    /**
     * 提炼 ResourceAccessException（通常是 connect / read timeout）的根因到中文短语。
     */
    public static String summarizeNetworkError(ResourceAccessException ex) {
        Throwable root = rootCause(ex);
        if (root instanceof ConnectException) {
            return "无法连接到第三方网关";
        }
        if (root instanceof SocketTimeoutException) {
            return "等待第三方网关响应超时";
        }
        if (root instanceof UnknownHostException) {
            return "无法解析第三方网关域名";
        }
        return "调用第三方网关失败";
    }

    /**
     * 上游 4xx / 5xx 错误摘要。
     */
    public static String summarizeUpstreamError(int status) {
        return "第三方网关返回 " + status;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
