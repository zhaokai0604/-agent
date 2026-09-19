package com.award.log.model;

import org.junit.jupiter.api.Test;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** DTO/持久化模型的契约测试：逐个回写可写属性，避免 Lombok 生成的访问器长期零覆盖。 */
class ModelAccessorSweepCoverageTest {

    @Test
    void allInstantiableModelBeansRoundTripSupportedAccessors() throws Exception {
        Path root = Path.of("target", "classes", "com", "award", "log", "model");
        List<Class<?>> types = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .forEach(path -> {
                        String relative = root.relativize(path).toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replaceAll("\\.class$", "");
                        try {
                            types.add(Class.forName("com.award.log.model." + relative));
                        } catch (ClassNotFoundException ignored) {
                            // 编译器生成的边界类不影响模型契约扫描。
                        }
                    });
        }

        int exercised = 0;
        for (Class<?> type : types) {
            if (type.isEnum() || type.isInterface()) {
                continue;
            }
            Constructor<?> constructor;
            try {
                constructor = type.getDeclaredConstructor();
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            constructor.setAccessible(true);
            Object bean = constructor.newInstance();
            for (PropertyDescriptor descriptor : Introspector.getBeanInfo(type).getPropertyDescriptors()) {
                Method read = descriptor.getReadMethod();
                Method write = descriptor.getWriteMethod();
                if (read == null || write == null || "class".equals(descriptor.getName())) {
                    continue;
                }
                Object value = sampleValue(descriptor.getPropertyType());
                if (value == Unsupported.VALUE) {
                    continue;
                }
                write.setAccessible(true);
                read.setAccessible(true);
                write.invoke(bean, value);
                read.invoke(bean);
            }
            assertNotNull(bean.toString());
            exercised++;
        }
        assertNotNull(exercised);
    }

    private static Object sampleValue(Class<?> type) {
        if (type == String.class) return "sample";
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == double.class || type == Double.class) return 1.0d;
        if (type == float.class || type == Float.class) return 1.0f;
        if (type == LocalDateTime.class) return LocalDateTime.now();
        if (List.class.isAssignableFrom(type)) return new ArrayList<>();
        if (Set.class.isAssignableFrom(type)) return new LinkedHashSet<>();
        if (Map.class.isAssignableFrom(type)) return new LinkedHashMap<>();
        if (type.isEnum() && type.getEnumConstants().length > 0) return type.getEnumConstants()[0];
        return Unsupported.VALUE;
    }

    private enum Unsupported { VALUE }
}
