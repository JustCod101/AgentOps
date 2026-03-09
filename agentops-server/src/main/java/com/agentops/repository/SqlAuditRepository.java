package com.agentops.repository;

import com.agentops.domain.entity.SqlAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SqlAuditRepository extends JpaRepository<SqlAuditLog, Long> {

    List<SqlAuditLog> findBySessionId(String sessionId);
}
