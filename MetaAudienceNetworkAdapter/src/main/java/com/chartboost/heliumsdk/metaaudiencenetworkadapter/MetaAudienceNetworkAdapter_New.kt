package com.chartboost.heliumsdk.metaaudiencenetworkadapter

import android.content.Context
import android.util.DisplayMetrics
import android.util.Size
import android.view.View
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import com.facebook.ads.*
import com.facebook.ads.Ad
import com.facebook.ads.BuildConfig.VERSION_NAME
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium Meta Audience Network adapter.
 */
class MetaAudienceNetworkAdapter_New : PartnerAdapter {
    companion object {
        /**
         * The tag used for log messages.
         */
        private const val TAG = "[MetaAudienceNetworkAdapter_New]"
    }

    /**
     * Get the Meta Audience Network SDK version.
     */
    override val partnerSdkVersion: String
        get() = VERSION_NAME

    /**
     * Get the Meta Audience Network adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = "3.{$VERSION_NAME}.0"

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
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        return suspendCoroutine { continuation ->
            AudienceNetworkAds
                .buildInitSettings(context.applicationContext)
                .withMediationService("Helium ${BuildConfig.VERSION_NAME}") // TODO: Separate out the various VERSION_NAMEs
                .withInitListener { result ->
                    continuation.resume(getInitResult(result))
                }
                .initialize()
        }
    }

    /**
     * Meta Audience Network internally handles GDPR. No action is required.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        // NO-OP
    }

    /**
     * Meta Audience Network internally handles GDPR. No action is required.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        // NO-OP
    }

    /**
     * Notify Meta Audience Network of the CCPA compliance.
     *
     * @param context The current [Context].
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(context: Context, hasGivenCcpaConsent: Boolean, privacyString: String?) {
        AdSettings.setDataProcessingOptions(
            if (hasGivenCcpaConsent)
                arrayOf()
            else
                arrayOf("LDU"), 1, 1000
        )
    }

    /**
     * Notify Meta Audience Network of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
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
        request: PreBidRequest
    ): Map<String, String> {
        // HB-3762: Meta's getBidderToken() needs to be called on a background thread.
        return withContext(IO) {
            hashMapOf("buyeruid" to BidderTokenProvider.getBidderToken(context))
        }
    }

    /**
     * Attempt to load a Meta Audience Network ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitialAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.REWARDED -> loadRewardedAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.BANNER -> loadBannerAd(
                context,
                request,
                partnerAdListener
            )
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
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL -> showInterstitialAd(partnerAd)
            AdFormat.REWARDED -> showRewardedAd(partnerAd)
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
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL -> destroyInterstitialAd(partnerAd)
            AdFormat.REWARDED -> destroyRewardedAd(partnerAd)
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
            Result.success(LogController.i("$TAG Initialization succeeded."))
        } else {
            LogController.e("$TAG Initialization failed: ${result.message}.")
            Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
        }
    }

    /**
     * Attempt to load a Meta Audience Network banner ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param heliumListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: AdLoadRequest,
        heliumListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val adView = AdView(
                context,
                request.partnerPlacement,
                getMetaBannerAdSize(request.size, context)
            )

            val metaListener: AdListener = object : AdListener {
                override fun onError(ad: Ad, adError: AdError) {
                    LogController.e("$TAG Failed to load Meta banner ad: ${adError.errorMessage}")
                    continuation.resume(
                        Result.failure(HeliumAdException(getHeliumErrorCode(adError.errorCode)))
                    )
                }

                override fun onAdLoaded(ad: Ad) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request,
                            )
                        )
                    )
                }

                override fun onAdClicked(ad: Ad) {
                    heliumListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onLoggingImpression(ad: Ad) {
                    heliumListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }
            }

            // Meta Audience Network is now bidding-only.
            adView.loadAd(
                adView.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build()
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param heliumListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: AdLoadRequest,
        heliumListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val interstitialAd = InterstitialAd(context, request.partnerPlacement)
            val metaListener: InterstitialAdListener = object : InterstitialAdListener {
                override fun onInterstitialDisplayed(ad: Ad) {
                    // NO-OP
                }

                override fun onInterstitialDismissed(ad: Ad?) {
                    heliumListener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ), null
                    )
                }

                override fun onError(ad: Ad, adError: AdError) {
                    LogController.e(
                        "$TAG Failed to load Meta interstitial ad: ${adError.errorMessage}"
                    )
                    continuation.resume(
                        Result.failure(HeliumAdException(getHeliumErrorCode(adError.errorCode)))
                    )
                }

                override fun onAdLoaded(ad: Ad) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdClicked(ad: Ad) {
                    heliumListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }

                override fun onLoggingImpression(ad: Ad) {
                    heliumListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }
            }

            // Meta Audience Network is now bidding-only.
            interstitialAd.loadAd(
                interstitialAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build()
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network rewarded video ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param heliumListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: AdLoadRequest,
        heliumListener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val rewardedVideoAd = RewardedVideoAd(context, request.partnerPlacement)
            val metaListener: RewardedVideoAdListener = object : RewardedVideoAdListener {
                override fun onRewardedVideoCompleted() {
                    heliumListener.onPartnerAdRewarded(
                        PartnerAd(
                            ad = rewardedVideoAd,
                            details = emptyMap(),
                            request = request
                        ), Reward(0, "")
                    )
                }

                override fun onLoggingImpression(ad: Ad) {
                    heliumListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }

                override fun onRewardedVideoClosed() {
                    heliumListener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = rewardedVideoAd,
                            details = emptyMap(),
                            request = request
                        ), null
                    )
                }

                override fun onError(ad: Ad, adError: AdError) {
                    LogController.e(
                        "$TAG Failed to load Meta rewarded ad: ${adError.errorMessage}"
                    )
                    continuation.resume(
                        Result.failure(HeliumAdException(getHeliumErrorCode(adError.errorCode)))
                    )
                }

                override fun onAdLoaded(ad: Ad) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = ad,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdClicked(ad: Ad) {
                    heliumListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }
            }

            // Meta Audience Network is now bidding-only.
            rewardedVideoAd.loadAd(
                rewardedVideoAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(metaListener).build()
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
                LogController.e("$TAG Failed to show Meta interstitial ad. Ad is not ready.")
                Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
            }
        } ?: run {
            LogController.e("$TAG Failed to show Meta interstitial ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
                Result.success(partnerAd)
            } else {
                LogController.e("$TAG Failed to show Meta rewarded video ad. Ad is not ready.")
                Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
            }
        } ?: run {
            LogController.e("$TAG Failed to show Meta rewarded video ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
                Result.success(partnerAd)
            } else {
                LogController.e("$TAG Failed to destroy Meta banner ad. Ad is not an AdView.")
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.e("$TAG Failed to destroy Meta banner ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
                Result.success(partnerAd)
            } else {
                LogController.e(
                    "$TAG Failed to destroy Meta interstitial ad. Ad is not an InterstitialAd."
                )
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.e("$TAG Failed to destroy Meta interstitial ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
                Result.success(partnerAd)
            } else {
                LogController.e(
                    "$TAG Failed to destroy Meta rewarded ad. Ad is not a RewardedVideoAd."
                )
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.e("$TAG Failed to destroy Meta rewarded ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Find the most appropriate Meta Audience Network banner ad size for the given screen area
     * based on height.
     *
     * @param size The [Size] to parse for conversion.
     * @param context The [Context] to use for conversion.
     *
     * @return The Meta ad size that best matches the given [Size].
     */
    private fun getMetaBannerAdSize(size: Size?, context: Context) = when (size?.height) {
        in 50 until 90 -> AdSize.BANNER_HEIGHT_50
        in 90 until 250 -> AdSize.BANNER_HEIGHT_90
        in 250 until convertPixelsToDp(
            DisplayMetrics().heightPixels,
            context
        ).toInt() -> AdSize.RECTANGLE_HEIGHT_250
        else -> AdSize.BANNER_HEIGHT_50
    }

    /**
     * Convert a given Meta Audience Network error code into a [HeliumErrorCode].
     *
     * @param error The Meta [AdError] to convert.
     *
     * @return The corresponding [HeliumErrorCode].
     */
    private fun getHeliumErrorCode(error: Int): HeliumErrorCode {
        return when (error) {
            AdError.NO_FILL_ERROR_CODE -> HeliumErrorCode.NO_FILL
            AdError.NETWORK_ERROR_CODE -> HeliumErrorCode.NO_CONNECTIVITY
            AdError.SERVER_ERROR_CODE -> HeliumErrorCode.SERVER_ERROR
            AdError.INTERSTITIAL_AD_TIMEOUT -> HeliumErrorCode.PARTNER_SDK_TIMEOUT
            else -> HeliumErrorCode.INTERNAL
        }
    }

    /**
     * Util method to convert a pixels value to a density-independent pixels value.
     *
     * @param pixels The pixels value to convert.
     * @param context The context to use for density conversion.
     *
     * @return The converted density-independent pixels value as a Float.
     */
    private fun convertPixelsToDp(pixels: Int, context: Context): Float {
        return pixels / (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
    }
}
