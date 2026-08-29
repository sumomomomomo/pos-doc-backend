package horse.sumomo.pos_doc_backend.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import horse.sumomo.pos_doc_backend.persistence.entity.OutboxEventEntity;

/**
 * Repository for outbox events.
 *
 * <p>The pending-batch query is the relay's read path: unpublished events
 * whose next attempt is due, oldest first. It is an explicit JPQL query
 * because the derived form would be unreadable.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

	@Query("""
			select e from OutboxEventEntity e
			where e.publishedAt is null and e.nextAttemptAt <= :now
			order by e.createdAt asc
			""")
	List<OutboxEventEntity> findPendingDue(@Param("now") Instant now);

	Optional<OutboxEventEntity> findById(UUID id);

	long countByPublishedAtIsNull();

}
