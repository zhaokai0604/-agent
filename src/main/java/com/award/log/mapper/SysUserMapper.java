package com.award.log.mapper;

import com.award.log.model.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统用户Mapper
 */
@Mapper
public interface SysUserMapper {
    /**
     * 根据用户ID查询公开资料（不含 password）
     */
    SysUser selectById(Integer userId);

    /**
     * 根据用户名查询公开资料（不含 password）
     */
    SysUser selectByUsername(String username);

    /**
     * 根据邮箱查询公开资料（不含 password）
     */
    SysUser selectByEmail(@Param("email") String email);

    /**
     * 登录/改密等鉴权场景：按用户名读取 password 哈希
     */
    SysUser selectAuthByUsername(String username);

    /**
     * 管理更新场景：读取含 password 的鉴权字段
     */
    SysUser selectAuthById(Integer userId);

    /**
     * 校验密码时只读哈希，避免 SELECT *
     */
    String selectPasswordHashById(@Param("userId") Integer userId);

    /**
     * API Key 等场景只读 role
     */
    Integer selectRoleById(@Param("userId") Integer userId);

    boolean existsById(@Param("userId") Integer userId);

    boolean existsByUsername(@Param("username") String username);

    boolean existsByEmail(@Param("email") String email);

    boolean existsByEmailExcludingUser(@Param("email") String email, @Param("userId") Integer userId);

    List<SysUser> selectAll();

    List<SysUser> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    long countAll();

    int insert(SysUser user);

    int updateById(SysUser user);

    int updateProfileFields(@Param("userId") Integer userId,
                            @Param("email") String email,
                            @Param("wechatUserid") String wechatUserid);

    int updatePasswordById(@Param("userId") Integer userId,
                           @Param("password") String password);

    int updateRoleById(@Param("userId") Integer userId, @Param("role") Integer role);

    int deleteById(Integer userId);

    List<SysUser> selectAdminUsers();
}
