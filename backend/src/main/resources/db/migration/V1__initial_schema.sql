CREATE TABLE users (
    id                  UUID         PRIMARY KEY,
    phone_number        VARCHAR(20)  NOT NULL,
    phone_number_hash   VARCHAR(64)  NOT NULL,
    username            VARCHAR(32)  NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    public_key          TEXT,
    profile_picture_url VARCHAR(500),
    about               VARCHAR(140),
    last_seen           TIMESTAMPTZ,
    is_online           BOOLEAN      NOT NULL DEFAULT FALSE,
    is_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_phone_number UNIQUE (phone_number),
    CONSTRAINT uq_users_phone_number_hash UNIQUE (phone_number_hash),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE INDEX idx_users_phone_number_hash ON users (phone_number_hash);

CREATE TABLE chats (
    id               UUID         PRIMARY KEY,
    is_group         BOOLEAN      NOT NULL DEFAULT FALSE,
    group_name       VARCHAR(120),
    group_avatar_url VARCHAR(500),
    created_by       UUID         REFERENCES users (id) ON DELETE SET NULL,
    last_message_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_chats_group_name CHECK (is_group OR group_name IS NULL)
);

CREATE INDEX idx_chats_last_message_at ON chats (last_message_at DESC);

CREATE TABLE chat_participants (
    id           UUID        PRIMARY KEY,
    chat_id      UUID        NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(20) NOT NULL,
    joined_at    TIMESTAMPTZ NOT NULL,
    last_read_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_chat_participants_chat_user UNIQUE (chat_id, user_id),
    CONSTRAINT ck_chat_participants_role CHECK (role IN ('ADMIN', 'MEMBER'))
);

CREATE INDEX idx_chat_participants_user ON chat_participants (user_id);
CREATE INDEX idx_chat_participants_chat ON chat_participants (chat_id);

CREATE TABLE messages (
    id                UUID        PRIMARY KEY,
    chat_id           UUID        NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    sender_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_message_id VARCHAR(64) NOT NULL,
    content           TEXT        NOT NULL,
    type              VARCHAR(20) NOT NULL,
    reply_to_id       UUID        REFERENCES messages (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_messages_sender_client UNIQUE (sender_id, client_message_id),
    CONSTRAINT ck_messages_type CHECK (type IN ('TEXT', 'IMAGE', 'VIDEO', 'AUDIO'))
);

CREATE INDEX idx_messages_chat_created ON messages (chat_id, created_at DESC, id DESC);
CREATE INDEX idx_messages_sender_created ON messages (sender_id, created_at DESC);

CREATE TABLE message_receipts (
    id           UUID        PRIMARY KEY,
    message_id   UUID        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       VARCHAR(20) NOT NULL,
    delivered_at TIMESTAMPTZ,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_message_receipts_message_user UNIQUE (message_id, user_id),
    CONSTRAINT ck_message_receipts_status CHECK (status IN ('SENT', 'DELIVERED', 'READ')),
    CONSTRAINT ck_message_receipts_read_at CHECK (status <> 'READ' OR read_at IS NOT NULL)
);

CREATE INDEX idx_message_receipts_user_status ON message_receipts (user_id, status);
CREATE INDEX idx_message_receipts_message ON message_receipts (message_id);