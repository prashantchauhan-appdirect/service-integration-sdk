package com.appdirect.sdk.meteredusage.service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.appdirect.sdk.appmarket.DeveloperSpecificAppmarketCredentialsSupplier;
import com.appdirect.sdk.appmarket.events.APIResult;
import com.appdirect.sdk.appmarket.events.ErrorCode;
import com.appdirect.sdk.meteredusage.MeteredUsageApi;
import com.appdirect.sdk.meteredusage.config.OAuth1RetrofitWrapper;
import com.appdirect.sdk.meteredusage.exception.MeterUsageServiceException;
import com.appdirect.sdk.meteredusage.exception.MeteredUsageApiException;
import com.appdirect.sdk.meteredusage.exception.ServiceException;
import com.appdirect.sdk.meteredusage.model.MeteredUsageItem;
import com.appdirect.sdk.meteredusage.model.MeteredUsageRequest;
import com.appdirect.sdk.meteredusage.model.MeteredUsageResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.gdata.util.common.base.Preconditions;
import retrofit2.Response;

@Slf4j
@Service
public class MeteredUsageApiClientServiceImpl implements MeteredUsageApiClientService {
	private final static String IDEMPOTENCY_KEY_ALREADY_SHARED = "Entry ALREADY exists with idempotencyKey";

	private final DeveloperSpecificAppmarketCredentialsSupplier credentialsSupplier;
	private final OAuth1RetrofitWrapper oAuth1RetrofitWrapper;

	@Autowired
	public MeteredUsageApiClientServiceImpl(DeveloperSpecificAppmarketCredentialsSupplier credentialsSupplier, OAuth1RetrofitWrapper oAuth1RetrofitWrapper) {
		this.credentialsSupplier = credentialsSupplier;
		this.oAuth1RetrofitWrapper = oAuth1RetrofitWrapper;
	}

	@Override
	public APIResult reportUsage(String baseUrl, String secretKey, String idempotentKey, MeteredUsageItem meteredUsageItem, boolean billable) {
		return reportUsage(baseUrl, secretKey, idempotentKey, Collections.singletonList(meteredUsageItem), billable);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String secretKey, MeteredUsageItem meteredUsageItem, boolean billable) {
		return reportUsage(baseUrl, secretKey, UUID.randomUUID().toString(), Collections.singletonList(meteredUsageItem), billable);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String secretKey, MeteredUsageItem meteredUsageItem, boolean billable, String sourceType) {
		return reportUsage(baseUrl, secretKey, UUID.randomUUID().toString(), Collections.singletonList(meteredUsageItem), billable, sourceType);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String secretKey, List<MeteredUsageItem> meteredUsageItems, boolean billable) {
		return reportUsage(baseUrl, secretKey, UUID.randomUUID().toString(), meteredUsageItems, billable);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String secretKey, String idempotentKey, List<MeteredUsageItem> meteredUsageItems, boolean billable) {
		return reportUsage(baseUrl, UUID.randomUUID().toString(), meteredUsageItems, billable, secretKey, credentialsSupplier.getConsumerCredentials(secretKey).developerSecret, org.apache.commons.lang3.StringUtils.EMPTY);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String secretKey, String idempotentKey, List<MeteredUsageItem> meteredUsageItems, boolean billable, String sourceType) {
		return reportUsage(baseUrl, UUID.randomUUID().toString(), meteredUsageItems, billable, secretKey, credentialsSupplier.getConsumerCredentials(secretKey).developerSecret, sourceType);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String idempotentKey, MeteredUsageItem meteredUsageItem, boolean billable, String secretKey, String secret) {
		return reportUsage(baseUrl, idempotentKey, Collections.singletonList(meteredUsageItem), billable, secretKey, secret, org.apache.commons.lang3.StringUtils.EMPTY);
	}

	@Override
	public APIResult reportUsage(String baseUrl, MeteredUsageItem meteredUsageItem, boolean billable, String secretKey, String secret) {
		return reportUsage(baseUrl, UUID.randomUUID().toString(), Collections.singletonList(meteredUsageItem), billable, secretKey, secret, org.apache.commons.lang3.StringUtils.EMPTY);
	}

	@Override
	public APIResult reportUsage(String baseUrl, List<MeteredUsageItem> meteredUsageItems, boolean billable, String secretKey, String secret) {
		return reportUsage(baseUrl, UUID.randomUUID().toString(), meteredUsageItems, billable, secretKey, secret, org.apache.commons.lang3.StringUtils.EMPTY);
	}

