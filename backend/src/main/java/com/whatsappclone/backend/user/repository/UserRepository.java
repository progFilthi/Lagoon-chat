package com.whatsappclone.backend.user.repository;

import com.whatsappclone.backend.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByPhoneNumber(String phoneNumber);

	Optional<User> findByUsername(String username);

	boolean existsByPhoneNumber(String phoneNumber);

	boolean existsByUsername(String username);

	List<User> findByIdIn(Collection<UUID> ids);

	@Query("""
			select u from User u
			where u.phoneNumberHash in :hashes
			""")
	List<User> findAllByPhoneNumberHashIn(@Param("hashes") Collection<String> hashes);

	@Query("""
			update User u
			set u.online = :online, u.lastSeen = :lastSeen
			where u.id = :id
			""")
	int updatePresence(@Param("id") UUID id, @Param("online") boolean online,
			@Param("lastSeen") Instant lastSeen);
}