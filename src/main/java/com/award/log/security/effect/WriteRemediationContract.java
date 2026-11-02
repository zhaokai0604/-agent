package com.award.log.security.effect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写操作受控处置契约：预期变化 + 恢复 + 最小审批。
 */
public record WriteRemediationContract(
        ExpectedEffect expectedEffect,
        RecoverSpec recover,
        RemediationApproval approval,
        String evidenceContractId,
        int irreversibility
) {
    public WriteRemediationContract {
        if (expectedEffect == null) {
            expectedEffect = ExpectedEffect.none();
        }
        if (recover == null) {
            recover = RecoverSpec.none();
        }
        if (approval == null) {
            approval = new RemediationApproval("", "", "", false, "");
        }
        evidenceContractId = evidenceContractId == null ? "" : evidenceContractId;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expectedEffect", expectedEffect.toMap());
        m.put("recover", recover.toMap());
        m.put("approval", approval.toMap());
        m.put("evidenceContractId", evidenceContractId);
        m.put("irreversibility", irreversibility);
        m.put("reversible", recover.reversible());
        return m;
    }
}
