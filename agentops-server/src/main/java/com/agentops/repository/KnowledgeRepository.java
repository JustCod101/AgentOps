package com.agentops.repository;

import com.agentops.domain.entity.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeRepository extends JpaRepository<KnowledgeEntry, Long> {

    List<KnowledgeEntry> findByCategoryOrderByPriorityDesc(String category);

    @Query("SELECT k FROM KnowledgeEntry k WHERE " +
           "LOWER(k.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(k.content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY k.priority DESC")
    List<KnowledgeEntry> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT k FROM KnowledgeEntry k WHERE k.category = :category AND (" +
           "LOWER(k.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(k.content) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY k.priority DESC")
    List<KnowledgeEntry> searchByCategoryAndKeyword(@Param("category") String category,
                                                    @Param("keyword") String keyword);

    List<KnowledgeEntry> findAllByOrderByPriorityDesc();
}
