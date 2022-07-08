package com.chartboost.helium.metaaudiencenetworkadapter

import android.content.Context
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Size
import android.view.View
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import com.facebook.ads.*
import com.facebook.ads.Ad
import com.facebook.ads.BuildConfig.VERSION_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium Meta Audience Network adapter.
 */
class MetaAudienceNetworkAdapter : PartnerAdapter {
    companion object {
        /**
         * The tag used for log messages.
         */
        private const val TAG = "[FacebookAdapter]"
    }

    /**
     * A map of Helium's listeners for the corresponding Helium placements.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

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
        get() = BuildConfig.VERSION_NAME

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
                .withMediationService("Helium ${BuildConfig.VERSION_NAME}")
                .withInitListener { result ->
                    continuation.resume(getInitResult(result))
                }
                .initialize()
        }
    }

    /**
     * Meta Audience Network internally handles GDPR. No action is required.
     */
    override fun setGdprApplies(gdprApplies: Boolean) {
        // NO-OP
    }

    /**
     * Meta Audience Network internally handles GDPR. No action is required.
     */
    override fun setGdprConsentStatus(gdprConsentStatus: GdprConsentStatus) {
        // NO-OP
    }

    /**
     * Notify Meta Audience Network of the CCPA compliance.
     *
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaPrivacyString(privacyString: String?) {
        AdSettings.setDataProcessingOptions(
            if (TextUtils.isEmpty(privacyString))
                arrayOf()
            else
                arrayOf("LDU"), 1, 1000
        )
    }

    /**
     * Notify Meta Audience Network of the COPPA subjectivity.
     *
     * @param isSubjectToCoppa The COPPA subjectivity.
     */
    override fun setUserSubjectToCoppa(isSubjectToCoppa: Boolean) {
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
        // Save the listener for later use.
        listeners[request.heliumPlacement] = partnerAdListener

        return when (request.format) {
            AdFormat.INTERSTITIAL -> loadInterstitialAd(
                context,
                request
            )
            AdFormat.REWARDED -> loadRewardedAd(
                context,
                request
            )
            AdFormat.BANNER -> loadBannerAd(
                context,
                request
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
            AdFormat.BANNER -> showBannerAd(partnerAd)
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
        listeners.remove(partnerAd.request.heliumPlacement)

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
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(context: Context, request: AdLoadRequest): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val listener = listeners[request.heliumPlacement]
            val adView = AdView(
                context,
                request.partnerPlacement,
                getFacebookBannerAdSize(request.size)
            )

            val bannerListener: AdListener = object : AdListener {
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
                                inlineView = null,
                                details = emptyMap(),
                                request = request,
                            )
                        )
                    )
                }

                override fun onAdClicked(ad: Ad) {
                    listener?.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request,
                        )
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for Meta adapter."
                    )
                }

                override fun onLoggingImpression(ad: Ad) {
                    listener?.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request,
                        )
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdImpression for Meta adapter."
                    )
                }
            }

            // Meta Audience Network is now bidding-only.
            adView.loadAd(
                adView.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(bannerListener).build()
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: AdLoadRequest
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val heliumListener = listeners[request.heliumPlacement]
            val interstitialAd = InterstitialAd(context, request.partnerPlacement)
            val facebookListener: InterstitialAdListener = object : InterstitialAdListener {
                override fun onInterstitialDisplayed(ad: Ad) {
                    // NO-OP
                }

                override fun onInterstitialDismissed(ad: Ad?) {
                    heliumListener?.onPartnerAdDismissed(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request,
                        ), null
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdDismissed for Meta adapter."
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
                                inlineView = null,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdClicked(ad: Ad) {
                    heliumListener?.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request
                        )
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for Meta adapter."
                    )
                }

                override fun onLoggingImpression(ad: Ad) {
                    heliumListener?.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request
                        )
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdImpression for Meta adapter."
                    )
                }
            }

            // Meta Audience Network is now bidding-only.
            interstitialAd.loadAd(
                interstitialAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(facebookListener).build()
            )
        }
    }

    /**
     * Attempt to load a Meta Audience Network rewarded video ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: AdLoadRequest
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val heliumListener = listeners[request.heliumPlacement]
            val rewardedVideoAd = RewardedVideoAd(context, request.partnerPlacement)
            val facebookListener: RewardedVideoAdListener = object : RewardedVideoAdListener {
                override fun onRewardedVideoCompleted() {
                    heliumListener?.onPartnerAdRewarded(
                        PartnerAd(
                            ad = rewardedVideoAd,
                            inlineView = null,
                            details = emptyMap(),
                            request = request
                        ), Reward(0, "")
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdRewarded for Meta adapter."
                    )
                }

                override fun onLoggingImpression(ad: Ad) {
                    heliumListener?.onPartnerAdImpression(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request
                        )
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdImpression for Meta adapter."
                    )
                }

                override fun onRewardedVideoClosed() {
                    heliumListener?.onPartnerAdDismissed(
                        PartnerAd(
                            ad = rewardedVideoAd,
                            inlineView = null,
                            details = emptyMap(),
                            request = request
                        ), null
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdDismissed for Meta adapter."
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
                                inlineView = null,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdClicked(ad: Ad) {
                    heliumListener?.onPartnerAdClicked(
                        PartnerAd(
                            ad = ad,
                            inlineView = null,
                            details = emptyMap(),
                            request = request
                        )
                    ) ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for Meta adapter."
                    )
                }
            }

            // Meta Audience Network is now bidding-only.
            rewardedVideoAd.loadAd(
                rewardedVideoAd.buildLoadAdConfig()
                    .withBid(request.adm)
                    .withAdListener(facebookListener).build()
            )
        }
    }

    /**
     * Attempt to show a Meta Audience Network banner ad.
     *
     * @param partnerAd The [PartnerAd] instance containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            CoroutineScope(Dispatchers.Main).launch {
                (it as AdView).visibility = View.VISIBLE
            }
            Result.success(partnerAd)
        } ?: run {
            LogController.e("$TAG Failed to show Meta banner ad. Banner ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
     *
     * @return The Meta ad size that best matches the given [Size].
     */
    private fun getFacebookBannerAdSize(size: Size?) = when (size?.height) {
        in 50 until 90 -> AdSize.BANNER_HEIGHT_50
        in 90 until 250 -> AdSize.BANNER_HEIGHT_90
        in 250 until DisplayMetrics().heightPixels -> AdSize.RECTANGLE_HEIGHT_250
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
}
