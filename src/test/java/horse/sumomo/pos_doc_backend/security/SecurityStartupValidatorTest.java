package horse.sumomo.pos_doc_backend.security;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fail-closed startup validation tests (Task 11 configuration tests 2 and 8).
 * The validator only reads the environment and never logs credential or subject
 * values.
 */
class SecurityStartupValidatorTest {

	private static SecurityProperties google() {
		return new SecurityProperties("google", new SecurityProperties.Google(List.of("v"), List.of()),
				List.of(), "/", "");
	}

	private static SecurityProperties stackTest() {
		return new SecurityProperties("stack-test", new SecurityProperties.Google(List.of("v"), List.of()),
				List.of(), "/", "0".repeat(64));
	}

	// 2. Missing/placeholder Google credentials fail closed.
	@Test
	void googleModeFailsWithoutCredentials() {
		MockEnvironment env = new MockEnvironment();
		assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(google(), env).validate());
	}

	@Test
	void googleModeFailsWithUnsetPlaceholderClient() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("GOOGLE_CLIENT_ID", "pos-doc-google-client-id-unset");
		env.setProperty("GOOGLE_CLIENT_SECRET", "a-usable-secret");
		assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(google(), env).validate());
	}

	@Test
	void googleModeFailsWithChangeMeSecret() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("GOOGLE_CLIENT_ID", "a-usable-id");
		env.setProperty("GOOGLE_CLIENT_SECRET", "change-me");
		assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(google(), env).validate());
	}

	// The exact placeholder values tracked in .env.example must be rejected.
	@Test
	void googleModeFailsWithTrackedExamplePlaceholderCredentials() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("GOOGLE_CLIENT_ID", "your-google-oauth-client-id");
		env.setProperty("GOOGLE_CLIENT_SECRET", "your-google-oauth-client-secret");
		assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(google(), env).validate());
	}

	// A single your- prefixed placeholder credential fails closed even if the other is usable.
	@Test
	void googleModeFailsWithYourPrefixedClientId() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("GOOGLE_CLIENT_ID", "your-google-oauth-client-id");
		env.setProperty("GOOGLE_CLIENT_SECRET", "a-usable-secret");
		assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(google(), env).validate());
	}

	@Test
	void googleModePassesWithUsableCredentials() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("GOOGLE_CLIENT_ID", "test-client-id");
		env.setProperty("GOOGLE_CLIENT_SECRET", "test-client-secret");
		assertDoesNotThrow(() -> new SecurityStartupValidator(google(), env).validate());
	}

	// 8. Stack-test mode requires the matching Spring profile.
	@Test
	void stackTestModeFailsWithoutProfile() {
		MockEnvironment env = new MockEnvironment();
		assertThrows(IllegalStateException.class, () -> new SecurityStartupValidator(stackTest(), env).validate());
	}

	@Test
	void stackTestModePassesWithProfile() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("stack-test");
		assertDoesNotThrow(() -> new SecurityStartupValidator(stackTest(), env).validate());
	}

}
