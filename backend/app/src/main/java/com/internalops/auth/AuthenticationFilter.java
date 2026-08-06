package com.internalops.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticationFilter extends OncePerRequestFilter {
    private final AuthService auth;
    public AuthenticationFilter(AuthService auth) { this.auth=auth; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI(); return path.startsWith("/api/auth/") || path.equals("/api/system/health") || !path.startsWith("/api/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException, IOException {
        CurrentUser user=auth.current(AuthController.token(request));
        if(user==null) { response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"success\":false,\"data\":null,\"message\":\"请先登录\"}"); return; }
        try { CurrentUser.set(user); chain.doFilter(request,response); } finally { CurrentUser.clear(); }
    }
}
