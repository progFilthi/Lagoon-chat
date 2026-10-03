package com.whatsappclone.backend.chat.repository;

import com.whatsappclone.backend.chat.model.ChatParticipant;
import com.whatsappclone.backend.chat.repository.projection.UnreadCount;
import com.whatsappclone.backend.message.model.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, UUID> {

	Optional<ChatParticipant> findByChatIdAndUserId(UUID chatId, UUID userId);

	boolean existsByChatIdAndUserId(UUID chatId, UUID userId);

	List<ChatParticipant> findByChatId(UUID chatId);

	List<ChatParticipant> findByUserId(UUID userId);

	List<ChatParticipant> findByChatIdIn(List<UUID> chatIds);

	@Query("select cp.user.id from ChatParticipant cp where cp.chat.id = :chatId and cp.user.id <> :excludeUserId")
	List<UUID> findOtherMemberIds(@Param("chatId") UUID chatId, @Param("excludeUserId") UUID excludeUserId);

	@Query("select cp.user.id from ChatParticipant cp where cp.chat.id in :chatIds")
	List<UUID> findUserIdsForChats(@Param("chatIds") List<UUID> chatIds);

	@Query("""
			select r.message.chat.id as chatId, count(r.id) as unreadCount
			from MessageReceipt r
			where r.recipient.id = :userId and r.status <> :readStatus
			group by r.message.chat.id
			""")
	List<UnreadCount> countUnreadByChat(@Param("userId") UUID userId,
			@Param("readStatus") MessageStatus readStatus);
}