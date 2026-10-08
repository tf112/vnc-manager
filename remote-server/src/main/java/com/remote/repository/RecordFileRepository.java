package com.remote.repository;

import com.remote.entity.RecordFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecordFileRepository extends JpaRepository<RecordFile, Long>, JpaSpecificationExecutor<RecordFile> {

    List<RecordFile> findByClientIdOrderByCreatedAtDesc(String clientId);

    List<RecordFile> findByTaskId(String taskId);
}
