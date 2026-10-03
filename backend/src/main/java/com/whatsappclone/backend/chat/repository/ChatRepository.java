package com.whatsappclone.backend.chat.repository;

import com.whatsappclone.backend.chat.model.Chat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

	@Query("""
			select c from Chat c
			where c.id in (select cp.chat.id from ChatParticipant cp where cp.user.id = :userId)
			""")
	List<Chat> findAllForUser(@Param("userId") UUID userId, Pageable pageable);

	@Query("""
			select c from Chat c
			where c.id in (select cp.chat.id from ChatParticipant cp where cp.user.id = :userId)
			""")
	List<Chat> findAllForUser(@Param("userId") UUID userId);

	Optional<Chat> findByIdAndGroupFalse(UUID id);

	@Query("""
			select c from Chat c
			where c.group = false
			  and exists (select 1 from ChatParticipant a where a.chat = c and a.user.id = :firstUserId)
			  and exists (select 1 from ChatParticipant b where b.chat = c and b.user.id = :secondUserId)
			  and (select count(a) from ChatParticipant a where a.chat = c) = 2
			""")
	Optional<Chat> findDirectChatBetween(@Param("firstUserId") UUID firstUserId,
			@Param("secondUserId") UUID secondUserId);
}