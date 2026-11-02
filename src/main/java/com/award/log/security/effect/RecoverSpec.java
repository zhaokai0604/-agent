package com.award.log.security.effect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复句柄：可逆操作给出恢复入口；不可逆须在执行前标注。
 */
public record RecoverSpec(
        boolean reversible,
        String recoverTool,
        String recoverHint,
        List<String> recycleHandles,
        String irreversibilityNote
) {
    public RecoverSpec {
        recoverTool = recoverTool == null ? "" : recoverTool.trim();
        recoverHint = recoverHint == null ? "" : recoverHint.trim();
        recycleHandles = recycleHandles == null ? List.of() : List.copyOf(recycleHandles);
        irreversibilityNote = irreversibilityNote == null ? "" : irreversibilityNote.trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reversible", reversible);
        m.put("recoverTool", recoverTool);
        m.put("recoverHint", recoverHint);
        m.put("recycleHandles", recycleHandles);
        m.put("irreversibilityNote", irreversibilityNote);
        return m;
    }

    public static RecoverSpec recycleBin() {
        return new RecoverSpec(
                true,
                "RecycleBinTool",
                "safe_delete 条目可通过 RecycleBinTool list/restore 恢复（TTL 内）",
                List.of(),
                "");
    }

    public static RecoverSpec irreversible(String note) {
        return new RecoverSpec(false, "", "", List.of(), note);
    }

    public static RecoverSpec none() {
        return new RecoverSpec(false, "", "无写操作，无需恢复", List.of(), "");
    }

    public RecoverSpec withHandles(List<String> handles) {
        return new RecoverSpec(reversible, recoverTool, recoverHint, handles, irreversibilityNote);
    }
}
