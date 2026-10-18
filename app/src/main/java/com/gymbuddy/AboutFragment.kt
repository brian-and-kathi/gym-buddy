package com.gymbuddy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.gymbuddy.databinding.FragmentAboutBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private class ProfileShareBridge(private val fragment: AboutFragment) {
    @JavascriptInterface
    fun share(url: String) {
        val parsed = Uri.parse(url.trim())
        val host = parsed.host ?: return
        if (!parsed.scheme.equals("https", ignoreCase = true)) return
        if (host != "gym.brianandkathi.com" && !host.endsWith(".brianandkathi.com")) return
        fragment.activity?.runOnUiThread {
            if (!fragment.isAdded) return@runOnUiThread
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, parsed.toString())
            }
            fragment.startActivity(Intent.createChooser(send, fragment.getString(R.string.about_share)))
        }
    }
}

class AboutFragment : Fragment() {

    companion object {
        private const val ARG_SHOW_LOG = "show_log"

        fun showingLog(): AboutFragment = AboutFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SHOW_LOG, true) }
        }
    }

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    private var manualOpen = false
    private var loadedStatsUrl: String? = null
    private var nameRequest = 0
    private var aboutTab = "log"
    private var shareUrl: String? = null
    private var updateReady = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val parsed = WorkerRemote.parseProfileQr(contents)
        if (parsed == null) {
            Toast.makeText(requireContext(), R.string.scan_profile_invalid, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        saveProfile(parsed.first, parsed.second)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startProfileScan()
        else Toast.makeText(requireContext(), R.string.scan_camera_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        paintTitle()
        val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        binding.versionText.text = "Version ${packageInfo.versionName}"
        binding.creditsText.text = coloredCredits(getString(R.string.about_credits))
        binding.checkUpdatesButton.setOnClickListener { checkForUpdates() }
        binding.tabLog.setOnClickListener { selectTab("log") }
        binding.tabStats.setOnClickListener { selectTab("stats") }
        binding.tabMore.setOnClickListener { selectTab("more") }
        binding.shareButton.setOnClickListener { shareProfile() }

        binding.statsWeb.setBackgroundColor(Color.parseColor("#121212"))
        binding.statsWeb.settings.javaScriptEnabled = true
        binding.statsWeb.settings.domStorageEnabled = true
        binding.statsWeb.settings.useWideViewPort = true
        binding.statsWeb.settings.loadWithOverviewMode = true
        binding.statsWeb.addJavascriptInterface(ProfileShareBridge(this), "GymBuddy")
        binding.statsWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isProfileHost(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (_binding == null || !WorkerRemote.isConfigured(requireContext())) return
                if (aboutTab != "more") binding.statsStatus.visibility = View.GONE
                captureShareUrl()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame || _binding == null) return
                binding.statsStatus.visibility = View.VISIBLE
                binding.statsStatus.setText(R.string.about_stats_failed)
            }
        }

        binding.openSiteButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_site_url))))
        }
        binding.scanProfileButton.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startProfileScan() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.disconnectButton.setOnClickListener { disconnectProfile() }
        binding.manualToggle.setOnClickListener {
            manualOpen = !manualOpen
            renderConnection()
        }
        binding.workerSaveButton.setOnClickListener {
            saveProfile(
                binding.workerUrlInput.text.toString(),
                binding.workerTokenInput.text.toString()
            )
        }
        renderUpdateOffer()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.statsWeb, true)
        binding.workerUrlInput.setText(WorkerRemote.getUrl(requireContext()) ?: "")
        binding.workerTokenInput.setText(WorkerRemote.getToken(requireContext()) ?: "")
        renderConnection()
    }

    override fun onResume() {
        super.onResume()
        renderUpdateOffer()
    }

    fun renderUpdateOffer() {
        val binding = _binding ?: return
        val offer = AppUpdate.offer(requireContext())
        updateReady = offer != null
        if (offer != null) {
            binding.updateStatus.visibility = View.VISIBLE
            binding.updateStatus.text = getString(R.string.update_ready, offer.versionName)
            binding.checkUpdatesButton.setText(R.string.update_install)
        }
    }

    private fun saveProfile(url: String, token: String) {
        WorkerRemote.save(requireContext(), url, token)
        binding.workerUrlInput.setText(WorkerRemote.getUrl(requireContext()) ?: "")
        binding.workerTokenInput.setText(WorkerRemote.getToken(requireContext()) ?: "")
        val configured = WorkerRemote.isConfigured(requireContext())
        if (configured) manualOpen = false
        loadedStatsUrl = null
        shareUrl = null
        renderConnection()
        Toast.makeText(
            requireContext(),
            if (configured) R.string.worker_saved else R.string.worker_cleared,
            Toast.LENGTH_SHORT
        ).show()
        if (configured) {
            (activity as? MainActivity)?.let { StandardRoutineOffer.maybeCheck(it) }
        }
    }

    private fun renderConnection() {
        if (_binding == null) return
        val context = requireContext()
        val url = WorkerRemote.getUrl(context)
        val connected = !url.isNullOrBlank()
        val setupVisibility = if (connected) View.GONE else View.VISIBLE
        binding.profileStatus.visibility = setupVisibility
        binding.setupBody.visibility = setupVisibility
        binding.openSiteButton.visibility = setupVisibility
        binding.scanProfileButton.visibility = setupVisibility
        binding.manualToggle.visibility = setupVisibility
        binding.manualFields.visibility = if (!connected && manualOpen) View.VISIBLE else View.GONE
        binding.manualToggle.setText(if (manualOpen) R.string.about_hide_manual else R.string.about_manual)
        binding.aboutTabs.visibility = if (connected) View.VISIBLE else View.GONE
        binding.connectionRow.visibility = if (connected) View.VISIBLE else View.GONE
        binding.shareCard.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            showConnected(WorkerRemote.profileSlug(context) ?: url!!)
            if (arguments?.getBoolean(ARG_SHOW_LOG) == true) aboutTab = "log"
            loadDisplayName(url!!)
            selectTab(aboutTab)
        } else {
            binding.profileStatus.setText(R.string.about_not_connected)
            binding.statsWeb.visibility = View.GONE
            binding.statsStatus.visibility = View.GONE
            binding.aboutScroll.visibility = View.VISIBLE
            aboutTab = "log"
        }
    }

    private fun selectTab(tab: String) {
        if (_binding == null) return
        aboutTab = tab
        styleTab(binding.tabLogLabel, binding.tabLogBar, tab == "log")
        styleTab(binding.tabStatsLabel, binding.tabStatsBar, tab == "stats")
        styleTab(binding.tabMoreLabel, binding.tabMoreBar, tab == "more")
        val url = WorkerRemote.getUrl(requireContext())
        val showWeb = !url.isNullOrBlank() && tab != "more"
        binding.aboutScroll.visibility = if (showWeb) View.GONE else View.VISIBLE
        binding.statsWeb.visibility = if (showWeb) View.VISIBLE else View.GONE
        if (!showWeb) {
            binding.statsStatus.visibility = View.GONE
        }
        if (showWeb) loadStats(url!!, tab)
    }

    private fun styleTab(label: TextView, bar: View, selected: Boolean) {
        label.setTextColor(Color.parseColor(if (selected) "#F9F72E" else "#DDDDDD"))
        bar.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun showConnected(name: String) {
        val label = getString(R.string.about_connected, name)
        val text = SpannableString("\u25CF  $label")
        text.setSpan(ForegroundColorSpan(Color.parseColor("#4CBB17")), 0, 1, 0)
        binding.connectedBadge.text = text
    }

    private fun shareProfile() {
        val url = shareUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.about_share_unavailable, Toast.LENGTH_SHORT).show()
            if (WorkerRemote.isConfigured(requireContext())) {
                loadStats(WorkerRemote.getUrl(requireContext())!!, "log")
            }
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, getString(R.string.about_share)))
    }

    private fun captureShareUrl() {
        val web = _binding?.statsWeb ?: return
        web.evaluateJavascript(
            "(function(){var el=document.getElementById('agentLink');return el?el.value:'';})()"
        ) { raw ->
            if (_binding == null || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            val value = try {
                org.json.JSONArray("[$raw]").getString(0)
            } catch (_: Exception) {
                raw.trim().trim('"')
            }
            if (value.startsWith("https://")) shareUrl = value
        }
    }

    private fun checkForUpdates() {
        if (updateReady) {
            val offer = AppUpdate.offer(requireContext())
            if (offer != null) {
                AppUpdate.startInstall(requireActivity(), offer)
                return
            }
        }
        binding.checkUpdatesButton.isEnabled = false
        binding.updateStatus.visibility = View.VISIBLE
        binding.updateStatus.setText(R.string.about_checking)
        AppUpdate.checkNow(requireContext()) { result ->
            if (_binding == null) return@checkNow
            binding.checkUpdatesButton.isEnabled = true
            when (result) {
                is AppUpdate.Check.Update -> {
                    updateReady = true
                    binding.updateStatus.text = getString(R.string.update_ready, result.offer.versionName)
                    binding.checkUpdatesButton.setText(R.string.update_install)
                }
                AppUpdate.Check.Current -> {
                    updateReady = false
                    binding.updateStatus.setText(R.string.about_up_to_date)
                    binding.checkUpdatesButton.setText(R.string.about_check_updates)
                }
                AppUpdate.Check.Failed -> {
                    binding.updateStatus.setText(R.string.about_check_failed)
                    binding.checkUpdatesButton.setText(R.string.about_check_updates)
                }
            }
        }
    }

    private fun disconnectProfile() {
        manualOpen = false
        WorkerRemote.save(requireContext(), "", "")
        loadedStatsUrl = null
        shareUrl = null
        binding.statsWeb.loadUrl("about:blank")
        renderConnection()
        Toast.makeText(requireContext(), R.string.worker_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun loadStats(url: String, tab: String) {
        val target = if (tab == "stats") "$url/?app=1&view=totals" else "$url/?app=1&view=log"
        if (loadedStatsUrl == target) return
        loadedStatsUrl = target
        if (arguments?.getBoolean(ARG_SHOW_LOG) == true) {
            binding.statsWeb.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        if (binding.statsWeb.visibility == View.VISIBLE) {
            binding.statsStatus.visibility = View.VISIBLE
            binding.statsStatus.setText(R.string.about_stats_loading)
        }
        val token = WorkerRemote.getToken(requireContext())
        val slug = WorkerRemote.profileSlug(requireContext())
        val origin = Uri.parse(url).let { parsed ->
            val port = if (parsed.port == -1) "" else ":${parsed.port}"
            "${parsed.scheme}://${parsed.host}$port"
        }
        if (!token.isNullOrBlank() && !slug.isNullOrBlank()) {
            CookieManager.getInstance().setCookie(origin, "gb_profiles=$slug.$token; Path=/; Secure; SameSite=Lax") {
                if (_binding != null && loadedStatsUrl == target) binding.statsWeb.loadUrl(target)
            }
            CookieManager.getInstance().flush()
        } else {
            binding.statsWeb.loadUrl(target)
        }
    }

    private fun loadDisplayName(url: String) {
        val requestId = ++nameRequest
        val token = WorkerRemote.getToken(requireContext())
        thread {
            val name = try {
                val request = Request.Builder().url(url).apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }
                http.newCall(request.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    Regex("""name="gb-profile" content="([^"]*)"""").find(body)?.groupValues?.get(1)?.trim()
                }
            } catch (_: Exception) {
                null
            }
            activity?.runOnUiThread {
                if (_binding == null || requestId != nameRequest) return@runOnUiThread
                if (!name.isNullOrEmpty() && WorkerRemote.isConfigured(requireContext())) {
                    showConnected(name)
                }
            }
        }
    }

    private fun isProfileHost(uri: Uri): Boolean {
        val base = WorkerRemote.getUrl(requireContext()) ?: return false
        val host = Uri.parse(base).host ?: return false
        return uri.scheme == "https" && uri.host == host
    }

    private fun startProfileScan() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.scan_profile))
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    private fun paintTitle() {
        val appName = "Gym Buddy"
        val title = SpannableString(appName)
        title.setSpan(ForegroundColorSpan(Color.parseColor("#FFFF00")), 0, 3, 0)
        title.setSpan(ForegroundColorSpan(Color.parseColor("#00FF00")), 4, appName.length, 0)
        binding.appNameText.text = title
    }

    private fun coloredCredits(credits: String): SpannableString {
        val text = SpannableString(credits)
        val earth = credits.indexOf("Earth")
        val brian = credits.indexOf("Brian")
        if (earth >= 0) {
            text.setSpan(ForegroundColorSpan(Color.parseColor("#00FFFF")), earth, earth + "Earth".length, 0)
        }
        if (brian >= 0) {
            text.setSpan(ForegroundColorSpan(Color.parseColor("#FF6B6B")), brian, brian + "Brian".length, 0)
        }
        return text
    }

    override fun onDestroyView() {
        binding.statsWeb.apply {
            stopLoading()
            webViewClient = WebViewClient()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }
}
