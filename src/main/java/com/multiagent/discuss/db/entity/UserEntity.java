package com.multiagent.discuss.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户账号。
 * <ul>
 *   <li>{@code passwordHash} —— BCrypt 哈希，登录时用 {@code BCryptPasswordEncoder.matches} 比对，<b>不可逆</b></li>
 *   <li>{@code authToken} —— 登录时刷新的 UUID；注销 / 重新登录会重新生成。放在 DB 而不是内存是为了支持「重启不丢失当前会话」</li>
 * </ul>
 */
@Entity
@Table(name = "user")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, length = 32, columnDefinition = "VARCHAR(32)")
    private String loginId;

    @Column(name = "display_name", nullable = false, length = 24)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 72, columnDefinition = "VARCHAR(72)")
    private String passwordHash;

    @Column(name = "auth_token", length = 36, columnDefinition = "CHAR(36)")
    private String authToken;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}