package com.award.log.service.impl;

import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AlarmConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class AlarmConfigServiceImpl implements AlarmConfigService {
    private static final String CONFIG_FILE = System.getProperty("user.dir").replace('\\', '/') + "/alarm-config.json";
    private static final String DEFAULT_KEY = "default";
    private static final String TASKS_KEY = "tasks";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public TaskAlarmConfig getDefaultConfig() {
        synchronized (this) {
            Map<String, Object> root = loadRoot();
            Map<String, Object> defaultMap = asMap(root.get(DEFAULT_KEY));
            return mergeWithDefault(null, defaultMap);
        }
    }

    @Override
    public TaskAlarmConfig getTaskConfig(String taskId) {
        synchronized (this) {
            Map<String, Object> root = loadRoot();
            Map<String, Object> tasks = asMap(root.get(TASKS_KEY));
            Map<String, Object> taskMap = asMap(tasks.get(taskId));
            return mapToConfig(taskId, taskMap);
        }
    }

    @Override
    public TaskAlarmConfig getEffectiveConfig(String taskId) {
        synchronized (this) {
            Map<String, Object> root = loadRoot();
            Map<String, Object> defaultMap = asMap(root.get(DEFAULT_KEY));
            Map<String, Object> tasks = asMap(root.get(TASKS_KEY));
            Map<String, Object> taskMap = asMap(tasks.get(taskId));
            return mergeWithDefault(taskId, mergeMaps(defaultMap, taskMap));
        }
    }

    @Override
    public TaskAlarmConfig saveTaskConfig(String taskId, Map<String, Object> rawConfig) {
        synchronized (this) {
            Map<String, Object> root = loadRoot();
            Map<String, Object> tasks = asMap(root.get(TASKS_KEY));
            if (tasks.isEmpty()) {
                tasks = new LinkedHashMap<>();
            }
            Map<String, Object> normalized = normalizeRaw(rawConfig);
            tasks.put(taskId, normalized);
            root.put(TASKS_KEY, tasks);
            saveRoot(root);
            return getEffectiveConfig(taskId);
        }
    }

    private Map<String, Object> loadRoot() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put(DEFAULT_KEY, buildDefaultMap());
            root.put(TASKS_KEY, new LinkedHashMap<>());
            saveRoot(root);
            return root;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            if (!root.containsKey(DEFAULT_KEY)) {
                root.put(DEFAULT_KEY, buildDefaultMap());
            }
            if (!root.containsKey(TASKS_KEY)) {
                root.put(TASKS_KEY, new LinkedHashMap<>());
            }
            return root;
        } catch (Exception e) {
            log.error("读取告警配置失败，使用默认配置", e);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put(DEFAULT_KEY, buildDefaultMap());
            root.put(TASKS_KEY, new LinkedHashMap<>());
            return root;
        }
    }

    private void saveRoot(Map<String, Object> root) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), root);
        } catch (Exception e) {
            log.error("保存告警配置失败", e);
        }
    }

    private Map<String, Object> buildDefaultMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pushChannels", Arrays.asList("WECHAT", "EMAIL"));
        m.put("alarmLevel", "ERROR");
        m.put("errorThreshold", 5);
        m.put("wechatUserId", "");
        m.put("emailAddress", "");
        m.put("enabled", true);
        m.put("cooldownMs", 300000L);
        return m;
    }

    private Map<String, Object> normalizeRaw(Map<String, Object> raw) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("pushChannels", normalizeChannels(raw.get("pushChannels")));
        n.put("alarmLevel", normalizeLevel(asString(raw.get("alarmLevel"), "ERROR")));
        n.put("errorThreshold", asInt(raw.get("errorThreshold"), 5));
        n.put("wechatUserId", asString(raw.get("wechatUserId"), ""));
        n.put("emailAddress", asString(raw.get("emailAddress"), asString(raw.get("email"), "")));
        n.put("enabled", asBoolean(raw.get("enabled"), true));
        n.put("cooldownMs", asLong(raw.get("cooldownMs"), 300000L));
        n.put("updatedAt", LocalDateTime.now().toString());
        return n;
    }

    private TaskAlarmConfig mergeWithDefault(String taskId, Map<String, Object> source) {
        Map<String, Object> merged = mergeMaps(buildDefaultMap(), source);
        TaskAlarmConfig config = mapToConfig(taskId, merged);
        if (config.getUpdatedAt() == null) {
            config.setUpdatedAt(LocalDateTime.now());
        }
        return config;
    }

    private Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (base != null) {
            result.putAll(base);
        }
        if (override != null) {
            result.putAll(override);
        }
        return result;
    }

    private TaskAlarmConfig mapToConfig(String taskId, Map<String, Object> map) {
        TaskAlarmConfig config = new TaskAlarmConfig();
        config.setTaskId(taskId);
        config.setPushChannels(normalizeChannels(map.get("pushChannels")));
        config.setAlarmLevel(normalizeLevel(asString(map.get("alarmLevel"), "ERROR")));
        config.setErrorThreshold(asInt(map.get("errorThreshold"), 5));
        config.setWechatUserId(asString(map.get("wechatUserId"), ""));
        config.setEmailAddress(asString(map.get("emailAddress"), ""));
        config.setEnabled(asBoolean(map.get("enabled"), true));
        config.setCooldownMs(asLong(map.get("cooldownMs"), 300000L));
        String updatedAt = asString(map.get("updatedAt"), null);
        if (updatedAt != null && !updatedAt.isEmpty()) {
            try {
                config.setUpdatedAt(LocalDateTime.parse(updatedAt));
            } catch (Exception ignored) {
            }
        }
        return config;
    }

    private List<String> normalizeChannels(Object channelsObj) {
        List<String> channels = new ArrayList<>();
        if (channelsObj instanceof List<?> list) {
            for (Object o : list) {
                String ch = asString(o, "").trim();
                if (ch.isEmpty()) {
                    continue;
                }
                if ("wechat".equalsIgnoreCase(ch)) {
                    channels.add("WECHAT");
                } else if ("email".equalsIgnoreCase(ch)) {
                    channels.add("EMAIL");
                } else {
                    channels.add(ch.toUpperCase(Locale.ROOT));
                }
            }
        }
        if (channels.isEmpty()) {
            channels.add("EMAIL");
        }
        return channels;
    }

    private String normalizeLevel(String level) {
        String l = level == null ? "ERROR" : level.trim().toUpperCase(Locale.ROOT);
        if (l.endsWith("_LEVEL")) {
            l = l.substring(0, l.length() - 6);
        }
        if ("WARN".equals(l)) {
            return "WARNING";
        }
        return l;
    }

    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            Map<String, Object> casted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                casted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return casted;
        }
        return new LinkedHashMap<>();
    }

    private String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Integer asInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Long asLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Boolean asBoolean(Object value, Boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
