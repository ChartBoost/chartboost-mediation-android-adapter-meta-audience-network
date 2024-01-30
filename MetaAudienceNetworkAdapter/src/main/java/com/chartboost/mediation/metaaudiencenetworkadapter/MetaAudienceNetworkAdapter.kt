/*
 * Copyright 2023-2024 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.metaaudiencenetworkadapter

import android.content.Context
import android.util.Size
import android.view.View
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.chartboost.mediation.metaaudiencenetworkadapter.BuildConfig.CHARTBOOST_MEDIATION_META_AUDIENCE_NETWORK_ADAPTER_VERSION
import com.facebook.ads.*
import com.facebook.ads.Ad
import com.facebook.ads.BuildConfig.VERSION_NAME
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Collections.emptyMap
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Meta Audience Network adapter.
 */
class MetaAudienceNetworkAdapter : PartnerAdapter {
    companion object {
        /**
         * Test mode flag that can optionally be set to true to enable test ads. It can be set at any
         * time and it will take effect for the next ad request. Remember to set this to false in
         * production.
         */
        var testMode = false
            set(value) {
                field = value
                AdSettings.setTestMode(value)
                PartnerLogController.log(
                    CUSTOM,
                    "Meta Audience Network test mode is ${
                        if (value) {
                            "enabled. Remember to disable it before publishing."
                        } else {
                            "disabled."
                        }
                    }",
                )
            }

