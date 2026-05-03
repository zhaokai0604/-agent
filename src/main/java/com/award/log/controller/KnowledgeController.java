package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Knowledge")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Operation(summary = "上传知识文档")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestBody Map<String, String> body) {
        return Result.success(knowledgeBaseService.upload(body.getOrDefault("title", "untitled"), body.getOrDefault("content", "")));
    }

    @Operation(summary = "语义检索 Top-5")
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String query) {
        return Result.success(knowledgeBaseService.search(query, 5));
    }

    @Operation(summary = "删除知识文档")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable String id) {
        return Result.success(knowledgeBaseService.delete(id));
    }
}
