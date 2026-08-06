package com.internalops.auth;

import com.internalops.api.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/login") public ApiResponse<CurrentUser> login(@RequestBody Map<String,String> body,HttpServletResponse response) {
        AuthService.Session session=auth.login(body.getOrDefault("username",""),body.getOrDefault("password", ""));
        Cookie cookie=new Cookie("OPS_SESSION",session.token()); cookie.setHttpOnly(true); cookie.setPath("/"); cookie.setMaxAge(8*60*60); response.addCookie(cookie); return ApiResponse.ok(session.user());
    }
    @PostMapping("/logout") public ApiResponse<Void> logout(HttpServletRequest request,HttpServletResponse response) { auth.logout(token(request)); Cookie cookie=new Cookie("OPS_SESSION",""); cookie.setPath("/"); cookie.setMaxAge(0); response.addCookie(cookie); return ApiResponse.ok(null); }
    @GetMapping("/me") public ApiResponse<CurrentUser> me(HttpServletRequest request) { CurrentUser user=auth.current(token(request)); if(user==null) throw new IllegalArgumentException("登录已失效"); return ApiResponse.ok(user); }
    static String token(HttpServletRequest request) { if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if("OPS_SESSION".equals(c.getName())) return c.getValue(); return null; }
}