        /**
         * List of placement IDs that can optionally be set for initialization purposes.
         * If this list should be set, it must be set before initializing the Chartboost Mediation SDK.
         */
        var placementIds = listOf<String>()
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Meta Audience Network placement IDs " +
                        if (value.isEmpty()) {
                            "not provided for initialization."
                        } else {
                            "provided for initialization: ${value.joinToString()}."
                        },
                )
            }

        /**
         * Convert a given Meta Audience Network error code into a [ChartboostMediationError].
         *
         * @param error The Meta [AdError] to convert.
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: Int) =
            when (error) {
                AdError.NO_FILL_ERROR_CODE -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
                AdError.NETWORK_ERROR_CODE -> ChartboostMediationError.CM_NO_CONNECTIVITY
                AdError.SERVER_ERROR_CODE -> ChartboostMediationError.CM_AD_SERVER_ERROR
                AdError.INTERSTITIAL_AD_TIMEOUT -> ChartboostMediationError.CM_LOAD_FAILURE_TIMEOUT
                AdError.LOAD_TOO_FREQUENTLY_ERROR_CODE -> ChartboostMediationError.CM_LOAD_FAILURE_RATE_LIMITED
                AdError.BROKEN_MEDIA_ERROR_CODE -> ChartboostMediationError.CM_SHOW_FAILURE_MEDIA_BROKEN
                AdError.LOAD_CALLED_WHILE_SHOWING_AD -> ChartboostMediationError.CM_LOAD_FAILURE_SHOW_IN_PROGRESS
                else -> ChartboostMediationError.CM_PARTNER_ERROR
            }

        /**
         * Lambda to be called for a successful Meta Audience Network interstitial ad show.
         */
        internal var onInterstitialAdShowSuccess: () -> Unit = {}

        /**
         * Lambda to be called for a failed Meta Audience Network interstitial ad show.
         */
        internal var onInterstitialAdShowFailure: () -> Unit = {}
    }

    /**
     * Get the Meta Audience Network SDK version.
     */
    override val partnerSdkVersion: String
        get() = VERSION_NAME

    /**
     * Get the Meta Audience Network adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion: String
        get() = CHARTBOOST_MEDIATION_META_AUDIENCE_NETWORK_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "facebook"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Meta Audience Network"

    /**
     * Initialize the Meta Audience Network SDK so that it's ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration A map of relevant data that can be used for initialization purposes.
     *
     * @return Result.success() if the initialization was successful, otherwise Result.failure(Exception).
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Unit>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            AudienceNetworkAds
                .buildInitSettings(context.applicationContext)
                .withMediationService("Chartboost $adapterVersion")
                .withInitListener { result ->
                    resumeOnce(getInitResult(result))
                }
                .withPlacementIds(placementIds)
                .initialize()
        }
    }

    /**
     * Notify the Meta Audience Network SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
        )

        // NO-OP. Meta Audience Network internally handles GDPR.
    }

    /**
     * Notify Meta Audience Network of the CCPA compliance.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        AdSettings.setDataProcessingOptions(
            if (hasGrantedCcpaConsent) {
                arrayOf()
            } else {
                arrayOf("LDU")
            },
            1,
            1000,
        )
    }

    /**
     * Notify Meta Audience Network of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
        )

        AdSettings.setMixedAudience(isSubjectToCoppa)
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest,
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        // Meta's getBidderToken() needs to be called on a background thread.
        return withContext(IO) {
            val token = BidderTokenProvider.getBidderToken(context) ?: ""

            PartnerLogController.log(if (token.isEmpty()) BIDDER_INFO_FETCH_FAILED else BIDDER_INFO_FETCH_SUCCEEDED)
            hashMapOf("buyeruid" to token)
        }
    }

    /**
     * Attempt to load a Meta Audience Network ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format.key) {
            AdFormat.INTERSTITIAL.key ->
                loadInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )
            AdFormat.REWARDED.key ->
                loadRewardedAd(
                    context,
                    request,
                    partnerAdListener,
                )
            AdFormat.BANNER.key, "adaptive_banner" ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
                )
            else -> {
                if (request.format.key == "rewarded_interstitial") {
                    loadRewardedInterstitialAd(
                        context,
                        request,
                        partnerAdListener,
                    )
                } else {
                    PartnerLogController.log(LOAD_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
                }
            }
        }
    }

    /**
     * Attempt to show the currently loaded Meta Audience Network ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the Meta ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        context: Context,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return suspendCancellableCoroutine { continuation ->
            val weakContinuationRef = WeakReference(continuation)

            fun resumeOnce(result: Result<PartnerAd>) {
                weakContinuationRef.get()?.let {
                    if (it.isActive) {
                        it.resume(result)
                    }
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Unable to resume continuation once. Continuation is null.")
                }
            }

            when (partnerAd.request.format.key) {
                AdFormat.BANNER.key, "adaptive_banner" -> {
                    // Banner ads do not have a separate "show" mechanism.
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(Result.success(partnerAd))
                    return@suspendCancellableCoroutine
                }
                AdFormat.INTERSTITIAL.key -> showInterstitialAd(partnerAd)
                AdFormat.REWARDED.key -> {
                    resumeOnce(showRewardedAd(partnerAd))
                    return@suspendCancellableCoroutine
                }
                else -> {
                    if (partnerAd.request.format.key == "rewarded_interstitial") {
                        resumeOnce(showRewardedInterstitialAd(partnerAd))
                        return@suspendCancellableCoroutine
                    } else {
                        PartnerLogController.log(SHOW_FAILED)
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT,
                                ),
                            ),
                        )
                        return@suspendCancellableCoroutine
                    }
                }
            }

            // Only suspend for interstitial show results. Meta's rewarded ad API does not provide a callback.
            onInterstitialAdShowSuccess = {
                PartnerLogController.log(SHOW_SUCCEEDED)
                resumeOnce(Result.success(partnerAd))
            }

            onInterstitialAdShowFailure = {
                PartnerLogController.log(SHOW_FAILED)
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(
                            ChartboostMediationError.CM_SHOW_FAILURE_UNKNOWN,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Discard unnecessary Meta Audience Network ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the Meta ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL.key -> destroyInterstitialAd(partnerAd)
            AdFormat.REWARDED.key -> destroyRewardedAd(partnerAd)
            else -> {
                if (partnerAd.request.format.key == "rewarded_interstitial") {
                    destroyRewardedInterstitialAd(partnerAd)
                } else {
                    PartnerLogController.log(INVALIDATE_SUCCEEDED)
                    Result.success(partnerAd)
                }
            }
        }
    }

    /**
     * Get a [Result] containing the initialization result of the Meta Audience Network SDK.
     *
     * @param result The initialization result of the Meta Audience Network SDK.
     *
     * @return A [Result] object containing details about the initialization result.
     */
    private fun getInitResult(result: AudienceNetworkAds.InitResult): Result<Unit> {
        return if (result.isSuccess) {
            Result.success(PartnerLogController.log(SETUP_SUCCEEDED))
        } else {
            PartnerLogController.log(SETUP_FAILED, "${result.message}.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN))
        }
    }

    /**
     * Attempt to load a Meta Audience Network banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val adView =
                AdView(
                    context,
                    request.partnerPlacement,
                    getMetaBannerAdSize(request.size),
                )

            val metaListener: AdListener =
                object : AdListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onError(
                        ad: Ad,
                        adError: AdError,
                    ) {
                        PartnerLogController.log(LOAD_FAILED, adError.errorMessage)
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    getChartboostMediationError(
                                        adError.errorCode,
                                    ),
                                ),
                            ),
                        )
                    }

                    override fun onAdLoaded(ad: Ad) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(
                                PartnerAd(
                                    ad = ad,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            ),
                        )
                    }

                    override fun onAdClicked(ad: Ad) {
                        PartnerLogController.log(DID_CLICK)
                        partnerAdListener.onPartnerAdClicked(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onLoggingImpression(ad: Ad) {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        partnerAdListener.onPartnerAdImpression(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }
                }

            // Meta Audience Network is now bidding-only.
            adView.loadAd(
                adView.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build(),
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val interstitialAd = InterstitialAd(context, request.partnerPlacement)
            val metaListener = MetaInterstitialAdListener(
                continuationRef = WeakReference(continuation),
                request = request,
                partnerAdListener = partnerAdListener,
                interstitialAd = interstitialAd,
            )

            // Meta Audience Network is now bidding-only.
            interstitialAd.loadAd(
                interstitialAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build(),
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network rewarded video ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val rewardedVideoAd = RewardedVideoAd(context, request.partnerPlacement)
            val metaListener = MetaRewardedAdListener(
                continuationRef = WeakReference(continuation),
                request = request,
                partnerAdListener = partnerAdListener,
                rewardedVideoAd = rewardedVideoAd,
            )

            // Meta Audience Network is now bidding-only.
            rewardedVideoAd.loadAd(
                rewardedVideoAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build(),
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network rewarded interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val rewardedInterstitialAd = RewardedInterstitialAd(context, request.partnerPlacement)
            val metaListener = MetaRewardedInterstitialAdListener(
                continuationRef = WeakReference(continuation),
                request = request,
                partnerAdListener = partnerAdListener,
                rewardedInterstitialAd = rewardedInterstitialAd,
            )

            rewardedInterstitialAd.loadAd(
                rewardedInterstitialAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build(),
            )
        }
    }

    /**
     * Attempt to show a Meta Audience Network interstitial ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the interstitial ad to be shown.
     *
     * @return Result.success(partnerAd) if the ad was successfully shown, otherwise Result.failure(Exception).
     */
    private fun showInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let { ad ->
            if (readyToShow(ad)) {
                (ad as InterstitialAd).show()
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to show a Meta Audience Network rewarded video ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the rewarded video ad to be shown.
     *
     * @return Result.success(partnerAd) if the ad was successfully shown, otherwise Result.failure(Exception).
     */
    private fun showRewardedAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let { ad ->
            if (readyToShow(ad)) {
                (ad as RewardedVideoAd).show()

                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to show a Meta Audience Network rewarded interstitial ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the rewarded interstitial ad to be shown.
     *
     * @return Result.success(partnerAd) if the ad was successfully shown, otherwise Result.failure(Exception).
     */
    private fun showRewardedInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let { ad ->
            if (readyToShow(ad)) {
                (ad as RewardedInterstitialAd).show()

                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Determine if a Meta Audience Network ad is ready to be shown.
     *
     * @param ad The ad to be checked.
     *
     * @return True if the ad is ready, false otherwise.
     */
    private fun readyToShow(ad: Any): Boolean {
        return when (ad) {
            is InterstitialAd -> ad.isAdLoaded && !ad.isAdInvalidated
            is RewardedVideoAd -> ad.isAdLoaded && !ad.isAdInvalidated
            is AdView -> !ad.isAdInvalidated
            is RewardedInterstitialAd -> ad.isAdLoaded && !ad.isAdInvalidated
            else -> false
        }
    }

    /**
     * Attempt to destroy the Meta Audience Network banner ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is AdView) {
                it.visibility = View.GONE
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an AdView.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to destroy the Meta Audience Network interstitial ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is InterstitialAd) {
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not an InterstitialAd.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to destroy the Meta Audience Network rewarded ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyRewardedAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is RewardedVideoAd) {
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not a RewardedVideoAd.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Attempt to destroy the Meta Audience Network rewarded interstitial ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyRewardedInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is RewardedInterstitialAd) {
                it.destroy()

                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            } else {
                PartnerLogController.log(INVALIDATE_FAILED, "Ad is not a RewardedInterstitialAd.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Find the most appropriate Meta Audience Network banner ad size for the given screen area
     * based on height.
     *
     * @param size The [Size] to parse for conversion.
     *
     * @return The Meta ad size that best matches the given [Size].
     */
    private fun getMetaBannerAdSize(size: Size?): AdSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> AdSize.BANNER_HEIGHT_50
                it in 90 until 250 -> AdSize.BANNER_HEIGHT_90
                it >= 250 -> AdSize.RECTANGLE_HEIGHT_250
                else -> AdSize.BANNER_HEIGHT_50
            }
        } ?: AdSize.BANNER_HEIGHT_50
    }

    /**
     * Callback for interstitial ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param partnerAdListener A [PartnerAdListener] to be notified of ad events.
     * @param interstitialAd An [InterstitialAd] object containing the interstitial ad.
     */
    private class MetaInterstitialAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val partnerAdListener: PartnerAdListener,
        private val interstitialAd: InterstitialAd,
    ): InterstitialAdListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "Unable to resume continuation. Continuation is null."
                )
            }
        }

        override fun onInterstitialDisplayed(ad: Ad) {
            when {
                ad.isAdInvalidated -> {
                    partnerAdListener.onPartnerAdExpired(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ),
                    )
                    onInterstitialAdShowFailure()
                    onInterstitialAdShowFailure = {}
                }
                else -> {
                    onInterstitialAdShowSuccess()
                    onInterstitialAdShowSuccess = {}
                }
            }
        }

        override fun onInterstitialDismissed(ad: Ad?) {
            PartnerLogController.log(DID_DISMISS)
            partnerAdListener.onPartnerAdDismissed(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onError(
            ad: Ad,
            adError: AdError,
        ) {
            PartnerLogController.log(LOAD_FAILED, adError.errorMessage)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        getChartboostMediationError(
                            adError.errorCode,
                        ),
                    ),
                ),
            )
        }

        override fun onAdLoaded(ad: Ad) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdClicked(ad: Ad) {
            PartnerLogController.log(DID_CLICK)
            partnerAdListener.onPartnerAdClicked(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onLoggingImpression(ad: Ad) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            partnerAdListener.onPartnerAdImpression(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }
    }

    /**
     * Callback for rewarded ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param partnerAdListener A [PartnerAdListener] to be notified of ad events.
     * @param rewardedVideoAd A [RewardedVideoAd] object containing the rewarded video ad.
     */
    private class MetaRewardedAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val partnerAdListener: PartnerAdListener,
        private val rewardedVideoAd: RewardedVideoAd,
    ): RewardedVideoAdListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onRewardedVideoCompleted() {
            PartnerLogController.log(DID_REWARD)
            partnerAdListener.onPartnerAdRewarded(
                PartnerAd(
                    ad = rewardedVideoAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onLoggingImpression(ad: Ad) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            partnerAdListener.onPartnerAdImpression(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onRewardedVideoClosed() {
            PartnerLogController.log(DID_DISMISS)
            partnerAdListener.onPartnerAdDismissed(
                PartnerAd(
                    ad = rewardedVideoAd,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }

        override fun onError(
            ad: Ad,
            adError: AdError,
        ) {
            PartnerLogController.log(LOAD_FAILED, adError.errorMessage)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        getChartboostMediationError(
                            adError.errorCode,
                        ),
                    ),
                ),
            )
        }

        override fun onAdLoaded(ad: Ad) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdClicked(ad: Ad) {
            PartnerLogController.log(DID_CLICK)
            partnerAdListener.onPartnerAdClicked(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }
    }

    /**
     * Callback for rewarded interstitial ads.
     *
     * @param continuationRef A [WeakReference] to the [CancellableContinuation] to be resumed once the ad is shown.
     * @param request A [PartnerAdLoadRequest] object containing the request.
     * @param partnerAdListener A [PartnerAdListener] to be notified of ad events.
     * @param rewardedInterstitialAd A [RewardedVideoAd] object containing the rewarded video ad.
     */
    private class MetaRewardedInterstitialAdListener(
        private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
        private val request: PartnerAdLoadRequest,
        private val partnerAdListener: PartnerAdListener,
        private val rewardedInterstitialAd: RewardedInterstitialAd,
    ): RewardedInterstitialAdListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation. Continuation is null.")
            }
        }

        override fun onError(
            ad: Ad?,
            error: AdError,
        ) {
            PartnerLogController.log(LOAD_FAILED, error.errorMessage)
            resumeOnce(
                Result.failure(
                    ChartboostMediationAdException(
                        getChartboostMediationError(
                            error.errorCode,
                        ),
                    ),
                ),
            )
        }

        override fun onAdLoaded(ad: Ad?) {
            PartnerLogController.log(LOAD_SUCCEEDED)
            resumeOnce(
                Result.success(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                ),
            )
        }

        override fun onAdClicked(ad: Ad?) {
            PartnerLogController.log(DID_CLICK)
            partnerAdListener.onPartnerAdClicked(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onLoggingImpression(ad: Ad?) {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            partnerAdListener.onPartnerAdImpression(
                PartnerAd(
                    ad = ad,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onRewardedInterstitialCompleted() {
            PartnerLogController.log(DID_REWARD)
            partnerAdListener.onPartnerAdRewarded(
                PartnerAd(
                    ad = rewardedInterstitialAd,
                    details = emptyMap(),
                    request = request,
                ),
            )
        }

        override fun onRewardedInterstitialClosed() {
            PartnerLogController.log(DID_DISMISS)
            partnerAdListener.onPartnerAdDismissed(
                PartnerAd(
                    ad = rewardedInterstitialAd,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            )
        }
    }
}
