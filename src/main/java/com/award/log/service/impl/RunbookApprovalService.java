package com.award.log.service.impl;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RunbookApprovalService {

    private final AtomicLong seq = new AtomicLong(1);
    private final Map<Long, Map<String, Object>> store = new ConcurrentHashMap<>();

    public Map<String, Object> submit(String title, String action, String command, Object requester) {
        long id = seq.getAndIncrement();
        Map<String, Object> item = new ConcurrentHashMap<>();
        item.put("id", id);
        item.put("title", title);
        item.put("action", action);
        item.put("command", command);
        item.put("requester", requester == null ? "unknown" : String.valueOf(requester));
        item.put("status", "PENDING");
        item.put("createdAt", System.currentTimeMillis());
        store.put(id, item);
        return item;
    }

    public Map<String, Object> approve(long id, Object approver) {
        Map<String, Object> item = getById(id);
        item.put("status", "APPROVED");
        item.put("approver", String.valueOf(approver));
        item.put("approvedAt", System.currentTimeMillis());
        return item;
    }

    public Map<String, Object> reject(long id, Object approver, String reason) {
        Map<String, Object> item = getById(id);
        item.put("status", "REJECTED");
        item.put("approver", String.valueOf(approver));
        item.put("reason", reason);
        item.put("approvedAt", System.currentTimeMillis());
        return item;
    }

    public Map<String, Object> execute(long id, Object operator) {
        Map<String, Object> item = getById(id);
        if (!"APPROVED".equals(item.get("status"))) {
            throw new IllegalStateException("仅已审批通过的Runbook允许执行");
        }
        item.put("status", "EXECUTED");
        item.put("operator", String.valueOf(operator));
        item.put("executedAt", System.currentTimeMillis());
        item.put("result", "EXECUTION_ACCEPTED");
        return item;
    }

    public List<Map<String, Object>> list() {
        return new ArrayList<>(store.values());
    }

    private Map<String, Object> getById(long id) {
        Map<String, Object> item = store.get(id);
        if (item == null) throw new IllegalArgumentException("Runbook申请不存在: " + id);
        return item;
    }
}
