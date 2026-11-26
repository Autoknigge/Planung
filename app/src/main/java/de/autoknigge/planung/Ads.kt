package de.autoknigge.planung

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/** Werbung: erst Einwilligung (EU), dann Banner am unteren Rand. */
object Ads {
    private val initialized = AtomicBoolean(false)

    private fun testMode(activity: Activity): Boolean = activity.resources.getBoolean(R.bool.admob_test_mode)

    /** Führt [block] aus, sobald Werbung angefordert werden darf (nach der Einwilligung). */
    private fun whenAllowed(activity: Activity, block: () -> Unit) {
        val ci = UserMessagingPlatform.getConsentInformation(activity)
        val done = AtomicBoolean(false)
        fun go() {
            if ((ci.canRequestAds() || testMode(activity)) && done.compareAndSet(false, true)) block()
        }
        ci.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> go() } },
            { _ -> go() }
        )
        // Einwilligung wurde bei einem früheren Start schon erteilt.
        go()
    }

    private fun init(activity: Activity) {
        if (initialized.compareAndSet(false, true)) MobileAds.initialize(activity) { }
    }

    fun attach(activity: Activity, container: LinearLayout) {
        whenAllowed(activity) { showBanner(activity, container) }
    }

    private fun showBanner(activity: Activity, container: LinearLayout) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (container.findViewWithTag<View>("ad") != null) return
        init(activity)
        val view = AdView(activity)
        view.tag = "ad"
        val dm = activity.resources.displayMetrics
        val widthDp = (dm.widthPixels / dm.density).toInt()
        view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
        view.adUnitId = if (testMode(activity)) "ca-app-pub-3940256099942544/9214589741" else activity.getString(R.string.admob_banner_id)
        container.addView(
            view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        view.adListener = object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {}
        }
        view.loadAd(AdRequest.Builder().build())
    }

    /** Öffnet die Werbe-Einstellungen: Einwilligungsformular, falls nötig, sonst eine Erklärung mit Link zu Google. */
    fun showPrivacyOptions(activity: Activity) {
        val ci = UserMessagingPlatform.getConsentInformation(activity)
        if (ci.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ -> }
            return
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Werbe-Einstellungen")
            .setMessage(
                "Hier kannst du deine Auswahl zur Werbung ändern. Mit „Auswahl zurücksetzen“ fragt die App dich sofort noch einmal, " +
                    "ob du der Nutzung deiner Daten für Werbezwecke zustimmst.\n\n" +
                    "Wie Google Werbung für dich personalisiert, kannst du außerdem in den Anzeigen-Einstellungen von Google ändern."
            )
            .setPositiveButton("Auswahl zurücksetzen") { _, _ ->
                try {
                    ci.reset()
                    ci.requestConsentInfoUpdate(
                        activity,
                        ConsentRequestParameters.Builder().build(),
                        { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> } },
                        { _ -> }
                    )
                } catch (e: Exception) {
                    Toast.makeText(activity, "Das Zurücksetzen hat nicht geklappt.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Google-Einstellungen") { _, _ ->
                try {
                    activity.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://adssettings.google.com"))
                    )
                } catch (e: Exception) {
                    Toast.makeText(activity, "Kein Browser gefunden.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }
}
