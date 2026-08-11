package com.internalops.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    public AuthService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Session login(String username, String password) {
        List<User> users = jdbc.query("SELECT id,username,password_hash,display_name,role FROM sys_user WHERE username=? AND enabled=TRUE",
                (rs, i) -> new User(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4), UserRole.valueOf(rs.getString(5))), username.trim());
        if (users.isEmpty() || !samePassword(password, users.get(0).hash())) throw new IllegalArgumentException("用户名或密码错误");
        User user=users.get(0); byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO user_session(user_id,token_hash,expires_at) VALUES(?,?,?)",user.id(),hash(token), Timestamp.from(Instant.now().plus(8, ChronoUnit.HOURS)));
        return new Session(token, new CurrentUser(user.id(),user.username(),user.displayName(),user.role()));
    }
    public CurrentUser current(String token) {
        if (token==null || token.isBlank()) return null;
        List<CurrentUser> users=jdbc.query("SELECT u.id,u.username,u.display_name,u.role FROM user_session s JOIN sys_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE",(rs,i)->new CurrentUser(rs.getLong(1),rs.getString(2),rs.getString(3), UserRole.valueOf(rs.getString(4))),hash(token));
        return users.isEmpty()?null:users.get(0);
    }
    public void logout(String token) { if(token!=null&&!token.isBlank()) jdbc.update("UPDATE user_session SET revoked_at=CURRENT_TIMESTAMP WHERE token_hash=?",hash(token)); }
    private boolean samePassword(String supplied, String stored) {
        return supplied != null && stored != null && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
    private String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private record User(long id,String username,String hash,String displayName,UserRole role) {}
    public record Session(String token, CurrentUser user) {}
}
