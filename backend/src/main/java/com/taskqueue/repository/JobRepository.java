package com.taskqueue.repository;

import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByTenantIdAndStatus(String tenantId, JobStatus status);

    Page<Job> findByTenantId(String tenantId, Pageable pageable);

    long countByTenantIdAndStatus(String tenantId, JobStatus status);

    Optional<Job> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    @Query("SELECT j FROM Job j WHERE j.status = 'RUNNING' AND j.lockedUntil < :now")
    List<Job> findExpiredLeases(@Param("now") LocalDateTime now);

    Page<Job> findByTenantIdAndStatus(String tenantId, JobStatus status, Pageable pageable);

    List<Job> findByStatus(JobStatus status);

    @Query("SELECT j.tenantId, COUNT(j) FROM Job j WHERE j.status = 'RUNNING' GROUP BY j.tenantId")
    List<Object[]> countRunningByTenant();
}
