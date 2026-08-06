package com.internalops.audit;

import com.internalops.auth.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OperationAuditFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    public OperationAuditFilter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request,response);
        if (response.getStatus() < 400 && ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()) || "DELETE".equals(request.getMethod())) && !request.getRequestURI().startsWith("/api/auth/")) {
            CurrentUser user=CurrentUser.get(); if(user!=null) try { jdbc.update("INSERT INTO operation_log(business_type,business_id,action,change_detail,operated_by,operated_at) VALUES(?,?,?,CAST(? AS JSON),?,CURRENT_TIMESTAMP)","HTTP",0,request.getMethod()+" "+request.getRequestURI(),"{}",user.id()); } catch (RuntimeException ignored) { }
        }
    }
}
