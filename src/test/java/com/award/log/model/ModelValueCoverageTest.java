package com.award.log.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the value-bearing paths of Lombok data models, including non-null equals/hashCode branches. */
class ModelValueCoverageTest {

    @Test
    void populatedModelsExerciseGeneratedValueSemantics() {
        for (Class<?> type : List.of(
                AlarmRuleEntity.class, DecisionFeedback.class, DecisionLog.class, EngineOfflineMetric.class,
                LogAlarm.class, LogAnalysisDetail.class, LogAnalysisTask.class, LogDocument.class,
                LogTemplateRecord.class, ModelEvaluation.class, PerformanceData.class, RuleDefinition.class,
                RuleHitStat.class, SysPermission.class, SysRole.class, SysRolePermission.class,
                SysUser.class, SysUserRole.class, SysUserView.class, TaskAlarmConfig.class,
                UserApiKey.class, UserProfilePreference.class)) {
            exerciseValuePaths(type);
        }
    }

    private static void exerciseValuePaths(Class<?> type) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object first = ctor.newInstance();
            Object second = ctor.newInstance();
            Method changedSetter = null;
            Object changedValue = null;
            for (Method setter : type.getMethods()) {
                if (setter.getParameterCount() != 1 || !setter.getName().startsWith("set")
                        || Modifier.isStatic(setter.getModifiers())) {
                    continue;
                }
                Object value = sample(setter.getParameterTypes()[0]);
                if (value == null) {
                    continue;
                }
                setter.invoke(first, value);
                setter.invoke(second, value);
                Object alternateValue = alternate(setter.getParameterTypes()[0], value);
                if (changedSetter == null && !Objects.equals(alternateValue, value)) {
                    changedSetter = setter;
                    changedValue = alternateValue;
                }
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (method.getParameterCount() == 0 && method.getName().startsWith("get")) {
                    method.invoke(first);
                }
            }
            assertNotNull(first.toString());
            first.hashCode();
            second.hashCode();
            assertTrue(first.equals(second));
            assertFalse(first.equals(new Object()));
            if (changedSetter != null && changedValue != null) {
                changedSetter.invoke(second, changedValue);
                assertFalse(first.equals(second));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to exercise model " + type.getName(), e);
        }
    }

    private static Object alternate(Class<?> type, Object value) {
        if (type == String.class) return value + "-changed";
        if (type == Integer.class || type == int.class) return ((Number) value).intValue() + 1;
        if (type == Long.class || type == long.class) return ((Number) value).longValue() + 1L;
        if (type == Double.class || type == double.class) return ((Number) value).doubleValue() + 1D;
        if (type == Float.class || type == float.class) return ((Number) value).floatValue() + 1F;
        if (type == Boolean.class || type == boolean.class) return !((Boolean) value);
        if (type == BigDecimal.class) return BigDecimal.TEN;
        return value;
    }

    private static Object sample(Class<?> type) {
        if (type == String.class) return "sample";
        if (type == Integer.class || type == int.class) return 1;
        if (type == Long.class || type == long.class) return 1L;
        if (type == Double.class || type == double.class) return 1D;
        if (type == Float.class || type == float.class) return 1F;
        if (type == Short.class || type == short.class) return (short) 1;
        if (type == Byte.class || type == byte.class) return (byte) 1;
        if (type == Boolean.class || type == boolean.class) return true;
        if (type == LocalDateTime.class) return LocalDateTime.of(2026, 1, 1, 0, 0);
        if (type == LocalDate.class) return LocalDate.of(2026, 1, 1);
        if (type == Date.class) return new Date(0L);
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (List.class.isAssignableFrom(type)) return List.of("sample");
        if (Map.class.isAssignableFrom(type)) return Map.of("key", "value");
        if (type.isEnum() && type.getEnumConstants().length > 0) return type.getEnumConstants()[0];
        return null;
    }
}
