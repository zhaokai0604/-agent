package com.award.log.agent.team;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded JSON and basic credential redaction at the team persistence/model boundary. */
@Component
public class TeamDataPolicy {
    private static final Pattern SECRET=Pattern.compile("(?i)(password|passwd|secret|token|api[-_]?key|authorization|private[-_]?key|cookie)");
    private static final String SECRET_NAME="(?:password|passwd|secret|token|api[-_]?key|access[-_]?token|authorization|private[-_]?key)";
    private static final Pattern QUOTED_ASSIGNMENT=Pattern.compile("(?i)("+SECRET_NAME+")([\\\"']?\\s*[:=]\\s*)([\\\"'])([^\\\"'\\r\\n]*)(\\3)");
    private static final Pattern ASSIGNMENT=Pattern.compile("(?i)("+SECRET_NAME+")([\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,;\\\"'}]+)");
    private static final Pattern COOKIE=Pattern.compile("(?i)((?:set-)?cookie\\s*[:=]\\s*)[^\\r\\n]+");
    private final ObjectMapper json;
    public TeamDataPolicy(ObjectMapper mapper) {
        json=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public String redact(String text) {
        if(text==null)return "";
        String filtered=text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{16,}","[REDACTED]")
                .replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----","[REDACTED PRIVATE KEY]");
        filtered=COOKIE.matcher(filtered).replaceAll("$1[REDACTED]");
        filtered=QUOTED_ASSIGNMENT.matcher(filtered).replaceAll("$1$2$3[REDACTED]$5");
        return ASSIGNMENT.matcher(filtered).replaceAll("$1$2[REDACTED]");
    }
    public String encode(Object value) {
        try { String text=json.writeValueAsString(value);bounded(text,60000);return text; }
        catch(java.io.IOException e){throw new IllegalArgumentException("Invalid team JSON",e);}
    }
    public <T>T decode(String value,Class<T> type) {
        bounded(value,60000);
        try { return json.readValue(value,type); }
        catch(java.io.IOException e){throw new IllegalArgumentException("Invalid role contract",e);}
    }
    public JsonNode tree(String value) { return decode(value,JsonNode.class); }
    public String safe(Object value) { return encode(clean(json.valueToTree(value))); }
    private JsonNode clean(JsonNode node) {
        if(node.isTextual())return json.getNodeFactory().textNode(redact(node.asText()));
        if(node.isObject()){
            var copy=json.createObjectNode();node.fields().forEachRemaining(e->copy.set(e.getKey(),SECRET.matcher(e.getKey()).find()
                    ?json.getNodeFactory().textNode("[REDACTED]"):clean(e.getValue())));return copy;
        }
        if(node.isArray()){var copy=json.createArrayNode();node.forEach(n->copy.add(clean(n)));return copy;}
        return node;
    }
    public String clip(String text,int bytes){
        boolean shortened=false;
        while(text.getBytes(StandardCharsets.UTF_8).length>bytes-20){text=text.substring(0,text.length()/2);shortened=true;}
        return shortened?text+" [TRUNCATED]":text;
    }
    public void bounded(String text,int bytes) {
        if(text==null||text.length()>bytes||text.getBytes(StandardCharsets.UTF_8).length>bytes)
            throw new IllegalArgumentException("Team payload exceeds limit");
    }
    public void executable(Object value) {
        JsonNode node=json.valueToTree(value);
        check(node,0);
        String text=encode(value);
        if(!redact(text).equals(text))throw new IllegalArgumentException("Credentials are not valid tool parameters");
    }
    private void check(JsonNode node,int depth) {
        if(depth>8)throw new IllegalArgumentException("Nested team parameters exceed limit");
        if(node.isObject())node.fields().forEachRemaining(entry->{
            if(SECRET.matcher(entry.getKey()).find()||Set.of("command","script","shell").contains(entry.getKey().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Unsupported sensitive parameter");
            check(entry.getValue(),depth+1);
        });
        else if(node.isArray()) {if(node.size()>32)throw new IllegalArgumentException("Too many parameter values");node.forEach(v->check(v,depth+1));}
        else if(node.isTextual()) {
            bounded(node.asText(),2048);
            if(!redact(node.asText()).equals(node.asText()))throw new IllegalArgumentException("Credentials are not valid in executable content");
        }
    }
}
