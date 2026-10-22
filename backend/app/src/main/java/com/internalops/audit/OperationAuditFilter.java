package com.internalops.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.internalops.api.ApiResponse;
import com.internalops.auth.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.util.Set;

/** Commands, automatic business changes and their audit are committed together. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OperationAuditFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(OperationAuditFilter.class);
    private final OperationRecordService records;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AuthService auth;
    private final boolean enabled;
    public OperationAuditFilter(OperationRecordService records,PlatformTransactionManager manager,ObjectMapper json,
                                AuthService auth,@Value("${app.operation-audit.enabled:true}")boolean enabled) {
        this.records=records;this.transactions=new TransactionTemplate(manager);this.json=json;this.auth=auth;this.enabled=enabled;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !enabled || !path.startsWith("/api/") || !Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())
                || path.startsWith("/api/auth/")
                || path.equals("/api/system/health") || path.equals("/api/auth/me")
                || path.startsWith("/api/dashboard") || path.matches("/api/product-images/[^/]+/content")
                || (path.startsWith("/api/operation-records") && request.getMethod().equals("GET"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        boolean mutation=Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod());
        CurrentUser actor=CurrentUser.get();
        if(actor==null && request.getRequestURI().equals("/api/auth/logout") && request.getCookies()!=null) {
            for(Cookie cookie:request.getCookies())if(cookie.getName().equals("OPS_SESSION"))actor=auth.current(cookie.getValue());
        }
        CurrentUser initialActor=actor;
        var buffered=new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status->{
                long id=records.begin(request.getMethod(),request.getRequestURI(),initialActor,request.getRemoteAddr(),mutation);
                OperationScope.set(id);
                try { chain.doFilter(request,buffered); }catch(IOException|ServletException e){throw new RequestFailure(e);}
                if(buffered.getStatus()>=400){status.setRollbackOnly();return;}
                CurrentUser finalActor=request.getAttribute("operationActor") instanceof CurrentUser user?user:initialActor;
                // Registered after transactional events: procurement runs before audit finalization.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {records.finish(id,buffered.getStatus(),finalActor,mutation);}
                });
            });
        } catch(Exception error) {
            log.error("Operation transaction failed: {} {}",request.getMethod(),request.getRequestURI(),error);
            buffered.reset();buffered.setStatus(500);buffered.setContentType("application/json;charset=UTF-8");
            String message="操作未完成，业务数据已回滚，请刷新后重试";
            buffered.getWriter().write(json.writeValueAsString(new ApiResponse<>(false,null,message)));
        } finally {OperationScope.clear();}
        buffered.copyBodyToResponse();
    }
    private static class RequestFailure extends RuntimeException {RequestFailure(Throwable cause){super(cause);} }
}
