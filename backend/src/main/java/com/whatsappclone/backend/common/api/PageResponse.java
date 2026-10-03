package com.whatsappclone.backend.common.api;

import java.util.List;

public record PageResponse<T>(List<T> content, int size, boolean hasMore, String nextCursor) {

	public static <T> PageResponse<T> of(List<T> content, int size, boolean hasMore, String nextCursor) {
		return new PageResponse<>(content, size, hasMore, hasMore ? nextCursor : null);
	}

	public static <T> PageResponse<T> of(List<T> content, int size) {
		return new PageResponse<>(content, size, false, null);
	}
}