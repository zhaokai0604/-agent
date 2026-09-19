package com.award.log.service.impl;

import com.award.log.model.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.award.log.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysUserServiceImplSecurityTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @InjectMocks
    private SysUserServiceImpl sysUserService;

    @BeforeEach
    void setUp() {
        lenient().when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            SysUser u = inv.getArgument(0);
            u.setUserId(99);
            return 1;
        });
    }

    @Test
    void registerUserAlwaysForcesOrdinaryRole() {
        SysUser user = new SysUser();
        user.setUsername("newbie");
        user.setPassword("Secret123!");
        user.setRole(1);

        Integer id = sysUserService.registerUser(user);

        assertEquals(99, id);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getRole());
        verify(sysUserMapper, never()).selectByUsername(any());
    }

    @Test
    void registerUserHandlesDuplicateKeyWithoutPreCheckRace() {
        when(sysUserMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("uk_username"));

        SysUser user = new SysUser();
        user.setUsername("newbie");
        user.setPassword("Secret123!");

        assertNull(sysUserService.registerUser(user));
    }

    @Test
    void updateUserPreservesExistingRole() {
        SysUser existing = new SysUser();
        existing.setUserId(5);
        existing.setUsername("alice");
        existing.setRole(0);
        existing.setPassword("hash");
        when(sysUserMapper.selectAuthById(5)).thenReturn(existing);
        when(sysUserMapper.updateById(any())).thenReturn(1);

        SysUser patch = new SysUser();
        patch.setUserId(5);
        patch.setUsername("alice");
        patch.setRole(1);

        assertTrue(sysUserService.updateUser(patch));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getRole());
    }

    @Test
    void updateOwnProfileRejectsDuplicateEmail() {
        when(sysUserMapper.existsByEmailExcludingUser("dup@example.com", 7)).thenReturn(true);

        assertFalse(sysUserService.updateOwnProfile(7, "dup@example.com", "wx-1"));
        verify(sysUserMapper, never()).updateProfileFields(any(), any(), any());
    }

    @Test
    void userExistsUsesExistsQueryInsteadOfSelectStar() {
        when(sysUserMapper.existsByUsername("alice")).thenReturn(true);

        assertTrue(sysUserService.userExists("alice"));
        verify(sysUserMapper).existsByUsername("alice");
        verify(sysUserMapper, never()).selectByUsername(any());
    }

    @Test
    void matchesPasswordReadsHashOnly() {
        when(sysUserMapper.selectPasswordHashById(3))
                .thenReturn("$2a$10$abcdefghijklmnopqrstuv");

        assertFalse(sysUserService.matchesPassword(3, "wrong"));
        verify(sysUserMapper).selectPasswordHashById(3);
        verify(sysUserMapper, never()).selectById(any());
    }

    @Test
    void updateUserRoleUsesDedicatedUpdate() {
        when(sysUserMapper.existsById(8)).thenReturn(true);
        when(sysUserMapper.updateRoleById(8, 1)).thenReturn(1);

        assertTrue(sysUserService.updateUserRole(8, 1));
        verify(sysUserMapper).updateRoleById(8, 1);
        verify(sysUserMapper, never()).selectById(any());
    }

    @Test
    void readAndPagingMethodsDelegateWithNormalizedInputs() {
        SysUser user = new SysUser();
        when(sysUserMapper.selectById(3)).thenReturn(user);
        when(sysUserMapper.selectByUsername("alice")).thenReturn(user);
        when(sysUserMapper.selectByEmail("a@example.com")).thenReturn(user);
        when(sysUserMapper.selectAll()).thenReturn(java.util.List.of(user));
        when(sysUserMapper.countAll()).thenReturn(1L);
        when(sysUserMapper.selectPage(2, 2)).thenReturn(java.util.List.of(user));

        assertSame(user, sysUserService.getUserById(3));
        assertSame(user, sysUserService.getUserByUsername("alice"));
        assertSame(user, sysUserService.getUserByEmail("  a@example.com "));
        assertEquals(1, sysUserService.getAllUsers().size());
        assertEquals(1L, sysUserService.getUsersPage(2, 2).getTotal());
        verify(sysUserMapper).selectPage(2, 2);
        assertNull(sysUserService.getUserByEmail("   "));
    }

    @Test
    void registerRejectsIncompleteInputAndHandlesZeroRows() {
        assertNull(sysUserService.registerUser(null));
        assertNull(sysUserService.registerUser(new SysUser()));
        SysUser blank = new SysUser();
        blank.setUsername(" ");
        blank.setPassword("x");
        assertNull(sysUserService.registerUser(blank));

        reset(sysUserMapper);
        when(sysUserMapper.insert(any(SysUser.class))).thenReturn(0);
        SysUser user = new SysUser();
        user.setUsername(" alice ");
        user.setPassword("secret");
        user.setEmail("   ");
        assertNull(sysUserService.registerUser(user));
        assertEquals("alice", user.getUsername());
        assertNull(user.getEmail());
    }

    @Test
    void updateUserRejectsMissingExistingAndConflicts() {
        assertFalse(sysUserService.updateUser(null));
        SysUser missingId = new SysUser();
        assertFalse(sysUserService.updateUser(missingId));
        SysUser patch = new SysUser();
        patch.setUserId(2);
        when(sysUserMapper.selectAuthById(2)).thenReturn(null);
        assertFalse(sysUserService.updateUser(patch));

        SysUser existing = new SysUser();
        existing.setUserId(2);
        existing.setUsername("alice");
        existing.setEmail("a@example.com");
        existing.setPassword("hash");
        existing.setRole(1);
        when(sysUserMapper.selectAuthById(2)).thenReturn(existing);
        when(sysUserMapper.existsByUsername("bob")).thenReturn(true);
        patch.setUsername("bob");
        assertFalse(sysUserService.updateUser(patch));

        reset(sysUserMapper);
        when(sysUserMapper.selectAuthById(2)).thenReturn(existing);
        when(sysUserMapper.existsByEmailExcludingUser("b@example.com", 2)).thenReturn(true);
        SysUser emailPatch = new SysUser();
        emailPatch.setUserId(2);
        emailPatch.setUsername("alice");
        emailPatch.setEmail("b@example.com");
        assertFalse(sysUserService.updateUser(emailPatch));
    }

    @Test
    void updateDeleteAndRoleMethodsHandleSuccessFailureAndDuplicate() {
        SysUser existing = new SysUser();
        existing.setUserId(4);
        existing.setUsername("alice");
        existing.setPassword("hash");
        existing.setRole(0);
        when(sysUserMapper.selectAuthById(4)).thenReturn(existing);
        when(sysUserMapper.updateById(any(SysUser.class))).thenReturn(0);
        SysUser patch = new SysUser();
        patch.setUserId(4);
        assertFalse(sysUserService.updateUser(patch));
        reset(sysUserMapper);
        when(sysUserMapper.selectAuthById(4)).thenReturn(existing);
        when(sysUserMapper.updateById(any(SysUser.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertFalse(sysUserService.updateUser(patch));

        when(sysUserMapper.deleteById(4)).thenReturn(1, 0);
        assertTrue(sysUserService.deleteUser(4));
        assertFalse(sysUserService.deleteUser(4));

        assertFalse(sysUserService.updateUserRole(null, 1));
        assertFalse(sysUserService.updateUserRole(4, null));
        when(sysUserMapper.existsById(4)).thenReturn(false);
        assertFalse(sysUserService.updateUserRole(4, 1));
        when(sysUserMapper.existsById(4)).thenReturn(true);
        when(sysUserMapper.updateRoleById(4, 1)).thenReturn(0);
        assertFalse(sysUserService.updateUserRole(4, 1));
        when(sysUserMapper.updateRoleById(4, 1)).thenThrow(new IllegalStateException("db"));
        assertFalse(sysUserService.updateUserRole(4, 1));
    }

    @Test
    void loginPasswordAdminAndExistenceQueriesCoverAuthBoundaries() {
        assertNull(sysUserService.login("missing", "pw"));
        SysUser auth = new SysUser();
        auth.setUserId(8);
        auth.setUsername("alice");
        auth.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"));
        when(sysUserMapper.selectAuthByUsername("alice")).thenReturn(auth);
        when(sysUserMapper.selectByUsername("alice")).thenReturn(auth);
        assertSame(auth, sysUserService.login("alice", "secret"));
        assertNull(sysUserService.login("alice", "wrong"));

        when(sysUserMapper.selectAdminUsers()).thenReturn(java.util.List.of());
        assertNull(sysUserService.getAdminUser());
        when(sysUserMapper.selectAdminUsers()).thenReturn(java.util.List.of(auth));
        assertSame(auth, sysUserService.getAdminUser());

        assertFalse(sysUserService.userExists(null));
        assertFalse(sysUserService.userExists(" "));
        when(sysUserMapper.existsByUsername("alice")).thenReturn(true);
        assertTrue(sysUserService.userExists(" alice "));
        assertFalse(sysUserService.userExistsById(null));
        when(sysUserMapper.existsById(8)).thenReturn(true);
        assertTrue(sysUserService.userExistsById(8));
        assertNull(sysUserService.getUserRoleById(null));
        when(sysUserMapper.selectRoleById(8)).thenReturn(0);
        assertEquals(0, sysUserService.getUserRoleById(8));
    }

    @Test
    void passwordProfileAndEmailConflictPathsAreHandled() {
        assertFalse(sysUserService.matchesPassword(null, "x"));
        assertFalse(sysUserService.matchesPassword(1, null));
        when(sysUserMapper.selectPasswordHashById(1)).thenReturn(null);
        assertFalse(sysUserService.matchesPassword(1, "x"));

        SysUser user = new SysUser();
        user.setUserId(1);
        user.setUsername("alice");
        user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("old"));
        when(sysUserMapper.selectAuthByUsername("alice")).thenReturn(user);
        when(sysUserMapper.updatePasswordById(eq(1), anyString())).thenReturn(1);
        assertTrue(sysUserService.resetPassword("alice", "new"));
        when(sysUserMapper.updatePasswordById(eq(1), anyString())).thenReturn(0);
        assertFalse(sysUserService.resetPassword("alice", "new"));
        when(sysUserMapper.selectAuthByUsername("missing")).thenReturn(null);
        assertFalse(sysUserService.resetPassword("missing", "new"));

        when(sysUserMapper.selectPasswordHashById(1)).thenReturn(user.getPassword());
        assertFalse(sysUserService.changeOwnPassword(null, "old", "new"));
        when(sysUserMapper.updatePasswordById(eq(1), anyString())).thenReturn(1);
        assertTrue(sysUserService.changeOwnPassword(1, "old", "new"));
        assertFalse(sysUserService.changeOwnPassword(1, "bad", "new"));

        when(sysUserMapper.existsByEmailExcludingUser("new@example.com", 1)).thenReturn(false);
        when(sysUserMapper.updateProfileFields(1, "new@example.com", null)).thenReturn(1);
        assertTrue(sysUserService.updateOwnProfile(1, " new@example.com ", " "));
        when(sysUserMapper.updateProfileFields(1, null, "wx")).thenThrow(new DuplicateKeyException("duplicate"));
        assertFalse(sysUserService.updateOwnProfile(1, null, " wx "));
    }
}
