package com.whatsappclone.backend.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The clients are built unconditionally, even with no bucket configured, so the application still
 * starts for local frontend work. Credentials resolve lazily, so an unconfigured or unreachable
 * environment only fails when a media endpoint is actually called.
 *
 * <p>Credentials are taken from the Spring {@code Environment} when present rather than left to the
 * SDK's default chain. That is not a stylistic choice: the chain reads <em>system properties and OS
 * environment variables</em>, which a value imported through {@code spring.config.import} never
 * reaches — so credentials in {@code .env} would be visible to Spring and invisible to the SDK,
 * failing at the first presign with "Unable to load credentials from any of the providers in the
 * chain". Reading them here closes that gap. When they are absent the default chain is used, which
 * is what keeps instance roles, web identity and shared profiles working in production.
 */
@Configuration
public class MediaConfig {

	@Bean
	public S3Client s3Client(@Value("${AWS_ACCESS_KEY_ID:}") String accessKeyId,
			@Value("${AWS_SECRET_ACCESS_KEY:}") String secretAccessKey,
			@Value("${app.media.region}") String region) {
		return S3Client.builder()
				.region(Region.of(region))
				.credentialsProvider(credentialsProvider(accessKeyId, secretAccessKey))
				.build();
	}

	@Bean
	public S3Presigner s3Presigner(@Value("${AWS_ACCESS_KEY_ID:}") String accessKeyId,
			@Value("${AWS_SECRET_ACCESS_KEY:}") String secretAccessKey,
			@Value("${app.media.region}") String region) {
		return S3Presigner.builder()
				.region(Region.of(region))
				.credentialsProvider(credentialsProvider(accessKeyId, secretAccessKey))
				.build();
	}

	private static AwsCredentialsProvider credentialsProvider(String accessKeyId, String secretAccessKey) {
		if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
		}
		return DefaultCredentialsProvider.create();
	}

	/** Bucket names are lower-case and DNS-compatible; anything else is a configuration mistake. */
	static boolean isValidBucketName(String bucket) {
		return StringUtils.hasText(bucket) && bucket.length() >= 3 && bucket.length() <= 63
				&& bucket.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]");
	}
}