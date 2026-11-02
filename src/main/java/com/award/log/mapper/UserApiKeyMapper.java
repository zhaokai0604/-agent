package com.award.log.mapper;

import com.award.log.model.UserApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户 API Key Mapper，操作 user_api_key 表（创建、轮换、吊销与最近使用）。
 */
@Mapper
public interface UserApiKeyMapper {

    List<UserApiKey> selectByUserId(@Param("userId") Integer userId);

    List<UserApiKey> selectByPrefix(@Param("keyPrefix") String keyPrefix);

    UserApiKey selectById(@Param("id") Long id);

    int insert(UserApiKey apiKey);

    int updateKeyMaterial(@Param("id") Long id,
                          @Param("keyPrefix") String keyPrefix,
                          @Param("keyHash") String keyHash,
                          @Param("updatedAt") LocalDateTime updatedAt);

    int revoke(@Param("id") Long id,
               @Param("revokedAt") LocalDateTime revokedAt,
               @Param("updatedAt") LocalDateTime updatedAt);

    int touchLastUsed(@Param("id") Long id,
                      @Param("lastUsedAt") LocalDateTime lastUsedAt,
                      @Param("updatedAt") LocalDateTime updatedAt);

    long countActiveByUserId(@Param("userId") Integer userId);
}
