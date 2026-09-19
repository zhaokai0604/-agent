package com.award.log.decision;

import com.award.log.collector.model.RawLogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomForestDecisionEngineCoverageTest {

    @Test
    void heuristicEvaluationHealthReloadAndPathResolutionCoverFallbacks() throws Exception {
        RandomForestDecisionEngine engine = new RandomForestDecisionEngine();
        ReflectionTestUtils.setField(engine, "modelPath", "missing/random-forest.onnx");
        ReflectionTestUtils.setField(engine, "alertThreshold", 0.75D);
        ReflectionTestUtils.setField(engine, "highConfidence", 0.8D);
        ReflectionTestUtils.setField(engine, "featureVersion", "test-feature");
        ReflectionTestUtils.setField(engine, "modelVersion", "test-model");

        DecisionInput fatal = input("FATAL", "exception timeout ERROR at Service", 0.5D);
        DecisionResult high = engine.evaluate(fatal);
        assertTrue(high.isShouldAlert());
        assertEquals(EngineType.RANDOM_FOREST, high.getEngineType());
        assertNotNull(engine.healthSnapshot().get("modelStatus"));
        assertFalse((Boolean) engine.manualReload());

        DecisionInput info = input("INFO", "ordinary message", 0D);
        DecisionResult low = engine.evaluate(info);
        assertFalse(low.isShouldAlert());
        assertTrue(engine.healthSnapshot().containsKey("lastInferenceLatencyMs"));

        ReflectionTestUtils.setField(engine, "modelPath", "classpath:missing-model.onnx");
        assertTrue(ReflectionTestUtils.invokeMethod(engine, "resolveModelPath") == null);
        ReflectionTestUtils.setField(engine, "modelPath", " ");
        assertTrue(ReflectionTestUtils.invokeMethod(engine, "resolveModelPath") == null);

        Path model = Files.createTempFile("rf-model", ".onnx");
        ReflectionTestUtils.setField(engine, "modelPath", model.toString());
        assertNotNull(ReflectionTestUtils.invokeMethod(engine, "resolveModelPath"));
        engine.init();
        engine.destroy();
        Files.deleteIfExists(model);
    }

    @Test
    void scoreExtractionClampAndFeatureTrackingCoverValueShapes() {
        RandomForestDecisionEngine engine = new RandomForestDecisionEngine();
        assertEquals(0.5D, ReflectionTestUtils.invokeMethod(engine, "tryExtractScore",
                (Object) new float[][]{{0.5F}}));
        assertEquals(0.7D, (double) ReflectionTestUtils.invokeMethod(engine, "tryExtractScore",
                (Object) new float[][]{{0.1F, 0.7F}}), 1e-5);
        assertEquals(0.9D, (double) ReflectionTestUtils.invokeMethod(engine, "tryExtractScore",
                (Object) new float[]{0.1F, 0.9F}), 1e-5);
        assertEquals(1.0D, ReflectionTestUtils.invokeMethod(engine, "tryExtractScore",
                (Object) new long[]{2L}));
        assertEquals(0.0D, ReflectionTestUtils.invokeMethod(engine, "tryExtractScore",
                (Object) new long[]{0L}));
        assertEquals(3.0D, ReflectionTestUtils.invokeMethod(engine, "tryExtractScore", 3));
        assertTrue(ReflectionTestUtils.invokeMethod(engine, "tryExtractScore", "no-score") == null);
        assertEquals(0.0D, ReflectionTestUtils.invokeMethod(engine, "clamp", Double.NaN));
        assertEquals(0.0D, ReflectionTestUtils.invokeMethod(engine, "clamp", Double.NEGATIVE_INFINITY));
        assertEquals(1.0D, ReflectionTestUtils.invokeMethod(engine, "clamp", 2.0D));
        assertEquals(0.5D, ReflectionTestUtils.invokeMethod(engine, "clamp", 0.5D));

        float[] features = new float[RfFeatureVectorExt.FEATURE_SIZE];
        features[0] = 1F;
        ReflectionTestUtils.invokeMethod(engine, "trackFeatureImportance", (Object) features);
        assertNotNull(engine.healthSnapshot());
    }

    private static DecisionInput input(String level, String content, double errorRate) {
        RawLogEvent event = new RawLogEvent();
        event.setLevel(level);
        event.setContent(content);
        event.setEventTime(100L);
        event.setIngestTime(500L);
        return DecisionInput.builder()
                .event(event)
                .template("exception timeout")
                .errorRate1m(errorRate)
                .error1m(10)
                .total1m(100)
                .build();
    }
}
