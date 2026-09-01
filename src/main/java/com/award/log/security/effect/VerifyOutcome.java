package com.award.log.security.effect;

/**
 * 写操作业务验证结果（产品强约束，不是普通错误文案）。
 */
public enum VerifyOutcome {
    /** 证据与预期均满足 */
    VERIFIED,
    /** 证据不足或仅弱信号，不得记为处置成功 */
    VERIFY_WEAK,
    /** 业务验证未通过 */
    VERIFY_FAILED,
    /** 真实写缺少可机读证据 */
    EVIDENCE_INCOMPLETE,
    /** 只读 / 预览 / 未请求真实写 */
    VERIFY_SKIPPED;

    /** 可作为「处置闭环成功」的结果（弱通过不算）。 */
    public boolean isRemediationSuccess() {
        return this == VERIFIED || this == VERIFY_SKIPPED;
    }

    /** @deprecated 使用 {@link #isRemediationSuccess()}；弱通过不再算成功。 */
    public boolean isSuccess() {
        return isRemediationSuccess();
    }
}
