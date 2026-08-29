package horse.sumomo.pos_doc_backend.persistence.converter;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link Instant} values to epoch-millisecond {@code INTEGER} columns.
 *
 * <p>The conversion is timezone-independent: an {@link Instant} is an
 * absolute point on the UTC timeline, so no host timezone is ever consulted.
 * {@code null} is preserved in both directions.
 */
@Converter(autoApply = true)
public class InstantEpochMillisConverter implements AttributeConverter<Instant, Long> {

	@Override
	public Long convertToDatabaseColumn(Instant attribute) {
		return attribute == null ? null : attribute.toEpochMilli();
	}

	@Override
	public Instant convertToEntityAttribute(Long dbData) {
		return dbData == null ? null : Instant.ofEpochMilli(dbData);
	}

}
