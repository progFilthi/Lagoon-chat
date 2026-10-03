package com.whatsappclone.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeContractTests {

	private static final long TIMEOUT_SECONDS = 15;
	private static final Random RANDOM = new Random();

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	private final WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
	private final RestTemplate rest = new RestTemplate();
	private final Map<String, BlockingQueue<JsonNode>> received = new ConcurrentHashMap<>();
	private final List<StompSession> sessions = new ArrayList<>();

	@BeforeEach
	void setUp() {
		// StompSession.send tracks receipts, which refuses to run without a scheduler. The test
		// correlates on its own receipt header, but the client still insists on one existing.
		stompClient.setTaskScheduler(new ConcurrentTaskScheduler(Executors.newSingleThreadScheduledExecutor()));
		received.clear();
		sessions.clear();
	}

	@AfterEach
	void tearDown() {
		sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
	}

	@Test
	@DisplayName("a recipient can render the message from the broadcast alone")
	void recipientReceivesRenderableMessage() throws Exception {
		Account alice = register();
		Account bob = register();
		UUID chatId = createDirectChat(alice, bob);

		StompSession bobSocket = connect(bob);
		BlockingQueue<JsonNode> bobInbox = subscribe(bobSocket, userTopic(bob, "messages"));

		StompSession aliceSocket = connect(alice);
		BlockingQueue<JsonNode> aliceInbox = subscribe(aliceSocket, userTopic(alice, "messages"));

		awaitSubscriptions(bobSocket, bob, chatId);
		awaitSubscriptions(aliceSocket, alice, chatId);

		send(aliceSocket, "/app/chat.send", "rcpt-1", """
				{"chatId":"%s","clientMessageId":"local-1","content":"hello bob","type":"TEXT","replyToId":null}
				""".formatted(chatId));

		JsonNode delivered = pollMessage(bobInbox, "recipient");
		assertThat(delivered.get("content").asString())
				.as("the broadcast must carry content, otherwise the recipient cannot render it")
				.isEqualTo("hello bob");
		assertThat(delivered.get("senderId").asString())
				.as("the broadcast must identify the sender")
				.isEqualTo(alice.id().toString());
		assertThat(delivered.get("id").asString())
				.as("the broadcast uses the same id field as history")
				.isNotBlank();
		assertThat(delivered.has("messageId"))
				.as("the divergent messageId field is gone")
				.isFalse();

		JsonNode echoed = pollMessage(aliceInbox, "sender");
		assertThat(echoed.get("clientMessageId").asString()).isEqualTo("local-1");
		assertThat(echoed.get("receipts").get("total").asInt())
				.as("the sender sees one pending receipt for the one recipient")
				.isEqualTo(1);
		assertThat(echoed.get("content").asString())
				.as("the sender's echo carries content too, so both sides share one shape")
				.isEqualTo("hello bob");
	}

	@Test
	@DisplayName("a rejected frame is reported to the sender and the socket survives")
	void rejectedFrameReportsErrorAndKeepsSocketOpen() throws Exception {
		Account alice = register();
		Account bob = register();
		UUID chatId = createDirectChat(alice, bob);

		StompSession aliceSocket = connect(alice);
		BlockingQueue<JsonNode> errors = subscribe(aliceSocket, userTopic(alice, "errors"));

		awaitSubscriptions(aliceSocket, alice, chatId);

		send(aliceSocket, "/app/chat.send", "rcpt-bad", """
				{"chatId":"%s","clientMessageId":"local-bad","content":"nope","type":"STICKER","replyToId":null}
				""".formatted(chatId));

		JsonNode error = poll(errors, "error report");
		assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
		assertThat(error.get("receipt").asString())
				.as("the client's receipt header is echoed so the failure can be attributed to one message")
				.isEqualTo("rcpt-bad");
		assertThat(error.get("destination").asString()).isEqualTo("/app/chat.send");

		assertThat(aliceSocket.isConnected())
				.as("the socket must still be open after a rejected frame")
				.isTrue();

		StompSession bobSocket = connect(bob);
		BlockingQueue<JsonNode> bobInbox = subscribe(bobSocket, userTopic(bob, "messages"));
		awaitSubscriptions(bobSocket, bob, chatId);

		send(aliceSocket, "/app/chat.send", "rcpt-good", """
				{"chatId":"%s","clientMessageId":"local-good","content":"still here","type":"TEXT","replyToId":null}
				""".formatted(chatId));

		assertThat(pollMessage(bobInbox, "post-rejection delivery").get("content").asString())
				.isEqualTo("still here");
	}

	@Test
	@DisplayName("marking a chat read tells the user's other sessions")
	void markingReadBroadcastsReadState() throws Exception {
		Account alice = register();
		Account bob = register();
		UUID chatId = createDirectChat(alice, bob);

		StompSession aliceSocket = connect(alice);
		BlockingQueue<JsonNode> readStates = subscribe(aliceSocket, userTopic(alice, "read-state"));

		awaitSubscriptions(aliceSocket, alice, chatId);

		post("/api/chats/" + chatId + "/read", null, alice.token());

		JsonNode event = poll(readStates, "read-state event");
		assertThat(event.get("chatId").asString()).isEqualTo(chatId.toString());
		assertThat(event.get("userId").asString()).isEqualTo(alice.id().toString());
		assertThat(event.get("lastReadAt").asString()).isNotBlank();
	}

	@Test
	@DisplayName("a replayed clientMessageId does not create a duplicate")
	void replayedSendIsIdempotent() throws Exception {
		Account alice = register();
		Account bob = register();
		UUID chatId = createDirectChat(alice, bob);

		StompSession aliceSocket = connect(alice);
		BlockingQueue<JsonNode> inbox = subscribe(aliceSocket, userTopic(alice, "messages"));

		String payload = """
				{"chatId":"%s","clientMessageId":"retry-me","content":"once","type":"TEXT","replyToId":null}
				""".formatted(chatId);

		send(aliceSocket, "/app/chat.send", "r1", payload);
		JsonNode first = pollMessage(inbox, "first send");
		send(aliceSocket, "/app/chat.send", "r2", payload);
		JsonNode second = pollMessage(inbox, "replayed send");

		assertThat(second.get("id").asString())
				.as("a retry after a reconnect must resolve to the original message")
				.isEqualTo(first.get("id").asString());

		JsonNode history = get("/api/chats/" + chatId + "/messages", alice.token());
		assertThat(history.get("data").get("content").size())
				.as("only one message is stored")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("an attachment key must have been issued to the sender")
	void mediaKeyMustBelongToSender() throws Exception {
		Account alice = register();
		Account bob = register();
		UUID chatId = createDirectChat(alice, bob);

		StompSession aliceSocket = connect(alice);
		BlockingQueue<JsonNode> errors = subscribe(aliceSocket, userTopic(alice, "errors"));
		BlockingQueue<JsonNode> inbox = subscribe(aliceSocket, userTopic(alice, "messages"));

		awaitSubscriptions(aliceSocket, alice, chatId);

		// Bob's key, posted by Alice. Ownership is judged from the key's own prefix, so this is
		// refused without any S3 call -- otherwise a sender could attach a file they never had access
		// to and have the whole chat download it.
		send(aliceSocket, "/app/chat.send", "r-foreign", """
				{"chatId":"%s","clientMessageId":"foreign","content":"","type":"IMAGE","mediaKey":"media/%s/x.png"}
				""".formatted(chatId, bob.id()));

		assertThat(poll(errors, "foreign media key").get("code").asString()).isEqualTo("VALIDATION_ERROR");

		// Her own key is accepted, and no S3 call is involved in persisting the reference.
		send(aliceSocket, "/app/chat.send", "r-own", """
				{"chatId":"%s","clientMessageId":"own","content":"","type":"IMAGE","mediaKey":"media/%s/mine.png"}
				""".formatted(chatId, alice.id()));

		JsonNode stored = pollMessage(inbox, "own media key");
		assertThat(stored.get("type").asString()).isEqualTo("IMAGE");
		assertThat(stored.get("content").asString())
				.as("an attachment has no text body, and the key is never leaked into content")
				.isEmpty();
	}

	@Test
	@DisplayName("a TEXT message needs a body and refuses an attachment")
	void textMessageInvariants() throws Exception {
		Account alice = register();
		Account bob = register();
		UUID chatId = createDirectChat(alice, bob);

		StompSession aliceSocket = connect(alice);
		BlockingQueue<JsonNode> errors = subscribe(aliceSocket, userTopic(alice, "errors"));

		awaitSubscriptions(aliceSocket, alice, chatId);

		send(aliceSocket, "/app/chat.send", "r-empty", """
				{"chatId":"%s","clientMessageId":"empty","content":"","type":"TEXT","replyToId":null}
				""".formatted(chatId));
		assertThat(poll(errors, "empty text body").get("code").asString()).isEqualTo("VALIDATION_ERROR");

		send(aliceSocket, "/app/chat.send", "r-key", """
				{"chatId":"%s","clientMessageId":"withkey","content":"hi","type":"TEXT","mediaKey":"media/%s/x.png"}
				""".formatted(chatId, alice.id()));
		assertThat(poll(errors, "text with a mediaKey").get("code").asString()).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	@DisplayName("every user-facing endpoint returns the same user shape")
	void userShapeIsUniform() {
		Account alice = register();

		JsonNode me = get("/api/users/me", alice.token()).get("data");
		JsonNode byId = get("/api/users/" + alice.id(), alice.token()).get("data");
		JsonNode synced = post("/api/users/sync", Map.of("phoneNumbers", List.of(alice.phoneNumber())), alice.token())
				.get("data");
		JsonNode login = post("/api/auth/login",
				Map.of("phoneNumber", alice.phoneNumber(), "password", "correct-horse"), null).get("data");

		assertThat(List.of(me, byId)).allSatisfy(this::assertUserShape);
		assertUserShape(login.get("user"));
		assertThat(synced).as("sync resolves a registered number").hasSize(1);
		assertUserShape(synced.get(0));
		assertThat(synced.get(0).get("id").asString())
				.as("sync returns the same shape, not a stripped-down contact")
				.isEqualTo(alice.id().toString());
	}

	private void assertUserShape(JsonNode node) {
		assertThat(node.has("id")).isTrue();
		assertThat(node.has("username")).isTrue();
		assertThat(node.has("phoneNumber")).isTrue();
		assertThat(node.has("about")).isTrue();
		assertThat(node.has("online"))
				.as("every user shape carries presence, so a client needs one type")
				.isTrue();
		assertThat(node.has("lastSeen")).isTrue();
	}

	// --- Harness -----------------------------------------------------------------------

	private record Account(UUID id, String token, String phoneNumber) {
	}

	private Account register() {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String phoneNumber = phoneNumber();
		JsonNode data = post("/api/auth/register",
				Map.of("phoneNumber", phoneNumber, "username", "user_" + suffix, "password", "correct-horse"),
				null).get("data");
		return new Account(UUID.fromString(data.get("user").get("id").asString()),
				data.get("accessToken").asString(), phoneNumber);
	}

	/** {@code +} then 11 digits, which satisfies the backend's E.164 pattern. */
	private static String phoneNumber() {
		StringBuilder digits = new StringBuilder("+1415");
		for (int i = 0; i < 7; i++) {
			digits.append(RANDOM.nextInt(10));
		}
		return digits.toString();
	}

	private UUID createDirectChat(Account creator, Account other) {
		JsonNode data = post("/api/chats", Map.of("memberIds", List.of(other.id().toString())), creator.token())
				.get("data");
		return UUID.fromString(data.get("chatId").asString());
	}

	private StompSession connect(Account account) throws Exception {
		StompHeaders connectHeaders = new StompHeaders();
		connectHeaders.add("Authorization", "Bearer " + account.token());
		StompSession session = stompClient
				.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, connectHeaders,
						new StompSessionHandlerAdapter() {
						})
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		sessions.add(session);
		return session;
	}

	private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
		BlockingQueue<JsonNode> queue = received.computeIfAbsent(destination, key -> new LinkedBlockingQueue<>());
		session.subscribe(destination, new StompSessionHandlerAdapter() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return byte[].class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				try {
					queue.add(objectMapper.readTree((byte[]) payload));
				}
				catch (Exception ex) {
					throw new IllegalStateException("could not read frame on " + destination, ex);
				}
			}
		});
		return queue;
	}

