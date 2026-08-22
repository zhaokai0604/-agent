package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户 API Key（库内仅存前缀与哈希），对应 user_api_key 表。
 */
@Data
public class UserApiKey {
    private Long id;
    private Integer userId;
    private String keyName;
    private String keyPrefix;
    private String keyHash;
    private String scopeBundle;
    private String status;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime revokedAt;
}
