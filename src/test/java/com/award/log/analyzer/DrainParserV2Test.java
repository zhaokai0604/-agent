package com.award.log.analyzer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DrainParserV2Test {

    @Test
    void shouldReturnStableTemplateId() {
        DrainParserV2 parser = new DrainParserV2();
        String id1 = parser.parse("ERROR user 123 timeout in mysql");
        String id2 = parser.parse("ERROR user 456 timeout in mysql");
        Assertions.assertNotNull(id1);
        Assertions.assertEquals(id1, id2);
    }
}
