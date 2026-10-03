package com.whatsappclone.backend.message.repository;

import com.whatsappclone.backend.message.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

	Optional<Message> findBySenderIdAndClientMessageId(UUID senderId, String clientMessageId);

	/**
	 * Keyset (cursor) pagination walking backwards through history. The cursor is the
	 * {@code (createdAt, id)} pair of the oldest message already seen; ties on createdAt are
	 * broken by id so the page boundary is stable.
	 */
	@Query("""
			select m from Message m
			where m.chat.id = :chatId
			  and (:cursorCreatedAt is null
			       or m.createdAt < :cursorCreatedAt
			       or (m.createdAt = :cursorCreatedAt and m.id < :cursorId))
			order by m.createdAt desc, m.id desc
			""")
	List<Message> findHistoryBefore(@Param("chatId") UUID chatId,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorId") UUID cursorId,
			Pageable pageable);

	@Query("""
			select m from Message m
			where m.chat.id = :chatId
			order by m.createdAt desc, m.id desc
			""")
	List<Message> findLatestInChat(@Param("chatId") UUID chatId, Pageable pageable);

	@Query("""
			select max(m.createdAt) from Message m
			where m.chat.id = :chatId
			""")
	Instant findLatestMessageTime(@Param("chatId") UUID chatId);
}