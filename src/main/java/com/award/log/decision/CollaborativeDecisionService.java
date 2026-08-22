package com.award.log.decision;

import org.springframework.stereotype.Service;

/**
 * 协同决策门面：将输入交给 {@link IntelligentRouter} 路由，并写入决策轨迹。
 * 自身不做规则/模型推理，只负责编排与记 trace。
 */
@Service
public class CollaborativeDecisionService {

    private final IntelligentRouter intelligentRouter;
    private final DecisionTraceService decisionTraceService;

    public CollaborativeDecisionService(IntelligentRouter intelligentRouter,
                                        DecisionTraceService decisionTraceService) {
        this.intelligentRouter = intelligentRouter;
        this.decisionTraceService = decisionTraceService;
    }

    /** 路由一次决策并记录耗时与输入输出轨迹。 */
    public DecisionResult decide(DecisionInput input) {
        long start = System.currentTimeMillis();
        DecisionResult result = intelligentRouter.decide(input);
        decisionTraceService.record(input, result, System.currentTimeMillis() - start);
        return result;
    }
}
