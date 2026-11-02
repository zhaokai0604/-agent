package com.award.log.mapper;

import com.award.log.model.UserProfilePreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户通知偏好 Mapper，操作 user_profile_preference 表。
 */
@Mapper
public interface UserProfilePreferenceMapper {

    UserProfilePreference selectByUserId(@Param("userId") Integer userId);

    int upsert(UserProfilePreference preference);
}
