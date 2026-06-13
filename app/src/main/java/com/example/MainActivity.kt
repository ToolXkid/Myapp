package com.example

import android.app.Application
import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.ads.*
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private lateinit var adMobHelper: AdMobHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Safely initialize AdMob on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MobileAds.initialize(this@MainActivity) {}
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        adMobHelper = AdMobHelper(this)

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF07070F)
                ) {
                    ImageGeneratorApp(adMobHelper = adMobHelper)
                }
            }
        }
    }
}

// ==========================================
// ADMOB HELPER CLASS WITH HIGH-LEVEL BINDINGS
// ==========================================
class AdMobHelper(private val activity: ComponentActivity) {
    private var rewardedAd: RewardedAd? = null
    var isAdLoading = mutableStateOf(false)
        private set

    fun loadAd(onAdLoaded: () -> Unit, onAdFailed: () -> Unit) {
        if (isAdLoading.value || rewardedAd != null) {
            if (rewardedAd != null) onAdLoaded()
            return
        }
        isAdLoading.value = true
        val adRequest = AdRequest.Builder().build()
        // AdMob live ID for Rewarded Ads from user
        val liveAdUnitId = "ca-app-pub-8725734436860588/1106639040"

        RewardedAd.load(
            activity,
            liveAdUnitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    rewardedAd = null
                    isAdLoading.value = false
                    onAdFailed()
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isAdLoading.value = false
                    onAdLoaded()
                }
            }
        )
    }

    fun showAd(onEarned: () -> Unit, onClosed: () -> Unit) {
        val ad = rewardedAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    onClosed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    rewardedAd = null
                    onClosed()
                }
            }
            ad.show(activity, OnUserEarnedRewardListener {
                onEarned()
            })
        } else {
            onClosed()
        }
    }

    fun clearLoadedAd() {
        rewardedAd = null
    }
}

// ==========================================
// LOCAL STATE VIEW MODEL
// ==========================================
class ImageGeneratorViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("ai_image_prefs", Context.MODE_PRIVATE)

    private val _coins = MutableStateFlow(prefs.getInt("user_coins", 50))
    val coins = _coins.asStateFlow()

    private val _totalAdsWatched = MutableStateFlow(prefs.getInt("total_ads_watched", 0))
    val totalAdsWatched = _totalAdsWatched.asStateFlow()

    private val _hasSeenOnboarding = MutableStateFlow(prefs.getBoolean("has_seen_onboarding", false))
    val hasSeenOnboarding = _hasSeenOnboarding.asStateFlow()

    // History items (Last 3 generated strings)
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history = _history.asStateFlow()

    data class HistoryItem(val prompt: String, val imageUrl: String)

    fun completeOnboarding() {
        prefs.edit().putBoolean("has_seen_onboarding", true).apply()
        _hasSeenOnboarding.value = true
    }

    fun addCoins(amount: Int) {
        val newBalance = _coins.value + amount
        prefs.edit().putInt("user_coins", newBalance).apply()
        _coins.value = newBalance
    }

    fun deductCoins(amount: Int): Boolean {
        if (_coins.value >= amount) {
            val newBalance = _coins.value - amount
            prefs.edit().putInt("user_coins", newBalance).apply()
            _coins.value = newBalance
            return true
        }
        return false
    }

    fun incrementAdsWatched() {
        val newVal = _totalAdsWatched.value + 1
        prefs.edit().putInt("total_ads_watched", newVal).apply()
        _totalAdsWatched.value = newVal
    }

    fun addToHistory(prompt: String, imageUrl: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.prompt.equals(prompt, ignoreCase = true) }
        current.add(0, HistoryItem(prompt, imageUrl))
        if (current.size > 3) {
            current.removeAt(current.lastIndex)
        }
        _history.value = current
    }

    fun buildImageUrl(prompt: String): String {
        val encodedBytes = URLEncoder.encode(prompt, StandardCharsets.UTF_8.toString())
        // Pollinations.ai API configured beautifully
        return "https://image.pollinations.ai/prompt/$encodedBytes?width=1024&height=1024&nologo=true&private=true"
    }

    fun resetPreferencesForTesting() {
        prefs.edit().clear().apply()
        _coins.value = 50
        _totalAdsWatched.value = 0
        _hasSeenOnboarding.value = false
        _history.value = emptyList()
    }
}

