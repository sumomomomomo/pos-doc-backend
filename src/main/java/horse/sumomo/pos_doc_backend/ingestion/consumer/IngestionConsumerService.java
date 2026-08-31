package horse.sumomo.pos_doc_backend.ingestion.consumer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import horse.sumomo.pos_doc_backend.ingestion.consumer.ArchiveExtractionService.ExtractedPdf;
import horse.sumomo.pos_doc_backend.persistence.entity.IngestionJobEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.PosRecordEntity;
import horse.sumomo.pos_doc_backend.persistence.entity.StorageObjectEntity;
import horse.sumomo.pos_doc_backend.persistence.repository.IngestionJobRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.PosRecordRepository;
import horse.sumomo.pos_doc_backend.persistence.repository.StorageObjectRepository;

/**
 * Orchestrates one ingestion attempt: claim, source-ZIP download, ZIP
 * revalidation, PDF extraction and storage, then transactional
 * metadata persistence with terminal-state updates.
 *
 * <p>This class never holds a database transaction open while talking to
 * MinIO or the ZIP reader. The claim and final persist are separate,
 * short transactions; extraction runs entirely between them.
 */
@Service
public class IngestionConsumerService {

	private static final Logger log = LoggerFactory.getLogger(IngestionConsumerService.class);

	private final IngestionJobRepository jobRepository;
	private final PosRecordRepository recordRepository;
	private final StorageObjectRepository storageObjectRepository;
	private final SourceArchiveDownloader downloader;
	private final ArchiveExtractionService extractor;
	private final ExtractionPersistenceService persistenceService;

	private final IngestionConsumerService self;

	public IngestionConsumerService(@Lazy IngestionConsumerService self,
			IngestionJobRepository jobRepository,
			PosRecordRepository recordRepository, StorageObjectRepository storageObjectRepository,
			SourceArchiveDownloader downloader, ArchiveExtractionService extractor,
			ExtractionPersistenceService persistenceService) {
		this.self = self;
		this.jobRepository = Objects.requireNonNull(jobRepository);
		this.recordRepository = Objects.requireNonNull(recordRepository);
		this.storageObjectRepository = Objects.requireNonNull(storageObjectRepository);
		this.downloader = Objects.requireNonNull(downloader);
		this.extractor = Objects.requireNonNull(extractor);
		this.persistenceService = Objects.requireNonNull(persistenceService);
	}

	/**
	 * Performs one consumer attempt for the given message. Throws
	 * {@link ConsumerException} on categorized failure; the listener's
	 * retry/recoverer decides whether to retry or DLQ. This method does
	 * not record the failure state itself — that responsibility belongs
	 * to the AMQP recoverer so a database failure on the main flow
	 * cannot prevent the terminal FAILED transition from being
	 * recorded.
	 */
	public void consume(IngestionMessageIdentifiers ids) {
		Objects.requireNonNull(ids, "ids must not be null");

		Claim claim = self.claim(ids);
		switch (claim.action) {
			case START -> runExtraction(claim);
			case IDEMPOTENT_NOOP -> log.debug("Idempotent no-op; job already completed (category=idempotent-noop); jobId={}",
					ids.jobId());
			case TERMINAL_NOOP -> log.debug("Terminal FAILED; ACK (category=terminal-noop); jobId={}",
					ids.jobId());
			case STATE_CONFLICT -> throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
		}
	}

