/*
 * Copyright 2023-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.metaaudiencenetworkadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import android.view.View
import com.chartboost.chartboostmediationsdk.ad.ChartboostMediationBannerAdView.ChartboostMediationBannerSize.Companion.asSize
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentManagementPlatform
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import com.facebook.ads.*
import com.facebook.ads.Ad
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
         * Convert a given Meta Audience Network error code into a [ChartboostMediationError].
         *
         * @param error The Meta [AdError] to convert.
         *
         * @return The corresponding [ChartboostMediationError].
         */
        internal fun getChartboostMediationError(error: Int) =
            when (error) {
                AdError.NO_FILL_ERROR_CODE -> ChartboostMediationError.LoadError.NoFill
                AdError.NETWORK_ERROR_CODE -> ChartboostMediationError.OtherError.NoConnectivity
                AdError.SERVER_ERROR_CODE -> ChartboostMediationError.OtherError.AdServerError
                AdError.INTERSTITIAL_AD_TIMEOUT -> ChartboostMediationError.LoadError.AdRequestTimeout
                AdError.LOAD_TOO_FREQUENTLY_ERROR_CODE -> ChartboostMediationError.LoadError.RateLimited
                AdError.BROKEN_MEDIA_ERROR_CODE -> ChartboostMediationError.ShowError.MediaBroken
                AdError.LOAD_CALLED_WHILE_SHOWING_AD -> ChartboostMediationError.LoadError.ShowInProgress
                else -> ChartboostMediationError.OtherError.PartnerError
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
     * The Meta Audience Network adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = MetaAudienceNetworkAdapterConfiguration

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
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<Map<String, Any>>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            AudienceNetworkAds
                .buildInitSettings(context.applicationContext)
                .withMediationService("Chartboost ${configuration.adapterVersion}")
                .withInitListener { result ->
                    resumeOnce(getInitResult(result))
                }
                .withPlacementIds(MetaAudienceNetworkAdapterConfiguration.placementIds)
                .initialize()
        }
    }

    /**
     * Notify Meta Audience Network of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        AdSettings.setMixedAudience(isUserUnderage)
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        // Meta's getBidderToken() needs to be called on a background thread.
        return withContext(IO) {
            val token = BidderTokenProvider.getBidderToken(context) ?: ""

            PartnerLogController.log(if (token.isEmpty()) BIDDER_INFO_FETCH_FAILED else BIDDER_INFO_FETCH_SUCCEEDED)
            Result.success(hashMapOf("buyeruid" to token))
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

        return when (request.format) {
            PartnerAdFormats.INTERSTITIAL ->
                loadInterstitialAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.REWARDED ->
                loadRewardedAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.BANNER ->
                loadBannerAd(
                    context,
                    request,
                    partnerAdListener,
                )
            PartnerAdFormats.REWARDED_INTERSTITIAL -> {
                    loadRewardedInterstitialAd(
                        context,
                        request,
                        partnerAdListener,
                    )
            }
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded Meta Audience Network ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the Meta ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
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

            when (partnerAd.request.format) {
                PartnerAdFormats.BANNER -> {
                    // Banner ads do not have a separate "show" mechanism.
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(Result.success(partnerAd))
                    return@suspendCancellableCoroutine
                }
                PartnerAdFormats.INTERSTITIAL -> showInterstitialAd(partnerAd)
                PartnerAdFormats.REWARDED -> {
                    resumeOnce(showRewardedAd(partnerAd))
                    return@suspendCancellableCoroutine
                }
                PartnerAdFormats.REWARDED_INTERSTITIAL -> {
                        resumeOnce(showRewardedInterstitialAd(partnerAd))
                        return@suspendCancellableCoroutine
                }
                else -> {
                    PartnerLogController.log(SHOW_FAILED)
                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(
                                ChartboostMediationError.ShowError.UnsupportedAdFormat,
                            ),
                        ),
                    )
                    return@suspendCancellableCoroutine
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
                            ChartboostMediationError.ShowError.Unknown,
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

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            PartnerAdFormats.INTERSTITIAL -> destroyInterstitialAd(partnerAd)
            PartnerAdFormats.REWARDED -> destroyRewardedAd(partnerAd)
            PartnerAdFormats.REWARDED_INTERSTITIAL -> destroyRewardedInterstitialAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>
    ) {
        val hasGrantedUspConsent =
            consents[ConsentKeys.CCPA_OPT_IN]?.takeIf { it.isNotBlank() }
                ?.equals(ConsentValues.GRANTED)
                ?: consents[ConsentKeys.USP]?.takeIf { it.isNotBlank() }
                    ?.let { ConsentManagementPlatform.getUspConsentFromUspString(it) }
        hasGrantedUspConsent?.let {
            PartnerLogController.log(
                if (hasGrantedUspConsent) {
                    USP_CONSENT_GRANTED
                } else {
                    USP_CONSENT_DENIED
                },
            )

            AdSettings.setDataProcessingOptions(
                if (hasGrantedUspConsent) {
                    arrayOf()
                } else {
                    arrayOf("LDU")
                },
                1,
                1000,
            )
        }
    }

    /**
     * Get a [Result] containing the initialization result of the Meta Audience Network SDK.
     *
     * @param result The initialization result of the Meta Audience Network SDK.
     *
     * @return A [Result] object containing details about the initialization result.
     */
    private fun getInitResult(result: AudienceNetworkAds.InitResult): Result<Map<String, Any>> {
        return if (result.isSuccess) {
            PartnerLogController.log(SETUP_SUCCEEDED)
            Result.success(emptyMap())
        } else {
            PartnerLogController.log(SETUP_FAILED, "${result.message}.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown))
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
                    getMetaBannerAdSize(request.bannerSize?.asSize()),
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
            val metaListener =
                MetaInterstitialAdListener(
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
            val metaListener =
                MetaRewardedAdListener(
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
            val metaListener =
                MetaRewardedInterstitialAdListener(
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
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
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.WrongResourceType))
            }
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
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
    ) : InterstitialAdListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            continuationRef.get()?.let {
                if (it.isActive) {
                    it.resume(result)
                }
            } ?: run {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "Unable to resume continuation. Continuation is null.",
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
    ) : RewardedVideoAdListener {
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
    ) : RewardedInterstitialAdListener {
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
