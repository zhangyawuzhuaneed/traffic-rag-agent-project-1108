package com.example.tunnel.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;

/**
 * 统一异常映射。LLM 调用失败时（401 / 超时 / 限流）不要让原始堆栈直接返回给前端，
 * 但服务端必须记全，否则线上没法定位是「模型挂了」还是「提示词写错了」。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);


        // 专门处理404路由不存在
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        // 如果是 favicon.ico，直接静默返回404，不打error日志
        if (e.getRequestURL().endsWith("/favicon.ico")) {
            return ResponseEntity.notFound().build();
        }
        // 其他正常404，走原有逻辑
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST", "message", e.getMessage()));
    }

    /** 知识库尚未就绪：明确返回 503，让调用方 / 网关可以重试，而不是拿到一份残缺答案。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> notReady(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "KNOWLEDGE_BASE_NOT_READY", "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception e) {
        log.error("AI 调用失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "AI_CALL_FAILED",
                "message", "服务暂时不可用，请稍后重试或联系值班人员；详细原因见服务端日志"));
    }
}
