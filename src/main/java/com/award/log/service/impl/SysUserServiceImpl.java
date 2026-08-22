package com.award.log.service.impl;

import com.award.log.common.PageResult;
import com.award.log.mapper.SysUserMapper;
import com.award.log.model.SysUser;
import com.award.log.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统用户Service实现
 */
@Slf4j
@Service
public class SysUserServiceImpl implements SysUserService {

    @Autowired
    private SysUserMapper sysUserMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public SysUser getUserById(Integer userId) {
        return sysUserMapper.selectById(userId);
    }

    @Override
    public SysUser getUserByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    @Override
    public SysUser getUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return sysUserMapper.selectByEmail(email.trim());
    }

    @Override
    public List<SysUser> getAllUsers() {
        return sysUserMapper.selectAll();
    }

    @Override
    public PageResult<SysUser> getUsersPage(int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        long total = sysUserMapper.countAll();
        List<SysUser> users = sysUserMapper.selectPage(offset, pageSize);
        return new PageResult<>(users, total);
    }

    @Override
    public Integer registerUser(SysUser user) {
        if (user == null
                || user.getUsername() == null
                || user.getUsername().isBlank()
                || user.getPassword() == null
                || user.getPassword().isBlank()) {
            return null;
        }

        user.setUsername(user.getUsername().trim());
        if (user.getEmail() != null) {
            String email = user.getEmail().trim();
            user.setEmail(email.isEmpty() ? null : email);
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole(0);

        try {
            int result = sysUserMapper.insert(user);
            if (result > 0) {
                log.info("用户注册成功: {}", user.getUsername());
                return user.getUserId();
            }
            log.error("用户注册失败: {}", user.getUsername());
            return null;
        } catch (DuplicateKeyException ex) {
            log.warn("用户注册冲突（用户名或邮箱已存在）: {}", user.getUsername());
            return null;
        }
    }

    @Override
    public boolean updateUser(SysUser user) {
        if (user == null || user.getUserId() == null) {
            return false;
        }
        SysUser existing = sysUserMapper.selectAuthById(user.getUserId());
        if (existing == null) {
            return false;
        }

        if (user.getUsername() == null || user.getUsername().isBlank()) {
            user.setUsername(existing.getUsername());
        } else {
            user.setUsername(user.getUsername().trim());
        }

        if (user.getEmail() != null) {
            String email = user.getEmail().trim();
            user.setEmail(email.isEmpty() ? null : email);
        }

        if (!user.getUsername().equals(existing.getUsername())
                && sysUserMapper.existsByUsername(user.getUsername())) {
            log.warn("更新用户失败，用户名已存在: {}", user.getUsername());
            return false;
        }
        if (user.getEmail() != null
                && !user.getEmail().equals(existing.getEmail())
                && sysUserMapper.existsByEmailExcludingUser(user.getEmail(), user.getUserId())) {
            log.warn("更新用户失败，邮箱已被占用: {}", user.getEmail());
            return false;
        }

        user.setRole(existing.getRole());

        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(existing.getPassword());
        }

        try {
            int result = sysUserMapper.updateById(user);
            boolean success = result > 0;
            if (success) {
                log.info("用户信息更新成功: {}", user.getUsername());
            } else {
                log.error("用户信息更新失败: {}", user.getUsername());
            }
            return success;
        } catch (DuplicateKeyException ex) {
            log.warn("更新用户冲突（用户名或邮箱已存在）: userId={}", user.getUserId());
            return false;
        }
    }

    @Override
    public boolean deleteUser(Integer userId) {
        int result = sysUserMapper.deleteById(userId);
        boolean success = result > 0;
        if (success) {
            log.info("用户删除成功: {}", userId);
        } else {
            log.error("用户删除失败: {}", userId);
        }
        return success;
    }

    @Override
    public SysUser login(String username, String password) {
        SysUser user = sysUserMapper.selectAuthByUsername(username);
        if (user == null) {
            log.warn("用户名不存在: {}", username);
            return null;
        }

        if (passwordEncoder.matches(password, user.getPassword())) {
            log.info("用户登录成功: {}", username);
            return sysUserMapper.selectByUsername(username);
        }
        log.warn("密码错误: {}", username);
        return null;
    }

    @Override
    public SysUser getAdminUser() {
        List<SysUser> admins = sysUserMapper.selectAdminUsers();
        if (admins == null || admins.isEmpty()) {
            log.warn("未找到管理员用户");
            return null;
        }
        SysUser admin = admins.get(0);
        log.info("找到管理员用户: {}", admin.getUsername());
        return admin;
    }

    @Override
    public boolean resetPassword(String username, String newPassword) {
        SysUser user = sysUserMapper.selectAuthByUsername(username);
        if (user == null) {
            log.warn("用户不存在: {}", username);
            return false;
        }

        String encoded = passwordEncoder.encode(newPassword);
        int result = sysUserMapper.updatePasswordById(user.getUserId(), encoded);
        boolean success = result > 0;
        if (success) {
            log.info("用户密码重置成功: {}", username);
        } else {
            log.error("用户密码重置失败: {}", username);
        }
        return success;
    }

    @Override
    public boolean matchesPassword(Integer userId, String rawPassword) {
        if (userId == null || rawPassword == null) {
            return false;
        }
        String hash = sysUserMapper.selectPasswordHashById(userId);
        return hash != null && passwordEncoder.matches(rawPassword, hash);
    }

    @Override
    public boolean changeOwnPassword(Integer userId, String oldPassword, String newPassword) {
        if (userId == null) {
            return false;
        }
        if (!matchesPassword(userId, oldPassword)) {
            return false;
        }
        String encoded = passwordEncoder.encode(newPassword);
        return sysUserMapper.updatePasswordById(userId, encoded) > 0;
    }

    @Override
    public boolean updateOwnProfile(Integer userId, String email, String wechatUserid) {
        if (userId == null) {
            return false;
        }
        String normalizedEmail = email == null ? null : email.trim();
        if (normalizedEmail != null && normalizedEmail.isEmpty()) {
            normalizedEmail = null;
        }
        String normalizedWechat = wechatUserid == null ? null : wechatUserid.trim();
        if (normalizedWechat != null && normalizedWechat.isEmpty()) {
            normalizedWechat = null;
        }

        if (normalizedEmail != null
                && sysUserMapper.existsByEmailExcludingUser(normalizedEmail, userId)) {
            log.warn("更新个人资料失败，邮箱已被占用: userId={}, email={}", userId, normalizedEmail);
            return false;
        }

        try {
            return sysUserMapper.updateProfileFields(userId, normalizedEmail, normalizedWechat) > 0;
        } catch (DuplicateKeyException ex) {
            log.warn("更新个人资料邮箱冲突: userId={}", userId);
            return false;
        }
    }

    @Override
    public boolean userExists(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return sysUserMapper.existsByUsername(username.trim());
    }

    @Override
    public boolean userExistsById(Integer userId) {
        if (userId == null) {
            return false;
        }
        return sysUserMapper.existsById(userId);
    }

    @Override
    public Integer getUserRoleById(Integer userId) {
        if (userId == null) {
            return null;
        }
        return sysUserMapper.selectRoleById(userId);
    }

    @Override
    public boolean updateUserRole(Integer userId, Integer role) {
        if (userId == null || role == null) {
            return false;
        }
        if (!sysUserMapper.existsById(userId)) {
            log.warn("用户不存在: {}", userId);
            return false;
        }
        try {
            int result = sysUserMapper.updateRoleById(userId, role);
            boolean success = result > 0;
            if (success) {
                log.info("用户角色更新成功: 用户ID={}, 角色={}", userId, role);
            } else {
                log.error("用户角色更新失败: 用户ID={}, 角色={}", userId, role);
            }
            return success;
        } catch (Exception e) {
            log.error("用户角色更新异常: 用户ID={}, 角色={}", userId, role, e);
            return false;
        }
    }
}
