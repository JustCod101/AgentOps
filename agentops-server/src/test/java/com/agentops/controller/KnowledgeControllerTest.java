package com.agentops.controller;

import com.agentops.domain.entity.KnowledgeEntry;
import com.agentops.repository.KnowledgeRepository;
import com.agentops.tool.KnowledgeSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KnowledgeController.class)
class KnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KnowledgeRepository knowledgeRepository;

    @MockBean
    private KnowledgeSearchTool knowledgeSearchTool;

    private KnowledgeEntry sampleEntry;

    @BeforeEach
    void setUp() {
        sampleEntry = KnowledgeEntry.builder()
                .id(1L)
                .category("SLOW_QUERY_PATTERN")
                .title("全表扫描导致慢查询")
                .content("当查询未命中索引时会触发全表扫描")
                .tags(List.of("慢查询", "索引"))
                .matchPatterns(List.of("full table scan", "seq scan"))
                .priority(10)
                .createdAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/knowledge — 列表")
    class ListTests {

        @Test
        @DisplayName("返回全部列表")
        void shouldReturnAllEntries() throws Exception {
            when(knowledgeRepository.findAllByOrderByPriorityDesc())
                    .thenReturn(List.of(sampleEntry));

            mockMvc.perform(get("/api/v1/knowledge"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].title").value("全表扫描导致慢查询"));
        }

        @Test
        @DisplayName("按 category 过滤")
        void shouldFilterByCategory() throws Exception {
            when(knowledgeRepository.findByCategoryOrderByPriorityDesc("SLOW_QUERY_PATTERN"))
                    .thenReturn(List.of(sampleEntry));

            mockMvc.perform(get("/api/v1/knowledge")
                            .param("category", "SLOW_QUERY_PATTERN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].category").value("SLOW_QUERY_PATTERN"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/knowledge/{id} — 详情")
    class GetByIdTests {

        @Test
        @DisplayName("存在时返回详情")
        void shouldReturnEntry() throws Exception {
            when(knowledgeRepository.findById(1L)).thenReturn(Optional.of(sampleEntry));

            mockMvc.perform(get("/api/v1/knowledge/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("全表扫描导致慢查询"));
        }

        @Test
        @DisplayName("不存在时返回 404")
        void shouldReturn404WhenNotFound() throws Exception {
            when(knowledgeRepository.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/knowledge/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("知识条目不存在: 99"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/knowledge/search — 搜索")
    class SearchTests {

        @Test
        @DisplayName("关键词搜索返回结果")
        void shouldSearchByKeyword() throws Exception {
            when(knowledgeSearchTool.search("慢查询", null, 10))
                    .thenReturn(List.of(sampleEntry));

            mockMvc.perform(get("/api/v1/knowledge/search")
                            .param("keyword", "慢查询"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].title").value("全表扫描导致慢查询"));
        }

        @Test
        @DisplayName("支持 category + keyword 组合搜索")
        void shouldSearchWithCategoryFilter() throws Exception {
            when(knowledgeSearchTool.search("索引", "SLOW_QUERY_PATTERN", 5))
                    .thenReturn(List.of(sampleEntry));

            mockMvc.perform(get("/api/v1/knowledge/search")
                            .param("keyword", "索引")
                            .param("category", "SLOW_QUERY_PATTERN")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/knowledge — 新增")
    class CreateTests {

        @Test
        @DisplayName("创建知识条目")
        void shouldCreateEntry() throws Exception {
            when(knowledgeRepository.save(any(KnowledgeEntry.class)))
                    .thenReturn(sampleEntry);

            String body = """
                    {
                        "category": "SLOW_QUERY_PATTERN",
                        "title": "全表扫描导致慢查询",
                        "content": "当查询未命中索引时会触发全表扫描",
                        "tags": ["慢查询", "索引"],
                        "matchPatterns": ["full table scan"],
                        "priority": 10
                    }
                    """;

            mockMvc.perform(post("/api/v1/knowledge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("全表扫描导致慢查询"));

            verify(knowledgeRepository).save(any(KnowledgeEntry.class));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/knowledge/{id} — 更新")
    class UpdateTests {

        @Test
        @DisplayName("更新已有条目")
        void shouldUpdateEntry() throws Exception {
            when(knowledgeRepository.findById(1L)).thenReturn(Optional.of(sampleEntry));
            when(knowledgeRepository.save(any(KnowledgeEntry.class))).thenReturn(sampleEntry);

            String body = """
                    {
                        "title": "更新后的标题",
                        "priority": 20
                    }
                    """;

            mockMvc.perform(put("/api/v1/knowledge/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(knowledgeRepository).save(any(KnowledgeEntry.class));
        }

        @Test
        @DisplayName("更新不存在的条目返回 404")
        void shouldReturn404WhenUpdatingNonExistent() throws Exception {
            when(knowledgeRepository.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/v1/knowledge/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"test\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/knowledge/{id} — 删除")
    class DeleteTests {

        @Test
        @DisplayName("删除已有条目")
        void shouldDeleteEntry() throws Exception {
            when(knowledgeRepository.existsById(1L)).thenReturn(true);

            mockMvc.perform(delete("/api/v1/knowledge/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(knowledgeRepository).deleteById(1L);
        }

        @Test
        @DisplayName("删除不存在的条目返回 404")
        void shouldReturn404WhenDeletingNonExistent() throws Exception {
            when(knowledgeRepository.existsById(99L)).thenReturn(false);

            mockMvc.perform(delete("/api/v1/knowledge/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }
}
