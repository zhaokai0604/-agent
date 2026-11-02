package com.award.log.agent.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TeamRoleModelTest {
    @Test void eachRoleIsSeparateBoundedJsonCallWithModelToolExecutionDisabled(){
        var chat=mock(ChatModel.class);var policy=new TeamDataPolicy(new ObjectMapper());
        when(chat.stream(any(Prompt.class))).thenAnswer(call->{
            Prompt prompt=call.getArgument(0);var options=(ToolCallingChatOptions)prompt.getOptions();
            assertFalse(options.getInternalToolExecutionEnabled());assertTrue(options.getToolCallbacks().isEmpty());assertTrue(options.getToolNames().isEmpty());
            assertFalse(prompt.getContents().contains("private-fixture-secret"));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"summary\":\"fixture\"}")))));
        });
        var model=new TeamRoleModel(chat,policy);
        for(String role:List.of("DIAGNOSIS","PLANNER","VERIFIER"))assertTrue(model.call(role,Map.of("password","private-fixture-secret"),Instant.now().plusSeconds(5)).json().contains("fixture"));
        verify(chat,times(3)).stream(any(Prompt.class));
    }
    @Test void partialModelFailureAndInvalidJsonAreNotSuccessfulHandoffs(){
        var chat=mock(ChatModel.class);var model=new TeamRoleModel(chat,new TeamDataPolicy(new ObjectMapper()));
        when(chat.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("fixture provider failed")));
        assertThrows(IllegalStateException.class,()->model.call("DIAGNOSIS",Map.of(),Instant.now().plusSeconds(1)));
        when(chat.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("not json"))))));
        assertThrows(IllegalArgumentException.class,()->model.call("PLANNER",Map.of(),Instant.now().plusSeconds(1)));
    }
    @Test void strictRoleJsonAndSensitiveParametersFailClosed(){
        var data=new TeamDataPolicy(new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->data.decode("{\"summary\":\"x\",\"evidenceIds\":[]}",TeamProtocol.Diagnosis.class));
        assertThrows(IllegalArgumentException.class,()->data.tree("{\"x\":1,\"x\":2}"));
        assertThrows(IllegalArgumentException.class,()->data.executable(Map.of("command","rm fixture")));
        assertThrows(IllegalArgumentException.class,()->data.executable(Map.of("api_key","fixture")));
        assertFalse(data.redact("Authorization: Bearer private-fixture-secret").contains("private-fixture-secret"));
        assertTrue(data.clip("文".repeat(5000),1000).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1000);
    }
    @Test void nestedToolPayloadStringsDoNotLeakCredentialsIntoEventsOrPrompts(){
        var data=new TeamDataPolicy(new ObjectMapper());
        var evidence=Map.of("data","{\"token\":\"fixture-access-value\",\"password\":\"two word fixture\",\"client_secret\":\"fixture-client-value\"}\nCookie: session=fixture-cookie-value; theme=dark\nnormal observation retained");
        String sanitized=data.safe(evidence);
        assertFalse(sanitized.contains("fixture-access-value"));assertFalse(sanitized.contains("two word fixture"));
        assertFalse(sanitized.contains("fixture-client-value"));assertFalse(sanitized.contains("fixture-cookie-value"));
        assertTrue(sanitized.contains("normal observation retained"));assertDoesNotThrow(()->data.tree(sanitized));
        assertThrows(IllegalArgumentException.class,()->data.executable(Map.of("header","token=fixture-access-value")));
    }
}