	@Transactional
	protected Claim claim(IngestionMessageIdentifiers ids) {
		IngestionJobEntity job = this.jobRepository.findById(ids.jobId())
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		PosRecordEntity record = job.getPosRecord();
		if (record == null || !record.getId().equals(ids.posRecordId)) {
			throw new ConsumerException(ConsumerException.Code.ID_MISMATCH);
		}
		if (record.getDeletedAt() != null) {
			throw new ConsumerException(ConsumerException.Code.RECORD_DELETED);
		}

		switch (job.getStatus()) {
			case QUEUED, RETRY_SCHEDULED -> {
				job.startAttempt(Instant.now());
				record.markProcessing(Instant.now());
				this.jobRepository.saveAndFlush(job);
				this.recordRepository.saveAndFlush(record);
				return new Claim(ClaimAction.START, job.getId());
			}
			case RUNNING -> {
				// Crash recovery: the previous attempt left the job in
				// RUNNING. Bump the attempt counter so observers see the
				// reentry; otherwise treat as a normal extraction.
				job.startAttempt(Instant.now());
				this.jobRepository.saveAndFlush(job);
				return new Claim(ClaimAction.START, job.getId());
			}
			case COMPLETED -> {
				long docCount = this.persistenceService.countDocuments(ids.posRecordId);
				if (docCount > 0) {
					return new Claim(ClaimAction.IDEMPOTENT_NOOP, job.getId());
				}
				throw new ConsumerException(ConsumerException.Code.EXTRACTION_STATE_CONFLICT);
			}
			case FAILED -> {
				return new Claim(ClaimAction.TERMINAL_NOOP, job.getId());
			}
			default -> throw new IllegalStateException("unexpected job status: " + job.getStatus());
		}
	}

	private void runExtraction(Claim claim) {
		SourceArchiveSpec source = self.loadSourceArchive(claim.jobId());
		SourceArchiveDownloader.DownloadedArchive downloaded;
		try {
			downloaded = this.downloader.download(source.objectKey(), source.byteSize(), source.sha256());
		}
		catch (ConsumerException e) {
			throw e;
		}

		List<ExtractedPdf> extracted = new ArrayList<>();
		List<ExtractedPdf> uploaded = new ArrayList<>();
		try (var ignored = downloaded) {
			extracted = this.extractor.extractAndStore(downloaded.getTempPath(), downloaded.getByteCount(),
					self.loadPosRecordIdForJob(claim.jobId()));
			uploaded.addAll(extracted);
			this.persistenceService.persistExtraction(self.loadPosRecordIdForJob(claim.jobId()), claim.jobId(),
					extracted, Instant.now());
		}
		catch (ConsumerException e) {
			this.extractor.compensate(uploaded);
			throw e;
		}
		catch (RuntimeException e) {
			this.extractor.compensate(uploaded);
			throw new ConsumerException(ConsumerException.Code.EXTRACTION_TRANSIENT_FAILURE, e);
		}
	}

	@Transactional(readOnly = true)
	protected UUID loadPosRecordIdForJob(UUID jobId) {
		IngestionJobEntity job = this.jobRepository.findById(jobId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		return job.getPosRecord().getId();
	}

	@Transactional(readOnly = true)
	protected SourceArchiveSpec loadSourceArchive(UUID jobId) {
		IngestionJobEntity job = this.jobRepository.findById(jobId)
				.orElseThrow(() -> new ConsumerException(ConsumerException.Code.ID_MISMATCH));
		PosRecordEntity record = job.getPosRecord();
		if (record == null) {
			throw new ConsumerException(ConsumerException.Code.ID_MISMATCH);
		}
		StorageObjectEntity source = record.getSourceArchive();
		if (source == null) {
			throw new ConsumerException(ConsumerException.Code.SOURCE_ARCHIVE_MISSING);
		}
		// Snapshot the source fields inside the transaction so the consumer
		// can call {@link SourceArchiveDownloader} after the session closes.
		return new SourceArchiveSpec(source.getObjectKey(), source.getByteSize(), source.getSha256());
	}

	/** Snapshot of {@link StorageObjectEntity} fields read inside a transaction. */
	record SourceArchiveSpec(String objectKey, long byteSize, String sha256) {
	}

	/**
	 * Identifiers carried by one inbound message; used as input to
	 * {@link #consume}.
	 */
	public record IngestionMessageIdentifiers(UUID jobId, UUID posRecordId) {
	}

	/** Internal claim action decided by {@link #claim}. */
	enum ClaimAction {
		START, IDEMPOTENT_NOOP, TERMINAL_NOOP, STATE_CONFLICT
	}

	/** Result of {@link #claim}: action and the job id. */
	record Claim(ClaimAction action, UUID jobId) {
	}

}