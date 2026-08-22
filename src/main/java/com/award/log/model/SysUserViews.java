package com.award.log.model;

import com.award.log.common.PageResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link SysUser} 与 API 视图之间的转换，避免控制器直接返回持久化实体。
 */
public final class SysUserViews {

    private SysUserViews() {
    }

    public static SysUserView toView(SysUser user) {
        if (user == null) {
            return null;
        }
        SysUserView view = new SysUserView();
        view.setUserId(user.getUserId());
        view.setUsername(user.getUsername());
        view.setRole(user.getRole());
        view.setWechatUserid(user.getWechatUserid());
        view.setEmail(user.getEmail());
        view.setCreateTime(user.getCreateTime());
        view.setUpdateTime(user.getUpdateTime());
        return view;
    }

    public static List<SysUserView> toViews(List<SysUser> users) {
        if (users == null) {
            return List.of();
        }
        return users.stream().map(SysUserViews::toView).collect(Collectors.toList());
    }

    public static PageResult<SysUserView> toViewPage(PageResult<SysUser> page) {
        if (page == null) {
            return new PageResult<>(List.of(), 0);
        }
        return new PageResult<>(toViews(page.getList()), page.getTotal());
    }
}
