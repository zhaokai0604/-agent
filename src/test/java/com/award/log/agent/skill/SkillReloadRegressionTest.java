package com.award.log.agent.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class SkillReloadRegressionTest {
    @Test void failedReloadKeepsPreviousCompleteCatalog() throws Exception {
        var loader=new SkillCatalogLoader();
        var original=loader.all();
        assertFalse(original.isEmpty());
        String originalId=original.get(0).id();
        var duplicate=new ByteArrayResource("id: duplicate\ntools: [DiskTool]\n".getBytes(StandardCharsets.UTF_8));
        Resource failedIo=mock(Resource.class);
        when(failedIo.getInputStream()).thenThrow(new java.io.IOException("resource unavailable"));
        List<Resource[]> failures=List.of(
                new Resource[]{new ByteArrayResource("id: broken\ntools: []\n".getBytes(StandardCharsets.UTF_8))},
                new Resource[]{duplicate,duplicate},
                new Resource[]{new ByteArrayResource("[]".getBytes(StandardCharsets.UTF_8))},
                new Resource[]{new ByteArrayResource("tools: [DiskTool]".getBytes(StandardCharsets.UTF_8))},
                new Resource[]{failedIo});
        for (Resource[] input : failures) {
            try (var mocked=mockConstruction(PathMatchingResourcePatternResolver.class,
                    (mock,context)->when(mock.getResources(anyString())).thenReturn(input))) {
                assertThrows(IllegalStateException.class,loader::reload);
                assertEquals(original,loader.all());
                assertNotNull(loader.find(originalId));
            }
        }
        assertNull(loader.find("missing-skill"));
    }
}
