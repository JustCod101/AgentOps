package com.agentops.controller;

import com.agentops.common.Result;
import com.agentops.domain.entity.KnowledgeEntry;
import com.agentops.repository.KnowledgeRepository;
import com.agentops.tool.KnowledgeSearchTool;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 知识库管理 API
 *
 * GET    /api/v1/knowledge                  — 列表 (可选 category 过滤)
 * GET    /api/v1/knowledge/{id}             — 详情
 * GET    /api/v1/knowledge/search           — 搜索 (keyword + category)
 * POST   /api/v1/knowledge                  — 新增
 * PUT    /api/v1/knowledge/{id}             — 更新
 * DELETE /api/v1/knowledge/{id}             — 删除
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeSearchTool knowledgeSearchTool;

    /**
     * 列表查询, 可选按 category 过滤
     */
    @GetMapping
    public Result<List<KnowledgeEntry>> list(
            @RequestParam(required = false) String category) {
        List<KnowledgeEntry> entries = (category != null && !category.isBlank())
                ? knowledgeRepository.findByCategoryOrderByPriorityDesc(category)
                : knowledgeRepository.findAllByOrderByPriorityDesc();
        return Result.ok(entries);
    }

    /**
     * 按 ID 查询详情
     */
    @GetMapping("/{id}")
    public Result<KnowledgeEntry> getById(@PathVariable Long id) {
        return knowledgeRepository.findById(id)
                .map(Result::ok)
                .orElse(Result.fail(404, "知识条目不存在: " + id));
    }

    /**
     * 搜索 — 支持关键词 + 模式匹配 + 分类过滤
     */
    @GetMapping("/search")
    public Result<List<KnowledgeEntry>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "10") int limit) {
        List<KnowledgeEntry> results = knowledgeSearchTool.search(keyword, category, limit);
        return Result.ok(results);
    }

    /**
     * 新增知识条目
     */
    @PostMapping
    public Result<KnowledgeEntry> create(@RequestBody KnowledgeEntryRequest request) {
        KnowledgeEntry entry = KnowledgeEntry.builder()
                .category(request.category())
                .title(request.title())
                .content(request.content())
                .tags(request.tags() != null ? request.tags() : List.of())
                .matchPatterns(request.matchPatterns() != null ? request.matchPatterns() : List.of())
                .priority(request.priority() != null ? request.priority() : 0)
                .createdAt(Instant.now())
                .build();
        return Result.ok(knowledgeRepository.save(entry));
    }

    /**
     * 更新知识条目
     */
    @PutMapping("/{id}")
    public Result<KnowledgeEntry> update(@PathVariable Long id,
                                         @RequestBody KnowledgeEntryRequest request) {
        return knowledgeRepository.findById(id)
                .map(existing -> {
                    if (request.category() != null) existing.setCategory(request.category());
                    if (request.title() != null) existing.setTitle(request.title());
                    if (request.content() != null) existing.setContent(request.content());
                    if (request.tags() != null) existing.setTags(request.tags());
                    if (request.matchPatterns() != null) existing.setMatchPatterns(request.matchPatterns());
                    if (request.priority() != null) existing.setPriority(request.priority());
                    return Result.ok(knowledgeRepository.save(existing));
                })
                .orElse(Result.fail(404, "知识条目不存在: " + id));
    }

    /**
     * 删除知识条目
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (!knowledgeRepository.existsById(id)) {
            return Result.fail(404, "知识条目不存在: " + id);
        }
        knowledgeRepository.deleteById(id);
        return Result.ok(null);
    }

    record KnowledgeEntryRequest(
            String category,
            String title,
            String content,
            List<String> tags,
            List<String> matchPatterns,
            Integer priority
    ) {}
}
