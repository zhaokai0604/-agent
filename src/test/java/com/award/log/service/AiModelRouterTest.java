package com.award.log.service;

import com.award.log.config.AiModelRoutingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.mockito.Mockito.mock;

class AiModelRouterTest {

    @Test
    void defaultModeKeepsExistingSpringAiModel() {
        ChatModel existing = mock(ChatModel.class);
        AiModelRoutingProperties properties = new AiModelRoutingProperties();
        properties.setDefaultModel("deepseek-chat");

        AiModelRouter router = new AiModelRouter(existing, properties);
        AiModelRouter.ResolvedModel resolved = router.resolve("检查磁盘", 100, true);

        assertSame(existing, resolved.chatModel());
        assertEquals("default", resolved.profile());
        assertEquals("deepseek-chat", resolved.model());
        assertEquals("DEFAULT", resolved.routingMode());
        Map<String, Object> snapshot = router.snapshot();
        assertEquals("DEFAULT", snapshot.get("routingMode"));
    }

    @Test
    void autoModeChoosesConfiguredProfileAndExposesSafeSnapshot() {
        ChatModel existing = mock(ChatModel.class);
        AiModelRoutingProperties properties = new AiModelRoutingProperties();
        properties.setRoutingMode("AUTO");
        properties.setDefaultContextWindow(4096);
        AiModelRoutingProperties.Profile first = new AiModelRoutingProperties.Profile();
        first.setEnabled(true);
        first.setBaseUrl("https://provider.example/v1");
        first.setApiKey("secret");
        first.setModel("model-a");
        first.setContextWindow(4096);
        first.setPriority(1);
        properties.getProfiles().put("primary", first);
        properties.setAutoProfiles(java.util.List.of("primary"));

        AiModelRouter.ResolvedModel resolved = new AiModelRouter(existing, properties).resolve("日志", 100, true);
        // 非 OpenAI 默认 ChatModel 无法克隆 profile，应安全回退而不暴露密钥。
        assertSame(existing, resolved.chatModel());
        assertEquals("default", resolved.profile());
        assertEquals("AUTO", resolved.routingMode());
        Map<String, Object> snapshot = new AiModelRouter(existing, properties).snapshot();
        assertNotNull(snapshot.get("profiles"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((java.util.List<Map<String, Object>>) snapshot.get("profiles")).get(0);
        assertEquals("primary", item.get("name"));
        assertEquals(true, item.get("configured"));
        assertEquals(4096, item.get("contextWindow"));
    }

    @Test
    void requestedUnknownOrOversizedPromptFallsBackToDefault() {
        ChatModel existing = mock(ChatModel.class);
        AiModelRoutingProperties properties = new AiModelRoutingProperties();
        AiModelRoutingProperties.Profile profile = new AiModelRoutingProperties.Profile();
        profile.setEnabled(true);
        profile.setBaseUrl("https://provider.example/v1");
        profile.setApiKey("secret");
        profile.setModel("model-a");
        profile.setContextWindow(600);
        properties.getProfiles().put("small", profile);
        AiModelRouter router = new AiModelRouter(existing, properties);

        assertEquals("REQUESTED", router.resolve("x", 1, false, "missing").routingMode());
        assertEquals("default", router.resolve("x", 100, true, "small").profile());
        assertEquals("default", router.resolve("x", 1000, false, "small").profile());
    }
}
