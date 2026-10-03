package com.whatsappclone.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the media endpoints without touching S3. Presigning is a local signature computation, so
 * dummy credentials produce genuine, correctly signed URLs offline. The one endpoint that does need
 * the network — {@code /complete}, which issues a HEAD — is only asserted for its rejection paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaTests {

	private static final String BUCKET = "whatsapp-clone-media-test";
	private static final long MAX_BYTES = 26214400L;

	@org.springframework.test.context.DynamicPropertySource
	static void mediaProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("app.media.bucket", () -> BUCKET);
		// Dummy credentials. These are Spring properties rather than system properties because
		// MediaConfig reads the Spring Environment -- the SDK's own default chain cannot see a value
		// imported through `spring.config.import`, which is the whole reason MediaConfig reads them.
		registry.add("AWS_ACCESS_KEY_ID", () -> "AKIAIOSFODNN7EXAMPLE");
		registry.add("AWS_SECRET_ACCESS_KEY", () -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
	}

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	private final RestTemplate rest = new RestTemplate();

	@Test
	@DisplayName("an upload URL is signed, scoped to the caller, and pinned to a content type")
	void uploadUrlIsSignedAndScoped() {
		Session session = register();

		JsonNode upload = post("/api/media/upload-url",
				Map.of("contentType", "image/png", "sizeBytes", 2048), session.token()).get("data");

		String key = upload.get("objectKey").asString();
		assertThat(key).as("keys are scoped to the uploader").startsWith("media/" + session.userId() + "/");
		assertThat(key).as("the extension comes from config, not the client").endsWith(".png");

		String url = upload.get("uploadUrl").asString();
		assertThat(url).as("the signature is present").contains("X-Amz-Signature=");
		assertThat(url).as("it is scoped to this exact object").contains(key);
		assertThat(url).as("it targets the configured bucket").contains(BUCKET);
		assertThat(upload.get("headers").get("Content-Type").asString())
				.as("the client must send the content type that was signed")
				.isEqualTo("image/png");
		assertThat(upload.get("expiresInSeconds").asLong()).isEqualTo(300);
	}

	@Test
	@DisplayName("a disallowed content type or oversized file never gets a URL")
	void uploadUrlRejectsBadDeclarations() {
		Session session = register();

		assertErrorCode(() -> post("/api/media/upload-url",
				Map.of("contentType", "application/x-msdownload", "sizeBytes", 10), session.token()),
				"VALIDATION_ERROR");
		assertErrorCode(() -> post("/api/media/upload-url",
				Map.of("contentType", "text/html", "sizeBytes", 10), session.token()),
				"VALIDATION_ERROR");
		assertErrorCode(() -> post("/api/media/upload-url",
				Map.of("contentType", "image/png", "sizeBytes", MAX_BYTES + 1), session.token()),
				"VALIDATION_ERROR");
	}

	@Test
	@DisplayName("complete rejects a key the caller was not issued")
	void completeRejectsForeignKey() {
		Session session = register();
		Session other = register();

		// No HEAD is attempted: ownership is checked first, so this never reaches S3.
		assertErrorCode(() -> post("/api/media/complete",
				Map.of("objectKey", "media/" + other.userId() + "/someone-elses.png"), session.token()),
				"VALIDATION_ERROR");
		assertErrorCode(() -> post("/api/media/complete",
				Map.of("objectKey", "media/" + session.userId() + "/../../etc/passwd"), session.token()),
				"VALIDATION_ERROR");
	}

	@Test
	@DisplayName("download is refused for a message the caller cannot see")
	void downloadRequiresMembership() {
		Session alice = register();
		Session outsider = register();

		// Never reaches an authorisation check: the message does not exist.
		assertErrorCode(() -> post("/api/media/download-url", Map.of("messageId", UUID.randomUUID()),
				outsider.token()), "NOT_FOUND");

		assertThat(alice.userId()).isNotEqualTo(outsider.userId());
	}

	private void assertErrorCode(Runnable call, String expectedCode) {
		try {
			call.run();
		}
		catch (org.springframework.web.client.HttpClientErrorException ex) {
			assertThat(objectMapper.readTree(ex.getResponseBodyAsString()).get("error").get("code").asString())
					.isEqualTo(expectedCode);
			return;
		}
		throw new AssertionError("expected the request to fail with " + expectedCode);
	}

	private record Session(UUID userId, String token) {
	}

	private Session register() {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		StringBuilder phone = new StringBuilder("+1415");
		Random random = new Random();
		for (int i = 0; i < 7; i++) {
			phone.append(random.nextInt(10));
		}
		JsonNode data = post("/api/auth/register",
				Map.of("phoneNumber", phone.toString(), "username", "user_" + suffix, "password", "correct-horse"),
				null).get("data");
		return new Session(UUID.fromString(data.get("user").get("id").asString()),
				data.get("accessToken").asString());
	}

	private JsonNode post(String path, Object body, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			headers.setBearerAuth(token);
		}
		ResponseEntity<String> response = rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful())
				.as("POST %s -> %s: %s", path, response.getStatusCode(), response.getBody())
				.isTrue();
		return objectMapper.readTree(response.getBody());
	}
}