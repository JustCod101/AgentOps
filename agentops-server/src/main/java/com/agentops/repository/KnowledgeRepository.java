package com.agentops.repository;

import com.agentops.domain.entity.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeRepository extends JpaRepository<KnowledgeEntry, Long> {

    List<KnowledgeEntry> findByCategoryOrderByPriorityDesc(String category);
}
