package com.award.log.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommonValueCoverageTest {

    @Test
    void resultFactoriesAndGeneratedAccessorsPreserveEnvelopeSemantics() {
        assertEquals(200, Result.success().getCode());
        assertEquals(200, Result.success("ok").getCode());
        assertEquals("ok", Result.success("ok").getData());
        assertEquals("custom", Result.success("ok", "custom").getMessage());
        assertEquals(500, Result.error("bad").getCode());
        assertEquals(403, Result.error(403, "denied").getCode());

        Result<String> result = Result.success("data");
        result.setCode(201);
        result.setMessage("created");
        result.setData("updated");
        assertEquals(201, result.getCode());
        assertEquals("created", result.getMessage());
        assertEquals("updated", result.getData());
        Result<String> expected = Result.success("updated", "created");
        expected.setCode(201);
        assertEquals(result, expected);
        assertNotNull(result.toString());
    }

    @Test
    void pageResultConstructorsAndAccessorsRoundTrip() {
        PageResult<String> empty = new PageResult<>();
        assertNull(empty.getList());
        PageResult<String> page = new PageResult<>(List.of("a"), 3);
        assertEquals(List.of("a"), page.getList());
        assertEquals(3, page.getTotal());
        page.setList(List.of("b"));
        page.setTotal(4);
        assertEquals("b", page.getList().get(0));
        assertEquals(4, page.getTotal());
        assertEquals(page, new PageResult<>(List.of("b"), 4));
    }
}
