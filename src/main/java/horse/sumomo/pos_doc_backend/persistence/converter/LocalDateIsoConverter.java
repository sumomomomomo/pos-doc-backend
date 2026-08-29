package horse.sumomo.pos_doc_backend.persistence.converter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link LocalDate} values to ISO-8601 calendar-date {@code TEXT}
 * columns (e.g. {@code 2026-08-29}).
 *
 * <p>A {@link LocalDate} carries no time-of-day and no timezone, so the
 * conversion is fully deterministic and never consults the host timezone.
 * {@code null} is preserved in both directions.
 */
@Converter(autoApply = true)
public class LocalDateIsoConverter implements AttributeConverter<LocalDate, String> {

	@Override
	public String convertToDatabaseColumn(LocalDate attribute) {
		return attribute == null ? null : DateTimeFormatter.ISO_LOCAL_DATE.format(attribute);
	}

	@Override
	public LocalDate convertToEntityAttribute(String dbData) {
		return dbData == null ? null : LocalDate.parse(dbData, DateTimeFormatter.ISO_LOCAL_DATE);
	}

}
