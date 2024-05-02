package com.chartboost.mediation.metaaudiencenetworkadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.facebook.ads.AdSettings

object MetaAudienceNetworkAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "facebook"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Meta Audience Network"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion = com.facebook.ads.BuildConfig.VERSION_NAME

    /**
     * The partner adapter version.
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
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_META_AUDIENCE_NETWORK_ADAPTER_VERSION

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
                    PartnerLogController.PartnerAdapterEvents.CUSTOM,
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
                    PartnerLogController.PartnerAdapterEvents.CUSTOM,
                    "Meta Audience Network placement IDs " +
                            if (value.isEmpty()) {
                                "not provided for initialization."
                            } else {
                                "provided for initialization: ${value.joinToString()}."
                            },
            )
        }
}