	@Override
	public APIResult reportUsage(String baseUrl, MeteredUsageItem meteredUsageItem, boolean billable, String secretKey, String secret, String sourceType) {
		return reportUsage(baseUrl, UUID.randomUUID().toString(), Collections.singletonList(meteredUsageItem), billable, secretKey, secret, sourceType);
	}

	@Override
	public APIResult reportUsage(String baseUrl, String idempotentKey, List<MeteredUsageItem> meteredUsageItems, boolean billable, String secretKey, String secret, String sourceType) {
		Preconditions.checkArgument(!StringUtils.isEmpty(baseUrl), "Base URL must not be empty");
		Preconditions.checkArgument(!StringUtils.isEmpty(secretKey), "Secret Key must not be empty");
		Preconditions.checkArgument(!StringUtils.isEmpty(idempotentKey), "IdempotentKey must not be empty");
		Preconditions.checkArgument(!CollectionUtils.isEmpty(meteredUsageItems), "Usage data to report must not be empty");

		// Create Request
		MeteredUsageRequest meteredUsageRequest = createMeteredUsageRequest(idempotentKey, meteredUsageItems, billable, sourceType);

		// Create API
		MeteredUsageApi meteredUsageApi = createMeteredUsageApi(baseUrl, secretKey, secret);

		try {
			return processResponse(meteredUsageApi.meteredUsageCall(meteredUsageRequest).execute());
		} catch (IOException e) {
			log.error("Metered Usage API Client failed with exception={}", e.getMessage(), e);
			throw new MeteredUsageApiException(String.format("Failed to inform Usage with errorCode=%s, message=%s", ErrorCode.UNKNOWN_ERROR, e.getMessage()), e);
		}
	}

	public APIResult retryableReportUsage(String baseUrl, String idempotentKey, List<MeteredUsageItem> meteredUsageItems, String secretKey, boolean billable, String sourceType) {
		APIResult apiResult = reportUsage(baseUrl, idempotentKey, meteredUsageItems, billable, secretKey, credentialsSupplier.getConsumerCredentials(secretKey).developerSecret, sourceType);
		if (!apiResult.isSuccess()) {
			log.warn("Failed to inform Usage idempotentKey={}, billable={} with errorCode={}, message={}", idempotentKey, billable, apiResult.getResponseCode(), apiResult.getMessage());
			if (!StringUtils.isEmpty(apiResult.getMessage()) && apiResult.getMessage().contains(IDEMPOTENCY_KEY_ALREADY_SHARED)) {
				log.error("Response is already shared with meterusage with idempotecncy key {}", idempotentKey);
				throw new MeterUsageServiceException(apiResult.getResponseCode(), apiResult.getMessage());
			}
			throw new ServiceException(apiResult.getMessage(), apiResult.getResponseCode());
		}
		return apiResult;
	}

	@VisibleForTesting
	MeteredUsageApi createMeteredUsageApi(String baseUrl, String secretKey, String secret) {
		return oAuth1RetrofitWrapper
			.baseUrl(baseUrl)
			.sign(secretKey, secret)
			.build()
			.create(MeteredUsageApi.class);
	}

	private APIResult processResponse(Response<MeteredUsageResponse> response) throws IOException{
		if (response.isSuccessful()) {
			return new APIResult(true, response.body().toString());
		}
		String errorBodyMessage = null;
		if (response.errorBody() != null) {
			errorBodyMessage = response.errorBody().string();
		}
		String errorMessage = !StringUtils.isEmpty(errorBodyMessage) ? response.message().concat(" ".concat(errorBodyMessage)) : response.message();
		log.error("Metered Usage API Client failed with error={}", errorMessage);
		return APIResult.failure(response.code(), String.format("Failed to inform Usage with errorCode=%s, message=%s", response.code(), errorMessage));
	}

	private MeteredUsageRequest createMeteredUsageRequest(String idempotentKey, List<MeteredUsageItem> meteredUsageItem, boolean billable, String sourceType) {
		return MeteredUsageRequest.builder()
			.idempotencyKey(idempotentKey)
			.billable(billable)
			.usages(meteredUsageItem)
			.sourceType(sourceType)
			.build();
	}
}
