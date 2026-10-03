package com.whatsappclone.backend.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SyncContactsRequest(

		@NotEmpty(message = "phoneNumbers must not be empty")
		@Size(max = 5000, message = "at most 5000 phone numbers can be synced at once")
		List<@Valid @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
				message = "every phone number must be E.164, e.g. +14155550100") String> phoneNumbers) {
}