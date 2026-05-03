package com.award.log.controller;

import com.award.log.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 与前端 {@code /api/alarm-rule/**} 对齐的兼容接口。
 * 规则存内存，进程重启后清空；生产若需持久化可改为落库。
 */
@Tag(name = "Alarm Rule (compat)")
@RestController
@RequestMapping("/api/alarm-rule")
public class AlarmRuleCompatController {

    private final Map<Long, Map<String, Object>> store = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    private static boolean ruleEnabled(Map<String, Object> m) {
        Object en = m.get("enabled");
        if (en == null) {
            return true;
        }
        if (en instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(en));
    }

    @Operation(summary = "新增告警规则（内存）")
    @PostMapping("/add")
    public Result<Map<String, Object>> add(@RequestBody Map<String, Object> rule) {
        long id = idSeq.getAndIncrement();
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(rule);
        row.putIfAbsent("enabled", true);
        row.put("id", id);
        store.put(id, row);
        return Result.success(row);
    }

    @Operation(summary = "更新告警规则（内存）")
    @PostMapping("/update")
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> rule) {
        Object idObj = rule.get("id");
        if (idObj == null) {
            return Result.error("id 不能为空");
        }
        long id = Long.parseLong(String.valueOf(idObj));
        if (!store.containsKey(id)) {
            return Result.error("规则不存在");
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(rule);
        row.put("id", id);
        store.put(id, row);
        return Result.success(row);
    }

    @Operation(summary = "删除规则")
    @DeleteMapping("/delete/{id}")
    public Result<Boolean> delete(@PathVariable long id) {
        return Result.success(store.remove(id) != null);
    }

    @Operation(summary = "按 id 查询")
    @GetMapping("/get/{id}")
    public Result<Map<String, Object>> get(@PathVariable long id) {
        Map<String, Object> row = store.get(id);
        if (row == null) {
            return Result.error("规则不存在");
        }
        return Result.success(row);
    }

    @Operation(summary = "全部规则")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.success(new ArrayList<>(store.values()));
    }

    @Operation(summary = "已启用规则")
    @GetMapping("/enabled")
    public Result<List<Map<String, Object>>> enabled() {
        List<Map<String, Object>> list = store.values().stream()
                .filter(AlarmRuleCompatController::ruleEnabled)
                .collect(Collectors.toList());
        return Result.success(list);
    }

    @Operation(summary = "启用/禁用")
    @PostMapping("/enable")
    public Result<Map<String, Object>> enable(@RequestParam long id, @RequestParam boolean enabled) {
        Map<String, Object> row = store.get(id);
        if (row == null) {
            return Result.error("规则不存在");
        }
        row.put("enabled", enabled);
        return Result.success(row);
    }

    @Operation(summary = "规则试匹配（pattern 字段为简单子串）")
    @PostMapping("/test")
    public Result<Map<String, Object>> test(
            @RequestParam long ruleId,
            @RequestParam(required = false, defaultValue = "") String testContent) {
        Map<String, Object> row = store.get(ruleId);
        if (row == null) {
            return Result.error("规则不存在");
        }
        String pattern = Objects.toString(row.getOrDefault("pattern", ""), "");
        boolean matched = !pattern.isEmpty() && testContent.contains(pattern);
        return Result.success(Map.of(
                "ruleId", ruleId,
                "matched", matched,
                "sampleLength", testContent.length()
        ));
    }
}
