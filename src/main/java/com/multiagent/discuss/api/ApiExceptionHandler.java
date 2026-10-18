package com.multiagent.discuss.api;

import com.multiagent.discuss.security.AuthException;
import com.multiagent.discuss.util.ErrorMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一异常处理：把 Spring / Spring AI / 网络 / 业务异常翻译成「中文 + 该怎么做」。
 *
 * <p>响应体字段：
 * <ul>
 *   <li>{@code status}  HTTP 状态码</li>
 *   <li>{@code error}   HTTP 状态短语（英文，机器可读）</li>
 *   <li>{@code code}    业务错误码（机器可读）</li>
 *   <li>{@code message} 中文错误描述（面向用户）</li>
 *   <li>{@code hint}    「该怎么做」的中文提示（面向用户）</li>
 *   <li>{@code traceId} 错误追踪 ID（关联服务端日志）</li>
 *   <li>{@code detail}  可公开的补充信息（可选，不包含底层异常或上游响应）</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 业务错误码前缀，运维排查可按此 grep 服务端日志 */
    public static final String E_VALIDATION = "VALIDATION";
    public static final String E_BAD_REQUEST = "BAD_REQUEST";
    public static final String E_CONFLICT = "CONFLICT";
    public static final String E_NOT_FOUND = "NOT_FOUND";
    public static final String E_METHOD = "METHOD_NOT_ALLOWED";
    public static final String E_MEDIA = "UNSUPPORTED_MEDIA_TYPE";
    public static final String E_PAYLOAD = "PAYLOAD_TOO_LARGE";
    public static final String E_UPSTREAM = "UPSTREAM_ERROR";
    public static final String E_NETWORK = "NETWORK_ERROR";
    public static final String E_INTERNAL = "INTERNAL_ERROR";
    public static final String E_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String E_USER_EXISTS = "USER_EXISTS";
    public static final String E_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

    // ─── 业务异常 ─────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, E_BAD_REQUEST, ex.getMessage(),
                ErrorMessages.hintFor(ex.getMessage()),
                null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        String message = ErrorMessages.publicStateMessage(ex);
        String code = (message.contains("已被注册"))
                ? E_USER_EXISTS
                : E_CONFLICT;
        return body(HttpStatus.CONFLICT, code, message,
                ErrorMessages.hintFor(message),
                null);
    }

    // ─── 鉴权 ─────────────────────────────────────────────────

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(AuthException ex) {
        String code = ex.getMessage() != null && ex.getMessage().contains("账号")
                ? E_INVALID_CREDENTIALS
                : E_UNAUTHORIZED;
        // INVALID_CREDENTIALS 也用 401（业务上"账号或密码错"与"未登录"都是无权限访问）
        return body(HttpStatus.UNAUTHORIZED, code, ex.getMessage(),
                "请重新登录后再试。",
                null);
    }

    // ─── 校验 / 请求格式 ──────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        String fieldMsg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + "：" + e.getDefaultMessage())
                .orElse("请求参数不合法");
        return body(HttpStatus.BAD_REQUEST, E_VALIDATION, fieldMsg,
                "请按上方字段名修正请求体后再提交。",
                null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException ex) {
        String msg = "缺少必填参数：" + ex.getParameterName();
        return body(HttpStatus.BAD_REQUEST, E_VALIDATION, msg,
                "请在 URL 或表单中补齐参数 " + ex.getParameterName() + " 后再提交。",
                null);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> missingPart(MissingServletRequestPartException ex) {
        String msg = "上传请求缺少文件部分：" + ex.getRequestPartName();
        return body(HttpStatus.BAD_REQUEST, E_VALIDATION, msg,
                "请使用 multipart/form-data 上传，字段名为 file。",
                null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> notReadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, E_VALIDATION,
                "请求体无法解析（JSON 格式错误或字段类型不匹配）",
                "请检查 JSON 是否完整、字段类型是否一致（字符串加引号、布尔用 true/false）。",
                null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, E_MEDIA,
                "不支持的请求格式：" + ex.getContentType(),
                "JSON 请求请设置 Content-Type: application/json；上传文件请用 multipart/form-data。",
                null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, E_METHOD,
                "该接口不支持 " + ex.getMethod() + " 方法",
                "请参考 API 文档使用正确的方法（GET / POST / PUT / DELETE）。",
                ex.getSupportedHttpMethods() == null ? null
                        : "支持的方法：" + ex.getSupportedHttpMethods());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ex) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, E_PAYLOAD,
                "上传文件超过大小限制",
                "请把 PDF / DOCX 压缩到 50MB 以内，或在 application.yml 调大 spring.servlet.multipart.max-file-size。",
                null);
    }

    // ─── 上游 / 网络 ──────────────────────────────────────────

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> upstream(org.springframework.web.client.ResourceAccessException ex) {
        String msg = ErrorMessages.summarizeNetworkError(ex);
        return body(HttpStatus.BAD_GATEWAY, E_NETWORK, msg,
                "请检查：① 第三方网关 baseUrl 是否可达；② 本机网络 / 代理设置；③ API Key 是否有效。",
                null);
    }

    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> upstreamClient(org.springframework.web.client.HttpClientErrorException ex) {
        String msg = ErrorMessages.summarizeUpstreamError(ex.getStatusCode().value());
        return body(HttpStatus.BAD_GATEWAY, E_UPSTREAM, msg,
                "请检查：① API Key 是否正确且未过期；② baseUrl 是否拼对（不要带 /v1）；③ 模型名是否被该网关支持。",
                null);
    }

    @ExceptionHandler(org.springframework.web.client.HttpServerErrorException.class)
    public ResponseEntity<Map<String, Object>> upstreamServer(org.springframework.web.client.HttpServerErrorException ex) {
        return body(HttpStatus.BAD_GATEWAY, E_UPSTREAM,
                "第三方网关返回 5xx：" + ex.getStatusCode().value(),
                "稍后重试，或切换到备用供应商（可在创建会话时换 llmApiKey / llmBaseUrl）。",
                null);
    }

    // ─── 兜底 ─────────────────────────────────────────────────

    /**
     * 路径没匹配到任何 handler（Spring 6.1+ 抛这个，而不是静默 404）。
     *
     * <p>最常见的原因是**前端比后端新**——前端调了一个后端还没注册的端点。
     * 不单独接住的话会掉进下面的 {@link #other} 变成 500「服务器内部错误：No static resource …」，
     * 既误导（其实是 404 不是 500），又把 Spring 的内部措辞漏给了用户。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, E_NOT_FOUND,
                "接口不存在：/" + ex.getResourcePath(),
                "该端点后端未注册。若是前端刚加的功能，重启后端即可。",
                null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception ex) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] 未捕获异常，类型={}", traceId, ex.getClass().getName());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, E_INTERNAL, "服务器内部错误",
                "已记录 traceId=" + traceId + "，可反馈给开发者。刷新页面或重试一次。",
                null, traceId);
    }

    // ─── 工具 ─────────────────────────────────────────────────

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                            String hint, String detail) {
        return body(status, code, message, hint, detail, UUID.randomUUID().toString().substring(0, 8));
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                            String hint, String detail, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("error", status.getReasonPhrase());
        m.put("code", code);
        m.put("message", message);
        if (hint != null) m.put("hint", hint);
        m.put("traceId", traceId);
        if (detail != null && !detail.isBlank()) m.put("detail", detail);
        return ResponseEntity.status(status).body(m);
    }
}