// ==========================================
// GENERAL COMPOSABLE ROUTER
// ==========================================
@Composable
fun ImageGeneratorApp(
    adMobHelper: AdMobHelper,
    viewModel: ImageGeneratorViewModel = viewModel()
) {
    val context = LocalContext.current
    val hasSeenOnboarding by viewModel.hasSeenOnboarding.collectAsState()
    val coins by viewModel.coins.collectAsState()
    val history by viewModel.history.collectAsState()

    var showWatchAdPrompt by remember { mutableStateOf(false) }
    var activeGenerationPrompt by remember { mutableStateOf<String?>(null) }
    var activeGenerationUrl by remember { mutableStateOf<String?>(null) }
    var showSimulatedAdAdOverlay by remember { mutableStateOf(false) }
    var showPreviewOverlay by remember { mutableStateOf(false) }

    // Floating notification/shimmer
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasSeenOnboarding) {
            OnboardingScreen(
                onStarted = {
                    viewModel.completeOnboarding()
                    Toast.makeText(context, "Welcome Bonus! 🪙50 coins added.", Toast.LENGTH_LONG).show()
                }
            )
        } else {
            DashboardScreen(
                coins = coins,
                history = history,
                onEarnCoinsClicked = { showWatchAdPrompt = true },
                onGenerateImage = { prompt ->
                    if (coins < 10) {
                        showWatchAdPrompt = true
                    } else {
                        // Deduct Coins
                        if (viewModel.deductCoins(10)) {
                            activeGenerationPrompt = prompt
                            activeGenerationUrl = viewModel.buildImageUrl(prompt)
                            showPreviewOverlay = true
                        } else {
                            Toast.makeText(context, "Not enough coins!", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onHistoryItemClicked = { historicItem ->
                    activeGenerationPrompt = historicItem.prompt
                    activeGenerationUrl = historicItem.imageUrl
                    showPreviewOverlay = true
                },
                onResetTestData = {
                    viewModel.resetPreferencesForTesting()
                    Toast.makeText(context, "App reset! Coins reset to 50.", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Action Prompts & Sheets
        if (showWatchAdPrompt) {
            WatchAdPromptDialog(
                currentCoins = coins,
                onDismiss = { showWatchAdPrompt = false },
                onWatchAdClicked = {
                    showWatchAdPrompt = false
                    // Start Google AdMob routine
                    adMobHelper.loadAd(
                        onAdLoaded = {
                            adMobHelper.showAd(
                                onEarned = {
                                    viewModel.addCoins(50)
                                    viewModel.incrementAdsWatched()
                                    Toast.makeText(context, "Instant Reward: 🪙50 coins added!", Toast.LENGTH_LONG).show()
                                },
                                onClosed = {
                                    adMobHelper.clearLoadedAd()
                                }
                            )
                        },
                        onAdFailed = {
                            // Offline or Sandbox environment -> invoke Simulated Ad Overlay gracefully
                            showSimulatedAdAdOverlay = true
                        }
                    )
                }
            )
        }

        // Active Generation Preview Overlay
        if (showPreviewOverlay && activeGenerationPrompt != null && activeGenerationUrl != null) {
            GenerationPreviewOverlay(
                prompt = activeGenerationPrompt!!,
                imageUrl = activeGenerationUrl!!,
                onSaveToGallery = { bitmap ->
                    // Run async download helper
                    scope.launch {
                        val success = downloadAndSaveImage(
                            context = context,
                            imageUrl = activeGenerationUrl!!,
                            displayName = activeGenerationPrompt!!.replace(" ", "_").take(20)
                        )
                        if (success) {
                            Toast.makeText(context, "Saved beautifully to your Gallery!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to download image. Try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDone = {
                    // Save to local history list in memory
                    viewModel.addToHistory(activeGenerationPrompt!!, activeGenerationUrl!!)
                    showPreviewOverlay = false
                    activeGenerationPrompt = null
                    activeGenerationUrl = null
                },
                onDiscardAndCancel = {
                    // Refilled coins because generation dismissed without confirming
                    viewModel.addCoins(10)
                    showPreviewOverlay = false
                    activeGenerationPrompt = null
                    activeGenerationUrl = null
                    Toast.makeText(context, "Deduction refunded! 🪙10 coins returned.", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Simulated high-fidelity full screen ad video fallback
        if (showSimulatedAdAdOverlay) {
            SimulatedAdScreen(
                onCompleted = {
                    viewModel.addCoins(50)
                    viewModel.incrementAdsWatched()
                    showSimulatedAdAdOverlay = false
                    Toast.makeText(context, "Bonus Completed! 🪙50 coins added.", Toast.LENGTH_LONG).show()
                },
                onCancelled = {
                    showSimulatedAdAdOverlay = false
                    Toast.makeText(context, "Video skipped early. No bonus awarded.", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

// ==========================================
// SCREEN 1: ONBOARDING SPLASH VIEW (IMMERSIVE THEME)
// ==========================================
@Composable
fun OnboardingScreen(onStarted: () -> Unit) {
    val gradientBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF004A77), Color(0xFFA8C8FF))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111318))
            .padding(24.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Theme Logo Mock
        Box(
            modifier = Modifier
                .size(120.dp)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(gradientBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = "Dream AI Logo",
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "AI Image Studio",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            color = Color(0xFFE2E2E6),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Transform descriptive thoughts into stellar digital artwork instantly on your local device.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFC4C6D0),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(36.dp))

        // Economy Rules Display
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF44474E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                RuleItem(icon = Icons.Rounded.CardGiftcard, title = "Welcome Bonus", desc = "+50 free coins upon launch")
                Divider(color = Color(0xFF44474E), modifier = Modifier.padding(vertical = 12.dp))
                RuleItem(icon = Icons.Rounded.Brush, title = "Creation Cost", desc = "10 coins per generated graphic")
                Divider(color = Color(0xFF44474E), modifier = Modifier.padding(vertical = 12.dp))
                RuleItem(icon = Icons.Rounded.PlayCircle, title = "Earn Back Anytime", desc = "Register +50 unlimited coins by watching sponsored videos")
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onStarted,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD1E4FF), contentColor = Color(0xFF003258)),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(8.dp, shape = RoundedCornerShape(28.dp))
                .testTag("get_started_button"),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(
                "Claim 50 Coins & Start",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun RuleItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFA8C8FF),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFFE2E2E6), fontSize = 14.sp)
            Text(desc, color = Color(0xFF8E9199), fontSize = 12.sp)
        }
    }
}

// ==========================================
// SCREEN 1 EXTRA: DASHBOARD WORKSPACE VIEW (IMMERSIVE THEME)
// ==========================================
@Composable
fun DashboardScreen(
    coins: Int,
    history: List<ImageGeneratorViewModel.HistoryItem>,
    onEarnCoinsClicked: () -> Unit,
    onGenerateImage: (String) -> Unit,
    onHistoryItemClicked: (ImageGeneratorViewModel.HistoryItem) -> Unit,
    onResetTestData: () -> Unit
) {
    var promptInput by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("home") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Trending prompt categories
    val promptSuggestions = listOf(
        "cyberpunk cat in a high-tech armor",
        "ethereal floating island in cotton-candy clouds",
        "isometric miniature library inside a glass bulb",
        "mythical dragon perched on neon skyscrapers"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111318))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // TOP Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "AI VISION",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    color = Color(0xFFA8C8FF),
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Image Studio",
                    fontWeight = FontWeight.Medium,
                    fontSize = 20.sp,
                    color = Color(0xFFE2E2E6),
                    letterSpacing = (-0.5).sp
                )
            }

            // High-end coin balance display (Immersive theme)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF1C1B1F))
                    .border(1.dp, Color(0xFF44474E), CircleShape)
                    .clickable { onEarnCoinsClicked() }
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = "🪙 $coins",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD1E4FF),
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("coin_balance")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF004A77)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Earn coins",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Divider(color = Color(0xFF1C1B1F), thickness = 1.dp)

        // Workspace Navigation Router Content
        Box(modifier = Modifier.weight(1f)) {
            if (activeTab == "home") {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    // Quick Suggestion Row
                    Text(
                        text = "SUGGESTIONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC4C6D0),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(promptSuggestions) { suggestion ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF1C1B1F))
                                    .border(1.dp, Color(0xFF44474E), RoundedCornerShape(20.dp))
                                    .clickable {
                                        promptInput = suggestion
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = suggestion.take(24) + "...",
                                    color = Color(0xFFD1E4FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Outer glowing gradient container (Immersive UI input)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF004A77).copy(alpha = 0.4f), Color(0xFFA8C8FF).copy(alpha = 0.4f))
                                )
                            )
                            .padding(1.5.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                            shape = RoundedCornerShape(23.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = "CREATION PROMPT",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color(0xFFC4C6D0),
                                    letterSpacing = 1.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                TextField(
                                    value = promptInput,
                                    onValueChange = { promptInput = it },
                                    placeholder = {
                                        Text(
                                            "Describe an image...",
                                            color = Color(0xFF8E9199).copy(alpha = 0.6f),
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            fontSize = 15.sp
                                        )
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .testTag("prompt_input_text_field"),
                                    maxLines = 4
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "10 coins per generation",
                                        fontSize = 11.sp,
                                        color = Color(0xFF8E9199)
                                    )

                                    if (promptInput.isNotBlank()) {
                                        IconButton(
                                            onClick = { promptInput = "" },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Clear input",
                                                tint = Color(0xFF8E9199),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Core generate button
                    val canGenerate = promptInput.isNotBlank()
                    Button(
                        onClick = {
                            if (canGenerate) {
                                keyboardController?.hide()
                                onGenerateImage(promptInput.trim())
                            }
                        },
                        enabled = canGenerate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (coins >= 10) Color(0xFFD1E4FF) else Color(0xFFFF006E),
                            contentColor = if (coins >= 10) Color(0xFF003258) else Color.White,
                            disabledContainerColor = Color(0xFF1C1B1F),
                            disabledContentColor = Color(0xFF44474E)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .shadow(
                                elevation = if (canGenerate) 8.dp else 0.dp,
                                shape = RoundedCornerShape(28.dp)
                            )
                            .testTag("generate_image_button"),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(
                            imageVector = if (coins >= 10) Icons.Rounded.AutoAwesome else Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (coins >= 10) "Generate Image" else "Earn Coins (Need 10 Coins)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    // Recently Generated elements list
                    if (history.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = Color(0xFFC4C6D0),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "RECENTLY GENERATED",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC4C6D0),
                                    fontSize = 11.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                            Text(
                                text = "Last 3",
                                color = Color(0xFFA8C8FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        history.forEach { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .clickable { onHistoryItemClicked(item) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E3036)),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF44474E))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.Black)
                                            .border(1.dp, Color(0xFF44474E), RoundedCornerShape(10.dp))
                                    ) {
                                        AsyncImage(
                                            model = item.imageUrl,
                                            contentDescription = "Session mini preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.prompt,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "Ready to review",
                                            fontSize = 11.sp,
                                            color = Color(0xFFA8C8FF)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Icon(
                                        imageVector = Icons.Rounded.ArrowForwardIos,
                                        contentDescription = "View fully icon",
                                        tint = Color(0xFF8E9199),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Empty State Placeholder
                        Spacer(modifier = Modifier.height(36.dp))
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = "Empty state icon",
                                tint = Color(0xFF44474E),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Recently Generated Showcase",
                                fontSize = 12.sp,
                                color = Color(0xFF8E9199),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else if (activeTab == "vault") {
                // VAULT VIEW - FULL SESSIONS GRID
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Text(
                        text = "LOCAL CREATION VAULT",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFFC4C6D0),
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (history.isNotEmpty()) {
                        history.forEach { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .clickable { onHistoryItemClicked(item) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF44474E))
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black)
                                            .border(1.dp, Color(0xFF44474E), RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = item.imageUrl,
                                            contentDescription = "Vault preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.prompt,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            maxLines = 2
                                        )
                                        Text(
                                            text = "Stored locally on device",
                                            fontSize = 11.sp,
                                            color = Color(0xFFA8C8FF)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Icon(
                                        imageVector = Icons.Rounded.ArrowForwardIos,
                                        contentDescription = "Open preview",
                                        tint = Color(0xFF8E9199),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Empty Vault State
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF44474E), RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FolderSpecial,
                                    contentDescription = null,
                                    tint = Color(0xFF44474E),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Your Vault is Empty",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Images you generate will be kept in memory during this sandbox session.",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else if (activeTab == "setup") {
                // SETUP CONTROLS PANEL
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Text(
                        text = "DEVELOPMENT UTILITIES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFFC4C6D0),
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF44474E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "Database Clear & Hard Reset",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Completely wipes preference cache and resets your coin balance back to 50 welcome credits.",
                                color = Color(0xFF8E9199),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = onResetTestData,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("reset_test_data_button")
                            ) {
                                Icon(imageVector = Icons.Rounded.Restore, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Wipe SharedPreferences Local Cash", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // NAVIGATION BAR - IMMERSIVE LOOK (bg-[#1C1B1F]/80 backdrop border-t)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(Color(0xFF1C1B1F).copy(alpha = 0.85f))
                .border(BorderStroke(1.dp, Color(0xFF44474E)))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            // Home option
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { activeTab = "home" }
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Home,
                    contentDescription = "Home tab",
                    tint = if (activeTab == "home") Color(0xFFD1E4FF) else Color(0xFFC4C6D0),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Home",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (activeTab == "home") Color(0xFFD1E4FF) else Color(0xFFC4C6D0)
                )
            }

            // Vault option
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { activeTab = "vault" }
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = "Vault tab",
                    tint = if (activeTab == "vault") Color(0xFFD1E4FF) else Color(0xFFC4C6D0),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Vault",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (activeTab == "vault") Color(0xFFD1E4FF) else Color(0xFFC4C6D0)
                )
            }

            // Setup option
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { activeTab = "setup" }
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Setup tab",
                    tint = if (activeTab == "setup") Color(0xFFD1E4FF) else Color(0xFFC4C6D0),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Setup",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (activeTab == "setup") Color(0xFFD1E4FF) else Color(0xFFC4C6D0)
                )
            }
        }
    }
}

// ==========================================
// SCREEN 2: GENERATION PREVIEW SCREEN OVERLAY (FULL WINDOW MODAL)
// ==========================================
@Composable
fun GenerationPreviewOverlay(
    prompt: String,
    imageUrl: String,
    onSaveToGallery: (Bitmap) -> Unit,
    onDone: () -> Unit,
    onDiscardAndCancel: () -> Unit
) {
    var isApiLoadingFinished by remember { mutableStateOf(false) }
    var hasApiErrorOccurred by remember { mutableStateOf(false) }

    // Sequential loading animations steps
    var currentStep by remember { mutableStateOf(0) }
    val progressLabels = listOf(
        "Interpreting textual layout...",
        "Structuring neural matrix...",
        "Painting color pigments...",
        "Polishing high resolution detail..."
    )

    // Simulate ticking animation steps
    LaunchedEffect(isApiLoadingFinished) {
        if (!isApiLoadingFinished) {
            while (currentStep < progressLabels.size - 1) {
                delay(1200)
                currentStep++
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (isApiLoadingFinished) onDone() else onDiscardAndCancel()
        },
        properties = DialogProperties(
            dismissOnBackPress = isApiLoadingFinished,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111318).copy(alpha = 0.95f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Header details
                HeaderCardView(prompt = prompt)

                // Central Image view context
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1C1B1F))
                        .border(1.dp, Color(0xFF44474E), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // API Call with integrated Coil loaders
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "AI Generated Artwork",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            when (state) {
                                is coil.compose.AsyncImagePainter.State.Loading -> {
                                    isApiLoadingFinished = false
                                    hasApiErrorOccurred = false
                                }
                                is coil.compose.AsyncImagePainter.State.Success -> {
                                    isApiLoadingFinished = true
                                    hasApiErrorOccurred = false
                                }
                                is coil.compose.AsyncImagePainter.State.Error -> {
                                    isApiLoadingFinished = true
                                    hasApiErrorOccurred = true
                                }
                                else -> {}
                            }
                        }
                    )

                    // Spinner while building online
                    if (!isApiLoadingFinished) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFFA8C8FF),
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(48.dp)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = progressLabels[currentStep],
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Rendering via Pollinations Engine",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Error presentation if api breaks
                    if (isApiLoadingFinished && hasApiErrorOccurred) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Generation Failed",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Your coins will be returned instantly.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = onDiscardAndCancel,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("preview_error_refund_button")
                            ) {
                                Text("Refund & Clear Menu", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // CONTROLS DRAWER
                if (isApiLoadingFinished && !hasApiErrorOccurred) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Dismiss Cancel
                        OutlinedButton(
                            onClick = onDone,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFF44474E)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(0.4f)
                                .height(56.dp)
                                .testTag("preview_cancel_button")
                        ) {
                            Text("Dashboard", fontWeight = FontWeight.Medium)
                        }

                        // Save download
                        Button(
                            onClick = {
                                onSaveToGallery(BitmapFactory.decodeStream(null))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD1E4FF),
                                contentColor = Color(0xFF003258)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(0.6f)
                                .height(56.dp)
                                .testTag("preview_download_button")
                        ) {
                            Icon(imageVector = Icons.Rounded.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save to Gallery", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (!isApiLoadingFinished) {
                    // Letting user cancel during loading - coin will be refunded
                    Button(
                        onClick = onDiscardAndCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E3E), contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("preview_loading_cancel_button")
                    ) {
                        Text("Cancel & Refund Cost", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderCardView(prompt: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
        border = BorderStroke(1.dp, Color(0xFF44474E)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color(0xFFA8C8FF),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Prompt Artwork", fontSize = 11.sp, color = Color.Gray)
                Text(
                    text = "\"$prompt\"",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 2
                )
            }
        }
    }
}

// ==========================================
// SCREEN 3: AD PROMPT POPUP DIALOG
// ==========================================
@Composable
fun WatchAdPromptDialog(
    currentCoins: Int,
    onDismiss: () -> Unit,
    onWatchAdClicked: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1B1F),
        titleContentColor = Color.White,
        textContentColor = Color.Gray,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.CardGiftcard,
                    contentDescription = null,
                    tint = Color(0xFFA8C8FF),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Earn Coins instantly!")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "You currently have $currentCoins coins. Sponsored image rendering costs 10 coins per generation.",
                    fontSize = 14.sp
                )
                Text(
                    "Watch a short video ad to instantly receive 50 coins completely free!",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD1E4FF),
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onWatchAdClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD1E4FF),
                    contentColor = Color(0xFF003258)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("ad_prompt_watch_button")
            ) {
                Text("Watch Ad (+50 Coins)", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("ad_prompt_cancel_button")
            ) {
                Text("Later", color = Color.LightGray)
            }
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    )
}

// ==========================================
// patrocinadores FALLBACK INTERACTIVE SCREEN (SIMULATOR FOR EMULATOR)
// ==========================================
@Composable
fun SimulatedAdScreen(
    onCompleted: () -> Unit,
    onCancelled: () -> Unit
) {
    var timerCount by remember { mutableStateOf(5) }

    LaunchedEffect(Unit) {
        while (timerCount > 0) {
            delay(1000)
            timerCount--
        }
        onCompleted()
    }

    Dialog(
        onDismissRequest = {}, // Force watching simulated video
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111318))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF004A77))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Sponsored Ad", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    // Countdown badge
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF1C1B1F))
                            .border(BorderStroke(1.dp, Color(0xFF44474E)))
                            .size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$timerCount",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD1E4FF),
                            fontSize = 14.sp
                        )
                    }
                }

                // Sponsored mockup graphic view
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF004A77), Color(0xFF1C1B1F))
                            )
                        )
                        .border(1.dp, Color(0xFF44474E), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayCircle,
                            contentDescription = "Simulated video ad icon",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "Dream Engine Premium Add-on",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Rendering artwork matching your dreams",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Reward will be credited automatically in $timerCount seconds.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    LinearProgressIndicator(
                        progress = { (5 - timerCount) / 5f },
                        color = Color(0xFFD1E4FF),
                        trackColor = Color(0xFF1C1B1F),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                }

                // Skip option
                TextButton(
                    onClick = onCancelled,
                    modifier = Modifier.testTag("skip_simulated_ad_button")
                ) {
                    Text(
                        "Skip Ad & Forfeit Reward",
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ==========================================
// SUSPENDING PIPELINE SAVING IMAGES TO GALLERY
// ==========================================
suspend fun downloadAndSaveImage(
    context: Context,
    imageUrl: String,
    displayName: String
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.connect()
            val input: InputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(input) ?: return@withContext false

            val resolver = context.contentResolver
            val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val imageDetails = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AI_Generator")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(imageCollection, imageDetails) ?: return@withContext false

            resolver.openOutputStream(imageUri).use { outputStream ->
                if (outputStream == null) return@withContext false
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                imageDetails.clear()
                imageDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, imageDetails, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
