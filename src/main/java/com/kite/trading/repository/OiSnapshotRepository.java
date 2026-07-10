package com.kite.trading.repository;

import com.kite.trading.entity.OiSnapshotEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OiSnapshotRepository extends JpaRepository<OiSnapshotEntity, Long> {

  List<OiSnapshotEntity> findByTimestampBetweenOrderByTimestampAsc(
      LocalDateTime start, LocalDateTime end);

  List<OiSnapshotEntity> findByIndexNameOrderByTimestampAsc(String indexName);

  long countByTimestampBetween(LocalDateTime start, LocalDateTime end);
}
