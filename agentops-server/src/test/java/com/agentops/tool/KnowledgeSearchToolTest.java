package com.agentops.tool;

import com.agentops.domain.entity.KnowledgeEntry;
import com.agentops.repository.KnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock
    private KnowledgeRepository knowledgeRepository;

    @InjectMocks
    private KnowledgeSearchTool knowledgeSearchTool;

    private KnowledgeEntry slowQueryEntry;
    private KnowledgeEntry lockEntry;
    private KnowledgeEntry connectionPoolEntry;
    private KnowledgeEntry runbookEntry;

    @BeforeEach
    void setUp() {
        slowQueryEntry = KnowledgeEntry.builder()
                .id(1L)
                .category("SLOW_QUERY_PATTERN")
                .title("全表扫描导致慢查询")
                .content("当查询未命中索引时会触发全表扫描")
                .tags(List.of("慢查询", "全表扫描", "索引"))
                .matchPatterns(List.of("full table scan", "seq scan", "rows_examined"))
                .priority(10)
                .createdAt(Instant.now())
                .build();

        lockEntry = KnowledgeEntry.builder()
                .id(2L)
                .category("SLOW_QUERY_PATTERN")
                .title("锁等待导致查询超时")
                .content("当多个事务竞争同一行/表锁时")
                .tags(List.of("锁等待", "死锁", "事务"))
                .matchPatterns(List.of("lock_time", "waiting", "deadlock", "lock timeout"))
                .priority(9)
                .createdAt(Instant.now())
                .build();

        connectionPoolEntry = KnowledgeEntry.builder()
                .id(3L)
                .category("ERROR_CODE")
                .title("Connection pool exhausted")
                .content("连接池耗尽通常由以下原因引起")
                .tags(List.of("连接池", "连接数"))
                .matchPatterns(List.of("connection pool", "too many connections", "connection exhausted"))
                .priority(10)
                .createdAt(Instant.now())
                .build();

        runbookEntry = KnowledgeEntry.builder()
                .id(4L)
                .category("RUNBOOK")
                .title("数据库响应变慢标准排障流程")
                .content("1. 检查慢查询日志")
                .tags(List.of("排障", "慢查询", "runbook"))
                .matchPatterns(List.of("响应慢", "延迟高", "slow", "latency"))
                .priority(10)
                .createdAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("search() — 综合搜索")
    class SearchTests {

        @Test
        @DisplayName("matchPatterns 命中时优先返回")
        void shouldMatchByPatterns() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, lockEntry, connectionPoolEntry, runbookEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search(
                    "发现 full table scan 导致查询慢", null, 5);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("tags 匹配也能命中")
        void shouldMatchByTags() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, lockEntry, connectionPoolEntry, runbookEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search("死锁", null, 5);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("connection pool 命中 ERROR_CODE 分类")
        void shouldMatchConnectionPool() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, lockEntry, connectionPoolEntry, runbookEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search(
                    "connection pool exhausted", null, 3);

            assertThat(results).isNotEmpty();
            assertThat(results.stream().anyMatch(e -> e.getId().equals(3L))).isTrue();
        }

        @Test
        @DisplayName("按 category 过滤搜索范围")
        void shouldFilterByCategory() {
            when(knowledgeRepository.findByCategoryOrderByPriorityDesc("SLOW_QUERY_PATTERN"))
                    .thenReturn(List.of(slowQueryEntry, lockEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search(
                    "full table scan", "SLOW_QUERY_PATTERN", 5);

            assertThat(results).isNotEmpty();
            assertThat(results).allMatch(e -> e.getCategory().equals("SLOW_QUERY_PATTERN"));
        }

        @Test
        @DisplayName("空查询文本返回全部(按 priority 排序)")
        void shouldReturnAllWhenQueryBlank() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, connectionPoolEntry, runbookEntry, lockEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search("", null, 10);

            assertThat(results).hasSize(4);
        }

        @Test
        @DisplayName("空查询 + category 返回该分类全部")
        void shouldReturnCategoryEntriesWhenQueryBlank() {
            when(knowledgeRepository.findByCategoryOrderByPriorityDesc("RUNBOOK"))
                    .thenReturn(List.of(runbookEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search("", "RUNBOOK", 10);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getCategory()).isEqualTo("RUNBOOK");
        }

        @Test
        @DisplayName("limit 限制返回条数")
        void shouldRespectLimit() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, lockEntry, connectionPoolEntry, runbookEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search("slow latency 慢查询", null, 2);

            assertThat(results.size()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("模式匹配不足时用关键词搜索补充")
        void shouldSupplementWithKeywordSearch() {
            // 模式匹配只命中 1 条，但 limit=3
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry));
            when(knowledgeRepository.searchByKeyword("索引优化"))
                    .thenReturn(List.of(slowQueryEntry, lockEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.search("索引优化", null, 3);

            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("无匹配时返回空列表")
        void shouldReturnEmptyWhenNoMatch() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, lockEntry));
            when(knowledgeRepository.searchByKeyword("完全无关的内容xyz"))
                    .thenReturn(List.of());

            List<KnowledgeEntry> results = knowledgeSearchTool.search("完全无关的内容xyz", null, 5);

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("searchByCategory()")
    class SearchByCategoryTests {

        @Test
        @DisplayName("返回指定分类的所有条目")
        void shouldReturnCategoryEntries() {
            when(knowledgeRepository.findByCategoryOrderByPriorityDesc("SLOW_QUERY_PATTERN"))
                    .thenReturn(List.of(slowQueryEntry, lockEntry));

            List<KnowledgeEntry> results = knowledgeSearchTool.searchByCategory("SLOW_QUERY_PATTERN");

            assertThat(results).hasSize(2);
            verify(knowledgeRepository).findByCategoryOrderByPriorityDesc("SLOW_QUERY_PATTERN");
        }
    }

    @Nested
    @DisplayName("searchAndFormat()")
    class SearchAndFormatTests {

        @Test
        @DisplayName("格式化输出包含标题、分类、优先级")
        void shouldFormatResults() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(slowQueryEntry, runbookEntry));

            String formatted = knowledgeSearchTool.searchAndFormat("slow 慢查询", null, 5);

            assertThat(formatted).contains("知识库匹配结果");
            assertThat(formatted).contains("全表扫描导致慢查询");
            assertThat(formatted).contains("SLOW_QUERY_PATTERN");
            assertThat(formatted).contains("优先级: 10");
        }

        @Test
        @DisplayName("无结果时返回提示文本")
        void shouldReturnNotFoundMessage() {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of());

            String formatted = knowledgeSearchTool.searchAndFormat("xyz", null, 5);

            assertThat(formatted).contains("未找到相关知识库条目");
        }
    }
}
