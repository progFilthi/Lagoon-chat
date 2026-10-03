package com.whatsappclone.backend.message.repository;

import com.whatsappclone.backend.message.model.MessageReceipt;
import com.whatsappclone.backend.message.model.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageReceiptRepository extends JpaRepository<MessageReceipt, UUID> {

	List<MessageReceipt> findByMessageId(UUID messageId);

	List<MessageReceipt> findByMessageIdIn(Collection<UUID> messageIds);

	@Query("""
			select r from MessageReceipt r
			where r.recipient.id = :userId
			  and r.message.id in :messageIds
			""")
	List<MessageReceipt> findForUserAndMessages(@Param("userId") UUID userId,
			@Param("messageIds") Collection<UUID> messageIds);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update MessageReceipt r
			set r.status = :status,
			    r.deliveredAt = case when r.deliveredAt is null then :now else r.deliveredAt end,
			    r.readAt = case when :status = :readStatus and r.readAt is null then :now else r.readAt end
			where r.message.id = :messageId
			  and r.recipient.id = :recipientId
			  and r.status <> :status
			""")
	int markStatus(@Param("messageId") UUID messageId, @Param("recipientId") UUID recipientId,
			@Param("status") MessageStatus status, @Param("readStatus") MessageStatus readStatus,
			@Param("now") Instant now);
}