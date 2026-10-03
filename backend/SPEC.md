# AI Assistant Instructions: WhatsApp Clone Backend Implementation

## Project Context
You are an expert Enterprise Java Architect. Your task is to build a complex, production-grade WhatsApp clone backend.
**Do not generate all the code at once.** I will ask you to execute this guide step-by-step. Acknowledge these instructions and wait for my command to begin Step 1.

## Tech Stack & Global Constraints
* **Core:** Java 25, Spring Boot 4.1.1
* **Database:** PostgreSQL via Spring Data JPA
* **Real-time:** Spring WebSockets (STOMP)
* **Security:** Spring Security
* **Utilities:** Lombok, Spring Validation

### Architectural Rules
1. **Separation of Concerns:** Keep `@RestController` and `@MessageMapping` controllers lean. Delegate all core business logic to `@Service` classes.
2. **Primary Keys:** Use `UUID` for all entity IDs to prevent ID guessing and support distributed generation.
3. **API Standardization:** Wrap all REST API responses in a standardized generic `ApiResponse<T>` record.
4. **Immutability:** Favor Java Records for DTOs and WebSocket payloads where mutation is not required.
5. **Dependency Management:** Use the existing `pom.xml`. If a specific utility is needed (e.g., `jjwt` for token generation), explicitly ask me to add it before generating code that relies on it.

---

## Execution Steps

### Step 1: Core Domain Model & JPA Repositories
Design and implement the core database entities with proper relational mappings, indexing, and auditing (`@CreatedDate`, `@LastModifiedDate`).
* **`User`**: `id` (UUID), `phoneNumber` (unique), `username`, `passwordHash`, `publicKey` (for future E2E encryption), `profilePictureUrl`, `lastSeen`, `isOnline`.
* **`Chat`**: `id` (UUID), `isGroup` (boolean), `groupName`, `createdAt`.
* **`ChatParticipant`**: `id` (UUID), `chat` (ManyToOne), `user` (ManyToOne), `role` (ADMIN, MEMBER), `joinedAt`.
* **`Message`**: `id` (UUID), `chat` (ManyToOne), `sender` (ManyToOne), `content` (encrypted payload), `type` (TEXT, IMAGE, VIDEO, AUDIO), `status` (SENT, DELIVERED, READ), `createdAt`.
* **Task:** Generate these entities and their corresponding `JpaRepository` interfaces.

### Step 2: Security & JWT Authentication Filter
Implement stateless session management using Spring Security.
* Configure `SecurityFilterChain` to disable CSRF and enforce stateless session creation.
* Create a `JwtService` for generating, signing, and validating tokens.
* Implement `AuthService` and `AuthController` for `/api/auth/register` and `/api/auth/login`.
* Create a WebSocket `ChannelInterceptor` to authenticate STOMP connections using the JWT token before the session is established.

### Step 3: REST API (Sync & Chat Initialization)
Create the HTTP endpoints required for initial app load and synchronization.
* **POST `/api/users/sync`**: Accepts a list of phone numbers (hashed) from the client's local contacts and returns the ones registered on the platform.
* **GET `/api/chats`**: Retrieves all active chats for the authenticated user, ordered by the latest message timestamp. Include unread message counts.
* **GET `/api/chats/{chatId}/messages`**: Fetches paginated message history using Spring Data `Pageable` (Keyset/Cursor pagination preferred).

### Step 4: WebSocket Configuration & Presence Tracking
Configure the real-time broker and user presence system.
* Configure `@EnableWebSocketMessageBroker`. Register endpoint at `/ws` (allow origins `*`).
* Map application destination prefix to `/app`.
* Map broker prefixes to `/topic` (group broadcasts) and `/queue` (private user queues).
* Implement `PresenceEventListener` listening to `SessionConnectedEvent` and `SessionDisconnectEvent`. Update `User.isOnline` in PostgreSQL and broadcast presence changes to the user's active chat participants.

### Step 5: Real-Time Messaging & Read Receipts
Implement the core chat functionality over WebSockets.
* Create `ChatController` with `@MessageMapping`.
* **Message Routing:** When a message is received, save it to PostgreSQL asynchronously, then broadcast it to the specific recipient queues (`/queue/user.{userId}`).
* **Receipts:** Listen for delivery/read status updates from clients and broadcast the status change back to the original sender's queue.