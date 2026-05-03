package com.award.log.service.impl;

import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.model.RuleDefinition;
import com.award.log.service.EngineHealthService;
import com.award.log.service.ExperimentService;
import com.award.log.service.RuleRegistryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class EngineHealthServiceImpl implements EngineHealthService {

    private final RandomForestDecisionEngine randomForestDecisionEngine;
    private final RuleRegistryService ruleRegistryService;
    private final ExperimentService experimentService;
    private volatile Map<String, Object> latest = new HashMap<>();

    public EngineHealthServiceImpl(RandomForestDecisionEngine randomForestDecisionEngine,
                                   RuleRegistryService ruleRegistryService,
                                   ExperimentService experimentService) {
        this.randomForestDecisionEngine = randomForestDecisionEngine;
        this.ruleRegistryService = ruleRegistryService;
        this.experimentService = experimentService;
    }

    @Scheduled(fixedDelay = 30000)
    public void collect() {
        long enabledRules = ruleRegistryService.list().stream().filter(RuleDefinition::isEnabled).count();
        Map<String, Object> m = new HashMap<>();
        m.put("ruleEngine", Map.of("ruleCount", ruleRegistryService.list().size(), "enabledRules", enabledRules));
        m.put("randomForest", randomForestDecisionEngine.healthSnapshot());
        m.put("llm", Map.of("availability", 1.0, "tokenUsage", 0));
        m.put("drain", Map.of("templateCount", 0, "parseSuccessRate", 1.0));
        m.put("abExperiment", experimentService.report());
        latest = m;
    }

    @Override
    public Map<String, Object> snapshot() {
        return latest;
    }
}
