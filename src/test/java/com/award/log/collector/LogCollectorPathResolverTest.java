package com.award.log.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class LogCollectorPathResolverTest {
    @TempDir Path directory;
    @Test void equivalentRootPathsDoNotStartTwoOwnersOrDuplicateCollection() throws Exception {
        Set<String> roots = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(LogCollectorPathResolver.class, "addIfExists", roots, directory.toString());
        ReflectionTestUtils.invokeMethod(LogCollectorPathResolver.class, "addIfExists", roots, directory.resolve(".").toString());
        assertEquals(Set.of(directory.toRealPath().toString()), roots);
        assertTrue(LogCollectorPathResolver.resolve(directory.toString()).contains(directory.toString()));
    }
}
