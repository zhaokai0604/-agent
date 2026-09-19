package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.model.SysUser;
import com.award.log.security.AuthInterceptor;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.SysUserService;
import com.award.log.service.UserApiKeyService;
import com.award.log.service.impl.AiAuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysUserControllerCoverageTest {

    @Mock private SysUserService userService;
    @Mock private RequestUserResolver userResolver;
    @Mock private UserApiKeyService apiKeyService;
    @Mock private AiAuditLogService auditLogService;
    @Mock private HttpSession session;
    @Mock private HttpServletRequest request;

    private SysUserController controller;
    private SysUser user;

    @BeforeEach
    void setUp() {
        controller = new SysUserController();
        ReflectionTestUtils.setField(controller, "sysUserService", userService);
        ReflectionTestUtils.setField(controller, "requestUserResolver", userResolver);
        ReflectionTestUtils.setField(controller, "userApiKeyService", apiKeyService);
        ReflectionTestUtils.setField(controller, "aiAuditLogService", auditLogService);
        user = new SysUser();
        user.setUserId(7);
        user.setUsername("alice");
        user.setRole(0);
        user.setEmail("alice@example.com");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(request.getRequestURI()).thenReturn("/admin/user");
    }

    @Test
    void loginKeyLoginAndLogoutCoverSessionAndAuditBranches() {
        SysUser login = new SysUser();
        login.setUsername("alice");
        login.setPassword("pw");
        when(userService.login("alice", "pw")).thenReturn(user);
        Result<?> loginResult = controller.login(login, session, request);
        assertEquals(200, loginResult.getCode());
        verify(session).setAttribute(AuthInterceptor.SESSION_USER_ID, 7);
        verify(session).setAttribute(AuthInterceptor.SESSION_USER_ROLE, 0);
        verify(auditLogService).save(eq(7), eq(0), any(), eq("LOGIN"), any(), eq(200), eq(0L), eq(0));

        when(userService.login("alice", "bad")).thenReturn(null);
        login.setPassword("bad");
        assertEquals(500, controller.login(login, session, request).getCode());

        when(apiKeyService.authenticate("bad-key")).thenReturn(Map.of("success", false, "message", "invalid"));
        assertEquals(401, controller.keyLogin(Map.of("apiKey", "bad-key"), session, request).getCode());
        when(apiKeyService.authenticate("")).thenReturn(Map.of("success", true, "userId", 7));
        when(userService.getUserById(7)).thenReturn(null);
        assertEquals(401, controller.keyLogin(null, session, request).getCode());

        when(apiKeyService.authenticate("good-key")).thenReturn(Map.of("success", true, "userId", 7));
        when(userService.getUserById(7)).thenReturn(user);
        assertEquals(200, controller.keyLogin(Map.of("apiKey", "good-key"), session, request).getCode());
        verify(auditLogService).save(eq(7), eq(0), any(), eq("KEY_LOGIN"), any(), eq(200), eq(0L), eq(0));

        when(session.getAttribute(AuthInterceptor.SESSION_USER_ID)).thenReturn(7);
        when(session.getAttribute(AuthInterceptor.SESSION_USER_ROLE)).thenReturn(0);
        assertTrue(controller.logout(session, request).getData());
        verify(auditLogService).save(eq(7), eq(0), any(), eq("LOGOUT"), any(), eq(200), eq(0L), eq(0));
        verify(session).invalidate();
    }

    @Test
    void registerReadListPageAndDeleteCoverValidation() {
        SysUser registration = new SysUser();
        registration.setUsername("alice");
        registration.setRole(1);
        when(userService.registerUser(registration)).thenReturn(8);
        assertEquals(200, controller.register(registration).getCode());
        assertNull(registration.getRole());
        when(userService.registerUser(any(SysUser.class))).thenReturn(null);
        assertEquals(500, controller.register(new SysUser()).getCode());

        when(userService.getUserById(7)).thenReturn(user);
        assertEquals(500, controller.getUserById("bad").getCode());
        assertEquals(200, controller.getUserById("7").getCode());
        assertEquals(500, controller.getUserById("null").getCode());
        assertEquals(500, controller.getUserById("undefined").getCode());

        when(userService.getAllUsers()).thenReturn(List.of(user));
        assertEquals(200, controller.getAllUsers().getCode());
        PageResult<SysUser> page = new PageResult<>();
        page.setList(List.of(user));
        page.setTotal(1);
        when(userService.getUsersPage(2, 10)).thenReturn(page);
        assertEquals(200, controller.getUsersPage(2, 10).getCode());
        when(userService.userExists("alice")).thenReturn(true);
        assertTrue(controller.checkUserExists(Map.of("username", "alice")).getData());

        when(userService.deleteUser(7)).thenReturn(true);
        assertEquals(500, controller.deleteUser("bad").getCode());
        assertTrue(controller.deleteUser("7").getData());
    }

    @Test
    void updateResetAndRoleEndpointsEnforceAdminAndReturnServiceOutcomes() {
        SysUser empty = new SysUser();
        assertEquals(500, controller.updateUser(empty).getCode());
        when(userService.updateUser(user)).thenReturn(true);
        assertTrue(controller.updateUser(user).getData());
        when(userService.updateUser(user)).thenThrow(new IllegalStateException("db"));
        assertEquals(500, controller.updateUser(user).getCode());

        when(userResolver.isAdmin(request)).thenReturn(false);
        assertEquals(403, controller.resetPassword(Map.of("username", "alice", "newPassword", "new"), request).getCode());
        assertEquals(403, controller.updateUserRole("7", Map.of("role", 1), request).getCode());

        when(userResolver.isAdmin(request)).thenReturn(true);
        assertEquals(500, controller.resetPassword(Map.of("username", " ", "newPassword", "new"), request).getCode());
        when(userService.resetPassword("alice", "new")).thenReturn(true, false);
        assertEquals(200, controller.resetPassword(Map.of("username", "alice", "newPassword", "new"), request).getCode());
        assertEquals(500, controller.resetPassword(Map.of("username", "alice", "newPassword", "new"), request).getCode());

        assertEquals(500, controller.updateUserRole("bad", Map.of("role", 1), request).getCode());
        assertEquals(500, controller.updateUserRole("7", Map.of(), request).getCode());
        when(userService.updateUserRole(7, 1)).thenReturn(true, false);
        assertEquals(200, controller.updateUserRole("7", Map.of("role", 1), request).getCode());
        assertEquals(500, controller.updateUserRole("7", Map.of("role", 1), request).getCode());
        when(userService.updateUserRole(7, 2)).thenThrow(new IllegalStateException("db"));
        assertEquals(500, controller.updateUserRole("7", Map.of("role", 2), request).getCode());
    }
}
