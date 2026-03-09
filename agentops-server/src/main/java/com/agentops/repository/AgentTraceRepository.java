package com.agentops.repository;

import com.agentops.domain.entity.AgentTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentTraceRepository extends JpaRepository<AgentTrace, Long> {

    List<AgentTrace> findBySessionIdOrderByStepIndex(String sessionId);

    List<AgentTrace> findBySessionIdAndAgentNameOrderByStepIndex(String sessionId, String agentName);
}
