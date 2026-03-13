package com.agentops.tool;

import com.agentops.domain.entity.KnowledgeEntry;
import com.agentops.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库搜索工具 — 供 Agent 在诊断过程中查询运维知识
 *
 * 搜索策略 (按优先级):
 *   1. matchPatterns 模式匹配 — 精确匹配已知故障模式
 *   2. 关键词搜索 — title + content 模糊匹配
 *   3. 分类过滤 — 按 category 缩小范围
 *
 * 结果按 matchScore + priority 综合排序
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final KnowledgeRepository knowledgeRepository;

    /**
     * 综合搜索: 先用 matchPatterns 做模式匹配, 再用关键词补充
     *
     * @param queryText  用户原始问题或 Agent 提取的关键信息
     * @param category   可选分类过滤 (null = 搜索全部)
     * @param limit      最大返回条数
     * @return 排序后的知识条目
     */
    public List<KnowledgeEntry> search(String queryText, String category, int limit) {
        if (queryText == null || queryText.isBlank()) {
            return category != null
                    ? knowledgeRepository.findByCategoryOrderByPriorityDesc(category)
                            .stream().limit(limit).collect(Collectors.toList())
                    : knowledgeRepository.findAllByOrderByPriorityDesc()
                            .stream().limit(limit).collect(Collectors.toList());
        }

        String lowerQuery = queryText.toLowerCase();

        // 1. 加载候选集
        List<KnowledgeEntry> candidates = (category != null)
                ? knowledgeRepository.findByCategoryOrderByPriorityDesc(category)
                : knowledgeRepository.findAllByOrderByPriorityDesc();

        // 2. 计算每条知识的匹配分数
        List<ScoredEntry> scored = candidates.stream()
                .map(entry -> new ScoredEntry(entry, computeScore(entry, lowerQuery)))
                .filter(se -> se.score > 0)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .toList();

        // 3. 如果模式匹配结果不足, 用关键词搜索补充
        if (scored.size() < limit) {
            Set<Long> existingIds = scored.stream()
                    .map(se -> se.entry.getId())
                    .collect(Collectors.toSet());

            List<KnowledgeEntry> keywordResults = (category != null)
                    ? knowledgeRepository.searchByCategoryAndKeyword(category, queryText)
                    : knowledgeRepository.searchByKeyword(queryText);

            List<KnowledgeEntry> supplementary = keywordResults.stream()
                    .filter(e -> !existingIds.contains(e.getId()))
                    .limit(limit - scored.size())
                    .toList();

            List<KnowledgeEntry> result = new ArrayList<>(scored.stream()
                    .map(ScoredEntry::entry).toList());
            result.addAll(supplementary);
            return result;
        }

        return scored.stream().map(ScoredEntry::entry).collect(Collectors.toList());
    }

    /**
     * 按分类搜索
     */
    public List<KnowledgeEntry> searchByCategory(String category) {
        return knowledgeRepository.findByCategoryOrderByPriorityDesc(category);
    }

    /**
     * 格式化搜索结果为 Agent 可消费的文本
     */
    public String searchAndFormat(String queryText, String category, int limit) {
        List<KnowledgeEntry> results = search(queryText, category, limit);
        if (results.isEmpty()) {
            return "未找到相关知识库条目。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 知识库匹配结果 (").append(results.size()).append(" 条) ===\n\n");

        for (int i = 0; i < results.size(); i++) {
            KnowledgeEntry entry = results.get(i);
            sb.append("--- [").append(i + 1).append("] ")
              .append(entry.getCategory()).append(" / ").append(entry.getTitle())
              .append(" (优先级: ").append(entry.getPriority()).append(") ---\n");
            sb.append(entry.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 计算匹配分数:
     *   matchPatterns 命中 = 10分/个
     *   tags 命中 = 5分/个
     *   priority 直接加分
     */
    private double computeScore(KnowledgeEntry entry, String lowerQuery) {
        double score = 0;

        // matchPatterns 模式匹配
        if (entry.getMatchPatterns() != null) {
            for (String pattern : entry.getMatchPatterns()) {
                if (lowerQuery.contains(pattern.toLowerCase())) {
                    score += 10;
                }
            }
        }

        // tags 匹配
        if (entry.getTags() != null) {
            for (String tag : entry.getTags()) {
                if (lowerQuery.contains(tag.toLowerCase())) {
                    score += 5;
                }
            }
        }

        // title 包含关键词
        if (entry.getTitle() != null && entry.getTitle().toLowerCase().contains(lowerQuery)) {
            score += 3;
        }

        // priority 加权 (归一化到 0~2 分)
        if (score > 0) {
            score += entry.getPriority() * 0.2;
        }

        return score;
    }

    private record ScoredEntry(KnowledgeEntry entry, double score) {}
}