private static final String BARRIER_PREFIX = "barrier-";

	/**
	 * Proves that every subscription made on this session so far is live on the server.
	 *
	 * <p>STOMP frames on a single connection are processed in order, so once a frame published
	 * <em>after</em> our SUBSCRIBEs has made the round trip back to us, the broker must already have
	 * registered them. Without this a SEND can overtake its own SUBSCRIBE and the resulting broadcast
	 * is silently dropped — which showed up as an error report that was intermittently missing only
	 * when the whole class ran together.
	 *
	 * <p>The barrier is itself an ordinary message, so it also arrives in every other member's queue.
	 * {@link #pollMessage} skips these.
	 */
	private void awaitSubscriptions(StompSession session, Account account, UUID chatId) throws Exception {
		BlockingQueue<JsonNode> inbox = subscribe(session, userTopic(account, "messages"));
		String marker = BARRIER_PREFIX + UUID.randomUUID();
		send(session, "/app/chat.send", "barrier", """
				{"chatId":"%s","clientMessageId":"%s","content":"barrier","type":"TEXT","replyToId":null}
				""".formatted(chatId, marker));
		poll(inbox, "subscription barrier");
	}

	/** Polls for a real message, discarding barrier frames left by {@link #awaitSubscriptions}. */
	private JsonNode pollMessage(BlockingQueue<JsonNode> queue, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline) {
			JsonNode frame = queue.poll(1, TimeUnit.SECONDS);
			if (frame == null) {
				continue;
			}
			JsonNode clientMessageId = frame.get("clientMessageId");
			if (clientMessageId != null && clientMessageId.asString().startsWith(BARRIER_PREFIX)) {
				continue;
			}
			return frame;
		}
		throw new AssertionError("timed out waiting for " + what);
	}

	/**
	 * Sends pre-serialised JSON as bytes, which is what the WebSocket STOMP codec requires, and
	 * sets a {@code receipt} header so a rejected frame can be correlated back to this send.
	 */
	private void send(StompSession session, String destination, String receipt, String json) {
		StompHeaders headers = new StompHeaders();
		headers.setDestination(destination);
		headers.add("receipt", receipt);
		session.send(headers, json.getBytes(StandardCharsets.UTF_8));
	}

	private JsonNode poll(BlockingQueue<JsonNode> queue, String what) throws InterruptedException {
		JsonNode frame = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(frame).as("timed out waiting for %s", what).isNotNull();
		return frame;
	}

	private static String userTopic(Account account, String channel) {
		return "/topic/user." + account.id() + "." + channel;
	}

	private JsonNode post(String path, Object body, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers));
	}

	private JsonNode get(String path, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return exchange(path, HttpMethod.GET, new HttpEntity<>(headers));
	}

	private JsonNode exchange(String path, HttpMethod method, HttpEntity<?> request) {
		ResponseEntity<String> response = rest.exchange(url(path), method, request, String.class);
		assertThat(response.getStatusCode().is2xxSuccessful())
				.as("%s %s -> %s: %s", method, path, response.getStatusCode(), response.getBody())
				.isTrue();
		return objectMapper.readTree(response.getBody());
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}