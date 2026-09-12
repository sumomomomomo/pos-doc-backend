package horse.sumomo.pos_doc_backend.review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.yourcompany.pos.api.model.PosRecordSearchPage;
import com.yourcompany.pos.api.model.PosRecordSearchRequest;
import com.yourcompany.pos.api.model.PosRecordStatus;
import com.yourcompany.pos.api.model.PosRecordSummary;

import horse.sumomo.pos_doc_backend.persistence.entity.PosDocumentEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.model.DocumentProcessingStatus;
import horse.sumomo.pos_doc_backend.persistence.repository.PosDocumentRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;
import horse.sumomo.pos_doc_backend.review.testsupport.ReviewTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence integration tests for {@link PosRecordSearchService} over a
 * temporary SQLite database. Each test creates its own synthetic records with
 * unique identifiers (so active-record uniqueness is never violated) and
 * isolates its assertions by membership / a unique shared policyholder name.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PosRecordSearchServiceIntegrationTest {

	private static final horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus RR =
			horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.REVIEW_REQUIRED;
	private static final horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus COMPLETED =
			horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.COMPLETED;
	private static final horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus PROCESSING =
			horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus.PROCESSING;

	@Autowired
	private PosRecordSearchService searchService;

	@Autowired
	private PosRecordRepository posRecordRepository;

	@Autowired
	private StorageObjectRepository storageObjectRepository;

	@Autowired
	private PosDocumentRepository posDocumentRepository;

	@DynamicPropertySource
	static void sqliteUrl(DynamicPropertyRegistry registry) throws Exception {
		Path dbFile = Files.createTempFile("pos-doc-review-search-test", ".db");
		dbFile.toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-wal").toFile().deleteOnExit();
		Path.of(dbFile.toString() + "-shm").toFile().deleteOnExit();
		registry.add("SQLITE_URL", () -> "jdbc:sqlite:" + dbFile);
	}

	/**
	 * The class shares one SQLite database. The search service pages results
	 * (default size 20), so a broad query could paginate a test's own records out
	 * of the top page once enough sibling tests have inserted rows. Clearing the
	 * two tables after each test keeps every test's assertions deterministic.
	 */
	@AfterEach
	void cleanDatabase() {
		this.posDocumentRepository.deleteAll();
		this.posRecordRepository.deleteAll();
		this.storageObjectRepository.deleteAll();
	}

	// ------------------------------------------------------------------
	// 1: empty request
	// ------------------------------------------------------------------

	@Test
	void emptyRequestReturnsOnlyActiveRowsWithDefaults() {
		PosRecordEntity a1 = rec(null, RR, T(1), T(1));
		PosRecordEntity a2 = rec(null, RR, T(2), T(2));
		PosRecordEntity a3 = rec(null, RR, T(3), T(3));
		PosRecordEntity deleted = rec(null, COMPLETED, T(4), T(4));
		deleted.markDeleted(T(9));
		this.posRecordRepository.saveAndFlush(deleted);

		PosRecordSearchPage page = this.searchService.search(new PosRecordSearchRequest());

		assertEquals(0, page.getPage());
		assertEquals(20, page.getSize());
		assertTrue(contains(page, a1.getId()));
		assertTrue(contains(page, a2.getId()));
		assertTrue(contains(page, a3.getId()));
		assertFalse(contains(page, deleted.getId()), "soft-deleted record must be excluded");
	}

	// ------------------------------------------------------------------
	// 2: eRef exact (normalized, no substring)
	// ------------------------------------------------------------------

	@Test
	void erefExactUsesNormalizationAndNoSubstringMatching() {
		PosRecordEntity record = recWithEref("EREF-2026-00123");

		PosRecordSearchPage hit = this.searchService.search(new PosRecordSearchRequest()
				.erefNumber(" eref 2026 00123 ")); // normalizes to EREF202600123
		assertTrue(contains(hit, record.getId()));

		PosRecordSearchPage miss = this.searchService.search(new PosRecordSearchRequest()
				.erefNumber("EREF2026")); // a strict prefix, not a full normalized match
		assertFalse(contains(miss, record.getId()), "exact matching must not do substring search");
	}

	// ------------------------------------------------------------------
	// 3: policy number exact (normalized)
	// ------------------------------------------------------------------

	@Test
	void policyNumberExactUsesNormalization() {
		PosRecordEntity record = recWithPolicy("P-123-456"); // normalizes to P123456

		assertTrue(contains(this.searchService.search(new PosRecordSearchRequest().policyNumber("p123456")),
				record.getId()));
		assertFalse(contains(this.searchService.search(new PosRecordSearchRequest().policyNumber("123")),
				record.getId()));
	}

	// ------------------------------------------------------------------
	// 4: exact name match with fuzzy off
	// ------------------------------------------------------------------

	@Test
	void exactNormalizedNameMatchingWithFuzzyOff() {
		PosRecordEntity record = rec("Jane   TAN", RR, T(1), T(1));

		assertTrue(contains(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName("JANE   TAN").fuzzyName(false)),
				record.getId()));
		assertFalse(contains(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName("Jane Tanx").fuzzyName(false)),
				record.getId()));
	}

	// ------------------------------------------------------------------
	// 5: fuzzy misspelling passes low threshold, fails high
	// ------------------------------------------------------------------

	@Test
	void fuzzyMisspellingPassesAtLowAndFailsAtHighThreshold() {
		PosRecordEntity record = rec("jane tann", RR, T(1), T(1));

		PosRecordSearchPage low = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName("jane tan").fuzzyName(true).minimumNameSimilarity(0.3));
		assertTrue(contains(low, record.getId()), "near match should pass at 0.3");

		PosRecordSearchPage high = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName("jane tan").fuzzyName(true).minimumNameSimilarity(0.95));
		assertFalse(contains(high, record.getId()), "near match should fail at 0.95");
	}

	// ------------------------------------------------------------------
	// 7: AND semantics across criteria
	// ------------------------------------------------------------------

	@Test
	void multipleCriteriaUseAndSemantics() {
		String eref = "EREF-AND-" + shortUuid();
		String policy = "POL-AND-" + shortUuid();
		PosRecordEntity match = recWithBoth(eref, policy);
		PosRecordEntity sameErefOnly = recWithEref(eref + "X"); // different policy
		PosRecordEntity samePolicyOnly = recWithPolicy(policy + "X"); // different eref

		PosRecordSearchPage page = this.searchService.search(new PosRecordSearchRequest()
				.erefNumber(eref).policyNumber(policy));

		assertTrue(contains(page, match.getId()));
		assertFalse(contains(page, sameErefOnly.getId()));
		assertFalse(contains(page, samePolicyOnly.getId()));
	}

	// ------------------------------------------------------------------
	// 8: status filtering
	// ------------------------------------------------------------------

	@Test
	void statusFilteringAndEmptyMeanNoFilter() {
		PosRecordEntity rr = rec("st-rr-" + shortUuid(), RR, T(1), T(1));
		PosRecordEntity completed = rec("st-completed-" + shortUuid(), COMPLETED, T(2), T(2));
		PosRecordEntity processing = rec("st-processing-" + shortUuid(), PROCESSING, T(3), T(3));

		PosRecordSearchPage filtered = this.searchService.search(new PosRecordSearchRequest()
				.addStatusesItem(PosRecordStatus.REVIEW_REQUIRED));
		assertTrue(contains(filtered, rr.getId()));
		assertFalse(contains(filtered, completed.getId()));
		assertFalse(contains(filtered, processing.getId()));

		PosRecordSearchPage noFilter = this.searchService.search(new PosRecordSearchRequest());
		assertTrue(contains(noFilter, rr.getId()));
		assertTrue(contains(noFilter, completed.getId()));
		assertTrue(contains(noFilter, processing.getId()));
	}

	// ------------------------------------------------------------------
	// 9: sort modes + UUID tie-breaker
	// ------------------------------------------------------------------

	@Test
	void updatedAtSortModesFollowDefinedOrder() {
		String holder = "sort-upd-" + shortUuid();
		PosRecordEntity old = rec(holder, RR, T(100), T(1));
		PosRecordEntity mid = rec(holder, RR, T(100), T(2));
		PosRecordEntity newest = rec(holder, RR, T(100), T(3));

		assertEquals(List.of(newest.getId(), mid.getId(), old.getId()),
				ids(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName(holder).fuzzyName(false).sort(PosRecordSearchRequest.SortEnum.UPDATED_AT_DESC))));
		assertEquals(List.of(old.getId(), mid.getId(), newest.getId()),
				ids(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName(holder).fuzzyName(false).sort(PosRecordSearchRequest.SortEnum.UPDATED_AT_ASC))));
		// RELEVANCE with an exact name match (all scores equal) falls back to updatedAt desc.
		assertEquals(List.of(newest.getId(), mid.getId(), old.getId()),
				ids(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName(holder).fuzzyName(false).sort(PosRecordSearchRequest.SortEnum.RELEVANCE))));
	}

	@Test
	void uploadedAtSortModesFollowDefinedOrder() {
		String holder = "sort-up-" + shortUuid();
		PosRecordEntity old = rec(holder, RR, T(1), T(1));
		PosRecordEntity mid = rec(holder, RR, T(2), T(2));
		PosRecordEntity newest = rec(holder, RR, T(3), T(3));

		assertEquals(List.of(newest.getId(), mid.getId(), old.getId()),
				ids(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName(holder).fuzzyName(false).sort(PosRecordSearchRequest.SortEnum.UPLOADED_AT_DESC))));
		assertEquals(List.of(old.getId(), mid.getId(), newest.getId()),
				ids(this.searchService.search(new PosRecordSearchRequest()
						.policyholderName(holder).fuzzyName(false).sort(PosRecordSearchRequest.SortEnum.UPLOADED_AT_ASC))));
	}

	@Test
	void uuidIsTheFinalTieBreaker() {
		String holder = "sort-tie-" + shortUuid();
		PosRecordEntity r1 = rec(holder, RR, T(1), T(7));
		PosRecordEntity r2 = rec(holder, RR, T(1), T(7)); // same updatedAt as r1

		List<UUID> expected = r1.getId().compareTo(r2.getId()) <= 0
				? List.of(r1.getId(), r2.getId()) : List.of(r2.getId(), r1.getId());
		assertEquals(expected, ids(this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(holder).fuzzyName(false).sort(PosRecordSearchRequest.SortEnum.UPDATED_AT_DESC))));
	}

	@Test
	void relevanceWithoutNameQueryOrdersByUpdatedAtThenUuid() {
		// A genuinely name-less RELEVANCE search (no policyholderName or other name
		// criteria) must fall back to updatedAt descending, then UUID ascending.
		PosRecordEntity old = rec(null, RR, T(100), T(1));
		PosRecordEntity mid = rec(null, RR, T(100), T(2));
		PosRecordEntity newestA = rec(null, RR, T(100), T(3));
		PosRecordEntity newestB = rec(null, RR, T(100), T(3)); // ties newest on updatedAt

		List<UUID> expected = newestA.getId().compareTo(newestB.getId()) <= 0
				? List.of(newestA.getId(), newestB.getId(), mid.getId(), old.getId())
				: List.of(newestB.getId(), newestA.getId(), mid.getId(), old.getId());

		assertEquals(expected,
				ids(this.searchService.search(new PosRecordSearchRequest()
						.sort(PosRecordSearchRequest.SortEnum.RELEVANCE))));
	}

	// ------------------------------------------------------------------
	// 10: RELEVANCE orders higher similarity first
	// ------------------------------------------------------------------

	@Test
	void relevanceOrdersHigherSimilarityFirst() {
		String base = "zx" + shortUuid();
		PosRecordEntity exact = rec(base + " tan", RR, T(1), T(1));
		PosRecordEntity near = rec(base + " tanx", RR, T(2), T(2));
		PosRecordEntity unrelated = rec(base + " zzz", RR, T(3), T(3));

		List<UUID> ordered = ids(this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(base + " tan").fuzzyName(true).minimumNameSimilarity(0.4)
				.sort(PosRecordSearchRequest.SortEnum.RELEVANCE)));

		assertEquals(exact.getId(), ordered.get(0), "highest similarity must rank first");
		assertEquals(unrelated.getId(), ordered.get(ordered.size() - 1), "lowest similarity ranks last");
		assertTrue(ordered.contains(near.getId()));
	}

	// ------------------------------------------------------------------
	// 11: pagination applied after ranking
	// ------------------------------------------------------------------

	@Test
	void paginationAppliedAfterRankingWithCorrectTotals() {
		String holder = "pag-" + shortUuid();
		PosRecordEntity r1 = rec(holder, RR, T(1), T(1));
		PosRecordEntity r2 = rec(holder, RR, T(2), T(2));
		PosRecordEntity r3 = rec(holder, RR, T(3), T(3));
		PosRecordEntity r4 = rec(holder, RR, T(4), T(4));
		PosRecordEntity r5 = rec(holder, RR, T(5), T(5));

		PosRecordSearchPage p0 = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(holder).fuzzyName(false).size(2).page(0));
		assertEquals(5L, p0.getTotalElements());
		assertEquals(3, p0.getTotalPages());
		assertEquals(List.of(r5.getId(), r4.getId()), ids(p0));

		PosRecordSearchPage p1 = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(holder).fuzzyName(false).size(2).page(1));
		assertEquals(List.of(r3.getId(), r2.getId()), ids(p1));

		PosRecordSearchPage p2 = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(holder).fuzzyName(false).size(2).page(2));
		assertEquals(List.of(r1.getId()), ids(p2));

		PosRecordSearchPage beyond = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(holder).fuzzyName(false).size(2).page(3));
		assertTrue(ids(beyond).isEmpty());
		assertEquals(5L, beyond.getTotalElements());
		assertEquals(3, beyond.getTotalPages());
	}

	@Test
	void emptyResultHasZeroTotalsAndEmptyItems() {
		PosRecordSearchPage page = this.searchService.search(new PosRecordSearchRequest()
				.erefNumber("NO-SUCH-EREF-" + shortUuid()));
		assertTrue(page.getItems().isEmpty());
		assertEquals(0L, page.getTotalElements());
		assertEquals(0, page.getTotalPages());
	}

	// ------------------------------------------------------------------
	// 12: no association initialization / OCR text
	// ------------------------------------------------------------------

	@Test
	void searchDoesNotTouchDocumentOrOcrAssociations() {
		String holder = "iso-" + shortUuid();
		PosRecordEntity record = rec(holder, RR, T(1), T(1));
		// A document exists on the record; the search must still succeed without
		// initializing that association or reading any OCR text.
		PosDocumentEntity doc = ReviewTestFixtures.saveDocument(this.posDocumentRepository,
				this.storageObjectRepository, record, 0, DocumentProcessingStatus.COMPLETED);
		assertTrue(doc.getId() != null);

		PosRecordSearchPage page = this.searchService.search(new PosRecordSearchRequest()
				.policyholderName(holder).fuzzyName(false));
		assertTrue(contains(page, record.getId()));
		// The summary exposes only scalar display fields (no sourceArchive/documents/OCR).
		PosRecordSummary summary = page.getItems().stream()
				.filter(s -> s.getId().equals(record.getId())).findFirst().orElseThrow();
		assertEquals(holder, summary.getPolicyholderName());
	}

	// ------------------------------------------------------------------
	// search validation (service layer protects direct calls)
	// ------------------------------------------------------------------

	@Test
	void invalidSearchRequestsAreRejected() {
		assertInvalid(new PosRecordSearchRequest().size(0));
		assertInvalid(new PosRecordSearchRequest().size(101));
		assertInvalid(new PosRecordSearchRequest().page(-1));
		assertInvalid(new PosRecordSearchRequest().minimumNameSimilarity(1.5));
		assertInvalid(new PosRecordSearchRequest().minimumNameSimilarity(-0.1));
		assertInvalid(new PosRecordSearchRequest().erefNumber("   "));
	}

	@Test
	void punctuationOnlyIdentifiersAndNonFiniteThresholdsReturn400() {
		// A nonblank identifier with no letters/digits (e.g. "---") empties the
		// normalizer; the service must translate that to a 400, never a 500.
		assertInvalid(new PosRecordSearchRequest().erefNumber("---"));
		assertInvalid(new PosRecordSearchRequest().policyNumber("..."));
		// Non-finite thresholds (NaN and infinities) are rejected via isFinite.
		assertInvalid(new PosRecordSearchRequest().minimumNameSimilarity(Double.NaN));
		assertInvalid(new PosRecordSearchRequest().minimumNameSimilarity(Double.POSITIVE_INFINITY));
		assertInvalid(new PosRecordSearchRequest().minimumNameSimilarity(Double.NEGATIVE_INFINITY));
	}

	private void assertInvalid(PosRecordSearchRequest request) {
		PosRecordApiException ex = org.junit.jupiter.api.Assertions.assertThrows(PosRecordApiException.class,
				() -> this.searchService.search(request));
		assertEquals(PosRecordApiException.Code.INVALID_SEARCH_REQUEST, ex.getCode());
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private PosRecordEntity rec(String holder,
			horse.sumomo.pos_doc_backend.persistence.model.PosRecordStatus status,
			Instant uploadedAt, Instant updatedAt) {
		var archive = ReviewTestFixtures.saveArchive(this.storageObjectRepository, "a-" + UUID.randomUUID());
		PosRecordEntity record = ReviewTestFixtures.saveRecord(this.posRecordRepository, archive, status,
				uploadedAt, updatedAt);
		if (holder != null) {
			record.setPolicyholderName(holder);
		}
		record.setConsultantName("Consultant " + shortUuid());
		return this.posRecordRepository.saveAndFlush(record);
	}

	private PosRecordEntity recWithEref(String eref) {
		PosRecordEntity record = rec(null, RR, T(1), T(1));
		record.setErefNumber(eref);
		return this.posRecordRepository.saveAndFlush(record);
	}

	private PosRecordEntity recWithPolicy(String policy) {
		PosRecordEntity record = rec(null, RR, T(1), T(1));
		record.setPolicyNumber(policy);
		return this.posRecordRepository.saveAndFlush(record);
	}

	private PosRecordEntity recWithBoth(String eref, String policy) {
		PosRecordEntity record = rec(null, RR, T(1), T(1));
		record.setErefNumber(eref);
		record.setPolicyNumber(policy);
		return this.posRecordRepository.saveAndFlush(record);
	}

	private static Instant T(long offsetSeconds) {
		return Instant.ofEpochMilli(1_700_000_000_000L + offsetSeconds * 1000L);
	}

	private static String shortUuid() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static boolean contains(PosRecordSearchPage page, UUID id) {
		return page.getItems().stream().anyMatch(s -> s.getId().equals(id));
	}

	private static List<UUID> ids(PosRecordSearchPage page) {
		return page.getItems().stream().map(PosRecordSummary::getId).toList();
	}

}
