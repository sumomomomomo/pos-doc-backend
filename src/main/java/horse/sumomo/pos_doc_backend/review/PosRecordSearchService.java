package horse.sumomo.pos_doc_backend.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordSearchRequest;
import com.yourcompany.pos.api.model.PosRecordSearchRequest.SortEnum;
import com.yourcompany.pos.api.model.PosRecordSummary;

import jakarta.persistence.criteria.Predicate;

import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus;
import horse.sumomo.pos_doc_backend.persistence.normalization.MetadataNormalizer;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;

/**
 * Search service for {@code POST /api/v1/pos-records/search}.
 *
 * <p>Validates the request (protecting direct calls in addition to Bean
 * Validation on the HTTP path), loads the active candidate rows through a
 * read-only Specification (soft-deleted records are excluded by construction),
 * scores policyholder-name similarity in Java, sorts deterministically, and
 * pages the result. It only ever reads scalar POS-record columns: it does not
 * initialize the source-archive, document, or OCR associations and never reads
 * OCR text.
 */
@Service
public class PosRecordSearchService {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;
	private static final double DEFAULT_THRESHOLD = 0.3;

	private final PosRecordRepository posRecordRepository;

	public PosRecordSearchService(PosRecordRepository posRecordRepository) {
		this.posRecordRepository = Objects.requireNonNull(posRecordRepository);
	}

	@Transactional(readOnly = true)
	public PosRecordSearchPage search(PosRecordSearchRequest request) {
		Objects.requireNonNull(request, "request must not be null");

		int page = request.getPage() == null ? DEFAULT_PAGE : request.getPage();
		int size = request.getSize() == null ? DEFAULT_SIZE : request.getSize();
		double threshold = request.getMinimumNameSimilarity() == null ? DEFAULT_THRESHOLD : request.getMinimumNameSimilarity();
		boolean fuzzy = request.getFuzzyName() == null || request.getFuzzyName();
		SortEnum sort = request.getSort() == null ? SortEnum.RELEVANCE : request.getSort();

		if (page < 0 || size < 1 || size > MAX_SIZE || threshold < 0.0 || threshold > 1.0) {
			throw new PosRecordApiException(PosRecordApiException.Code.INVALID_SEARCH_REQUEST);
		}

		String erefNorm = normalizeFilter(request.getErefNumber(), false);
		String policyNorm = normalizeFilter(request.getPolicyNumber(), false);
		String nameNorm = normalizeFilter(request.getPolicyholderName(), true);
		boolean nameQuery = nameNorm != null;

		List<PosRecordStatus> statusFilter = null;
		if (request.getStatuses() != null && !request.getStatuses().isEmpty()) {
			statusFilter = request.getStatuses().stream()
					.map(status -> PosRecordStatus.valueOf(status.name()))
					.toList();
		}

		List<PosRecordEntity> candidates = this.posRecordRepository.findAll(activeCandidateSpec(
				erefNorm, policyNorm, nameNorm, fuzzy, statusFilter));

		List<Candidate> rows = new ArrayList<>();
		for (PosRecordEntity entity : candidates) {
			double score = 0.0;
			if (nameQuery) {
				String holderNorm = entity.getPolicyholderNameNormalized();
				if (holderNorm != null) {
					score = fuzzy ? TrigramSimilarity.similarity(nameNorm, holderNorm)
							: (nameNorm.equals(holderNorm) ? 1.0 : 0.0);
				}
				if (score < threshold) {
					continue;
				}
			}
			rows.add(new Candidate(entity, score));
		}

		rows.sort(comparator(sort, nameQuery));

		long totalElements = rows.size();
		int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
		long fromIndex = (long) page * size;
		List<PosRecordSummary> items;
		if (fromIndex >= totalElements) {
			items = List.of();
		}
		else {
			int toIndex = (int) Math.min(fromIndex + size, totalElements);
			items = rows.subList((int) fromIndex, toIndex).stream()
					.map(row -> PosRecordApiMapper.toSummary(row.entity()))
					.toList();
		}

		return new PosRecordSearchPage(items, page, size, totalElements, totalPages);
	}

	private static String normalizeFilter(String raw, boolean name) {
		if (raw == null) {
			return null;
		}
		if (raw.isBlank()) {
			throw new PosRecordApiException(PosRecordApiException.Code.INVALID_SEARCH_REQUEST);
		}
		return name ? MetadataNormalizer.normalizeName(raw) : MetadataNormalizer.normalizeIdentifier(raw);
	}

	private static Specification<PosRecordEntity> activeCandidateSpec(String erefNorm, String policyNorm,
			String nameNorm, boolean fuzzy, List<PosRecordStatus> statusFilter) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.isNull(root.get("deletedAt")));
			if (erefNorm != null) {
				predicates.add(cb.equal(root.get("erefNumberNormalized"), erefNorm));
			}
			if (policyNorm != null) {
				predicates.add(cb.equal(root.get("policyNumberNormalized"), policyNorm));
			}
			if (nameNorm != null && !fuzzy) {
				predicates.add(cb.equal(root.get("policyholderNameNormalized"), nameNorm));
			}
			if (statusFilter != null && !statusFilter.isEmpty()) {
				predicates.add(root.get("status").in(statusFilter));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	private static Comparator<Candidate> comparator(SortEnum sort, boolean nameQuery) {
		Comparator<Candidate> idAsc = Comparator.comparing(Candidate::id);
		Comparator<Candidate> updatedAtDesc = Comparator.comparing(Candidate::updatedAt, Comparator.reverseOrder());
		Comparator<Candidate> updatedAtAsc = Comparator.comparing(Candidate::updatedAt);
		Comparator<Candidate> uploadedAtDesc = Comparator.comparing(Candidate::uploadedAt, Comparator.reverseOrder());
		Comparator<Candidate> uploadedAtAsc = Comparator.comparing(Candidate::uploadedAt);
		Comparator<Candidate> similarityDesc = Comparator.comparingDouble(Candidate::score).reversed();

		return switch (sort) {
			case RELEVANCE -> nameQuery
					? similarityDesc.thenComparing(updatedAtDesc).thenComparing(idAsc)
					: updatedAtDesc.thenComparing(idAsc);
			case UPLOADED_AT_DESC -> uploadedAtDesc.thenComparing(idAsc);
			case UPLOADED_AT_ASC -> uploadedAtAsc.thenComparing(idAsc);
			case UPDATED_AT_DESC -> updatedAtDesc.thenComparing(idAsc);
			case UPDATED_AT_ASC -> updatedAtAsc.thenComparing(idAsc);
		};
	}

	private record Candidate(PosRecordEntity entity, double score) {
		UUID id() {
			return this.entity.getId();
		}

		Instant updatedAt() {
			return this.entity.getUpdatedAt();
		}

		Instant uploadedAt() {
			return this.entity.getUploadedAt();
		}
	}

}
