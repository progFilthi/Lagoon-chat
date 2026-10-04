import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import com.whatsappclone.backend.BackendApplication;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end against a real S3 bucket: presign, PUT, verify, send the message over the socket, then
 * have a <em>different</em> chat member fetch the bytes back through a signed download URL.
 *
 * <p>Opt-in, because it writes to a live bucket and needs credentials. Enable with
 * {@code RUN_S3_TESTS=true}; it reads {@code MEDIA_BUCKET}, {@code AWS_REGION} and the credentials
 * from {@code backend/.env}. The object it creates is deleted afterwards, including on failure.
 *
 * <p>This covers the two halves the offline suite cannot: that the bucket policy actually permits
 * what a presigned URL asks for, and that a member who did not upload can still download.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_S3_TESTS", matches = "true")
class MediaS3RoundTripTests {

	private static final byte[] PAYLOAD = "whatsapp-clone-s3-probe".getBytes(StandardCharsets.UTF_8);
	private static final long TIMEOUT_SECONDS = 20;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private S3Client s3Client;

	@Value("${app.media.bucket}")
	private String bucket;

	@LocalServerPort
	private int port;

	private final RestTemplate rest = new RestTemplate();
	private final HttpClient http = HttpClient.newHttpClient();
	private final WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
	private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

	@Test
	@DisplayName("upload to S3, send it as a message, and let another member download it")
	void fullRoundTripThroughRealBucket() throws Exception {
		Account alice = register();
		Account bob = register();

		String objectKey = null;
		try {
			// 1. presign
			JsonNode upload = post("/api/media/upload-url",
					Map.of("contentType", "image/png", "sizeBytes", PAYLOAD.length), alice.token()).get("data");
			objectKey = upload.get("objectKey").asString();
			assertThat(objectKey).startsWith("media/" + alice.id() + "/");

			// 2. PUT the bytes with exactly the signed content type
			HttpResponse<Void> put = http.send(HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asString()))
					.header("Content-Type", upload.get("headers").get("Content-Type").asString())
					.PUT(HttpRequest.BodyPublishers.ofByteArray(PAYLOAD))
					.build(), HttpResponse.BodyHandlers.discarding());
			assertThat(put.statusCode())
					.as("the presigned URL must be permitted by the bucket policy")
					.isEqualTo(200);

			// 3. confirm -- the call that genuinely reads the object back out of S3
			JsonNode complete = post("/api/media/complete", Map.of("objectKey", objectKey), alice.token()).get("data");
			assertThat(complete.get("verified").asBoolean())
					.as("complete rejected it: %s", complete.get("reason").asString())
					.isTrue();
			assertThat(complete.get("sizeBytes").asLong()).isEqualTo(PAYLOAD.length);

			// 4. send it as a message over the socket
			UUID chatId = UUID.fromString(post("/api/chats", Map.of("memberIds", List.of(bob.id().toString())),
					alice.token()).get("data").get("chatId").asString());

			StompSession aliceSocket = connect(alice);
			aliceSocket.subscribe("/topic/user." + alice.id() + ".messages", collectingHandler());
			send(aliceSocket, """
					{"chatId":"%s","clientMessageId":"img-1","content":"look at this","type":"IMAGE","replyToId":null,"mediaKey":"%s"}
					""".formatted(chatId, objectKey));

			JsonNode message = poll("message echo");
			assertThat(message.get("type").asString()).isEqualTo("IMAGE");
			assertThat(message.get("content").asString())
					.as("the caption travels as content; the key does not")
					.isEqualTo("look at this");
			UUID messageId = UUID.fromString(message.get("id").asString());

			// 5. a member who did not upload it fetches the bytes
			JsonNode download = post("/api/media/download-url", Map.of("messageId", messageId), bob.token())
					.get("data");
			HttpResponse<byte[]> fetched = http.send(
					HttpRequest.newBuilder(URI.create(download.get("downloadUrl").asString())).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());

			assertThat(fetched.statusCode()).isEqualTo(200);
			assertThat(fetched.body())
					.as("the signed GET returns the bytes that were uploaded")
					.isEqualTo(PAYLOAD);

			// 6. a non-member is refused even though they know the message id
			Account outsider = register();
			assertThat(errorCodeOf(() -> post("/api/media/download-url", Map.of("messageId", messageId),
					outsider.token())))
					.as("download is authorised by chat membership, not by knowing the message id")
					.isEqualTo("NOT_CHAT_PARTICIPANT");
		}
		finally {
			if (objectKey != null) {
				s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
			}
		}
	}

	// --- harness --------------------------------------------------------------------

	private record Account(UUID id, String token) {
	}

	private Account register() {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		Random random = new Random();
		StringBuilder phone = new StringBuilder("+1415");
		for (int i = 0; i < 7; i++) {
			phone.append(random.nextInt(10));
		}
		JsonNode data = post("/api/auth/register",
				Map.of("phoneNumber", phone.toString(), "username", "user_" + suffix, "password", "correct-horse"),
				null).get("data");
		return new Account(UUID.fromString(data.get("user").get("id").asString()),
				data.get("accessToken").asString());
	}

	private StompSession connect(Account account) throws Exception {
		stompClient.setTaskScheduler(
				new org.springframework.scheduling.concurrent.ConcurrentTaskScheduler(
						Executors.newSingleThreadScheduledExecutor()));
		StompHeaders connectHeaders = new StompHeaders();
		connectHeaders.add("Authorization", "Bearer " + account.token());
		return stompClient.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null,
				connectHeaders, new StompSessionHandlerAdapter() {
				}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private StompFrameHandler collectingHandler() {
		return new StompSessionHandlerAdapter() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return byte[].class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				try {
					inbox.add(objectMapper.readTree((byte[]) payload));
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
		};
	}

	private void send(StompSession session, String json) {
		StompHeaders headers = new StompHeaders();
		headers.setDestination("/app/chat.send");
		headers.add("receipt", "s3-" + UUID.randomUUID());
		session.send(headers, json.getBytes(StandardCharsets.UTF_8));
	}

	private JsonNode poll(String what) throws InterruptedException {
		JsonNode frame = inbox.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(frame).as("timed out waiting for %s", what).isNotNull();
		return frame;
	}

	private String errorCodeOf(Runnable call) {
		try {
			call.run();
		}
		catch (org.springframework.web.client.HttpClientErrorException ex) {
			return objectMapper.readTree(ex.getResponseBodyAsString()).get("error").get("code").asString();
		}
		throw new AssertionError("expected the request to be rejected");
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