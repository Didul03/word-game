@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.madwordguess

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/* ---------------------------------------------------------
 * Activity
 * --------------------------------------------------------- */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    dynamicLightColorScheme(this)
                else
                    lightColorScheme()

            MaterialTheme(colorScheme = colors) { AppRoot() }
        }
    }
}

/* ---------------------------------------------------------
 * Prefs & Round history store
 * --------------------------------------------------------- */
object Prefs {
    private const val FILE = "mad_word_guess_prefs"
    private const val KEY_NAME = "player_name"
    private const val KEY_HISTORY = "history"

    fun getName(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_NAME, "") ?: ""

    fun setName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_NAME, name).apply()
    }

    fun loadHistory(ctx: Context): List<RoundRecord> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<RoundRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += RoundRecord(
                    word = o.optString("word"),
                    score = o.optInt("score"),
                    seconds = o.optInt("seconds"),
                    attempts = o.optInt("attempts"),
                    success = o.optBoolean("success", false),
                    level = o.optInt("level"),
                    timestamp = o.optLong("ts")
                )
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    fun saveHistory(ctx: Context, list: List<RoundRecord>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
            o.put("word", it.word)
            o.put("score", it.score)
            o.put("seconds", it.seconds)
            o.put("attempts", it.attempts)
            o.put("success", it.success)
            o.put("level", it.level)
            o.put("ts", it.timestamp)
            arr.put(o)
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}

data class RoundRecord(
    val word: String,
    val score: Int,
    val seconds: Int,
    val attempts: Int,
    val success: Boolean,
    val level: Int,
    val timestamp: Long
)

/* ---------------------------------------------------------
 * Random Word API (ref [2])
 * --------------------------------------------------------- */
interface RandomWordApi {
    @GET("word")
    suspend fun getWord(@Query("number") number: Int = 1): List<String>
}
private fun provideWordApi(): RandomWordApi =
    Retrofit.Builder()
        .baseUrl("https://random-word-api.herokuapp.com/")
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
        .build()
        .create(RandomWordApi::class.java)

/* ---------------------------------------------------------
 * Dreamlo leaderboard (ref [5])
 * --------------------------------------------------------- */
object Dreamlo {
    // TODO: replace with your real codes before submitting
    const val PUBLIC_CODE = "DREAMLO_PUBLIC_CODE"
    const val PRIVATE_CODE = "DREAMLO_PRIVATE_CODE"

    data class Entry(val name: String, val score: Int, val seconds: Int)

    suspend fun submit(name: String, score: Int, seconds: Int) {
        if (PUBLIC_CODE.startsWith("DREAMLO")) return
        val safeName = URLEncoder.encode(name.replace("/", "_"), "UTF-8")
        val url = "http://dreamlo.com/lb/$PRIVATE_CODE/add/$safeName/$score/$seconds"
        runCatching {
            okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(url).build()
            ).execute().close()
        }
    }

    suspend fun fetchTop(): List<Entry> = withContext(Dispatchers.IO) {
        if (PUBLIC_CODE.startsWith("DREAMLO")) return@withContext emptyList<Entry>()
        val url = "http://dreamlo.com/lb/$PUBLIC_CODE/json"
        val resp = okhttp3.OkHttpClient()
            .newCall(okhttp3.Request.Builder().url(url).build())
            .execute()
        if (!resp.isSuccessful) return@withContext emptyList<Entry>()
        val body = resp.body?.string().orEmpty()
        try {
            val root = JSONObject(body)
            val lb = root.optJSONObject("dreamlo")?.optJSONObject("leaderboard")
                ?: return@withContext emptyList<Entry>()
            val entry = lb.opt("entry") ?: return@withContext emptyList<Entry>()
            val out = mutableListOf<Entry>()
            fun add(o: JSONObject) {
                out += Entry(
                    name = o.optString("name"),
                    score = o.optString("score").toIntOrNull() ?: 0,
                    seconds = o.optString("seconds").toIntOrNull() ?: 0
                )
            }
            if (entry is JSONArray) for (i in 0 until entry.length()) add(entry.getJSONObject(i))
            else if (entry is JSONObject) add(entry)
            out.sortWith(compareByDescending<Entry> { it.score }.thenBy { it.seconds })
            out.take(20)
        } catch (_: Exception) { emptyList() }
    }
}

/* ---------------------------------------------------------
 * Game state + ViewModel
 * --------------------------------------------------------- */
data class GameState(
    val player: String = "",
    val secret: String = "",
    val score: Int = 100,
    val attempts: Int = 0,
    val tipUsed: Boolean = false,
    val startedAt: Long = 0L,
    val level: Int = 1,
    val loading: Boolean = false,
    val status: String = "",
)

class GameVm(private val api: RandomWordApi) : ViewModel() {

    var state by mutableStateOf(GameState(loading = true))
        private set

    /** UI can set this to capture round finishes and persist history */
    var onRoundFinished: ((RoundRecord) -> Unit)? = null

    private var tickJob: Job? = null

    fun attachPlayer(name: String) { state = state.copy(player = name) }

    fun timeElapsedSeconds(): Int =
        if (state.startedAt == 0L) 0 else ((SystemClock.elapsedRealtime() - state.startedAt) / 1000).toInt()

    fun start(level: Int = 1) {
        viewModelScope.launch {
            tickJob?.cancel()
            state = state.copy(loading = true, level = level)
            val minLen = max(3, 3 + (level - 1)) // longer words for higher levels
            val word = fetchWordMinLength(minLen)
            state = GameState(
                player = state.player,
                secret = word,
                score = 100,
                attempts = 0,
                tipUsed = false,
                startedAt = SystemClock.elapsedRealtime(),
                level = level,
                loading = false,
                status = "Guess the word!"
            )
            tickJob = viewModelScope.launch { while (isActive) delay(1000) } // tick for timer UI
        }
    }

    private suspend fun fetchWordMinLength(minLen: Int): String {
        repeat(4) {
            val w = api.getWord().firstOrNull()?.lowercase() ?: "android"
            if (w.length >= minLen) return w
        }
        return "android" // fallback
    }

    private fun normalize(w: String): String =
        w.trim().lowercase().filter { it in 'a'..'z' }

    private fun buildRecord(success: Boolean, finalScore: Int, finalAttempts: Int): RoundRecord =
        RoundRecord(
            word = state.secret,
            score = finalScore,
            seconds = timeElapsedSeconds(),
            attempts = finalAttempts,
            success = success,
            level = state.level,
            timestamp = System.currentTimeMillis()
        )

    fun guess(input: String) {
        val s = state
        if (s.loading) return
        if (s.attempts >= 10 || s.score <= 0) return

        val user = normalize(input)
        val secretNorm = normalize(s.secret)

        if (user.isEmpty()) {
            state = s.copy(status = "Please type a word.")
            return
        }

        if (user == secretNorm) {
            val secs = timeElapsedSeconds()
            val status = "✅ Correct! (${s.secret}) in ${secs}s • Score ${s.score}"
            state = s.copy(status = status)
            onRoundFinished?.invoke(buildRecord(success = true, finalScore = s.score, finalAttempts = s.attempts + 1))
            viewModelScope.launch { Dreamlo.submit(s.player, s.score + max(0, 20 - secs), secs) }
        } else {
            val newAttempts = s.attempts + 1
            val newScore = max(0, s.score - 10)
            val end = newAttempts >= 10 || newScore == 0
            val msg = if (end) "❌ Failed! It was \"${s.secret}\". Score 0."
            else "Wrong ${newAttempts}/10 • Score $newScore"
            state = s.copy(attempts = newAttempts, score = newScore, status = msg)
            if (end) {
                onRoundFinished?.invoke(buildRecord(success = false, finalScore = newScore, finalAttempts = newAttempts))
            }
        }
    }

    fun revealLength(): String {
        val s = state
        if (s.score < 5) {
            state = s.copy(status = "Not enough points.")
            return "Not enough points."
        }
        val newScore = s.score - 5
        val msg = "Letters: ${s.secret.length}"
        state = s.copy(score = newScore, status = msg)
        return msg
    }

    fun letterCount(ch: String): String {
        val s = state
        if (s.score < 5) {
            state = s.copy(status = "Not enough points.")
            return "Not enough points."
        }
        val l = ch.trim().lowercase()
        if (l.length != 1 || l[0] !in 'a'..'z') {
            state = s.copy(status = "Enter one letter (a-z).")
            return "Enter one letter (a-z)."
        }
        val count = s.secret.count { it == l[0] }
        val newScore = s.score - 5
        val msg = if (count == 1) "There is 1 \"$l\"." else "There are $count \"$l\"."
        state = s.copy(score = newScore, status = msg)
        return msg
    }

    suspend fun tip(): String {
        val s = state
        if (s.attempts < 5) {
            state = s.copy(status = "Tip after 5 attempts.")
            return "Tip after 5 attempts."
        }
        if (s.tipUsed) {
            state = s.copy(status = "Tip already used.")
            return "Tip already used."
        }
        val msg = "Starts with \"${s.secret.first().uppercase()}\"."
        state = s.copy(tipUsed = true, status = msg)
        return msg
    }
}

/* ---------------------------------------------------------
 * Nav & Root
 * --------------------------------------------------------- */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Play : Screen("play", "Play", Icons.Filled.Home)
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.BarChart)
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current

    // name
    var name by remember { mutableStateOf(Prefs.getName(ctx)) }

    // history
    var history by remember { mutableStateOf(Prefs.loadHistory(ctx)) }
    fun addHistory(rec: RoundRecord) {
        history = history + rec
        Prefs.saveHistory(ctx, history)
    }
    fun clearHistory() {
        history = emptyList()
        Prefs.saveHistory(ctx, history)
    }

    val nav = rememberNavController()

    // VM (shared across tabs)
    val vm = remember { GameVm(provideWordApi()) }
    LaunchedEffect(name) { vm.attachPlayer(name); vm.start(level = 1) }
    LaunchedEffect(Unit) { vm.onRoundFinished = { addHistory(it) } }

    Scaffold(
        bottomBar = {
            val items = listOf(Screen.Play, Screen.Dashboard)
            NavigationBar {
                val backStack by nav.currentBackStackEntryAsState()
                val currentDest = backStack?.destination
                items.forEach { scr ->
                    val selected = currentDest?.hierarchy?.any { it.route == scr.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(scr.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(scr.icon, contentDescription = scr.label) },
                        label = { Text(scr.label) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = Screen.Play.route,
            modifier = Modifier.padding(pad)
        ) {
            composable(Screen.Play.route) {
                PlayScreen(
                    vm = vm,
                    onRename = { newName -> Prefs.setName(ctx, newName); name = newName },
                    onShowDashboard = { nav.navigate(Screen.Dashboard.route) }
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    playerName = name,
                    history = history,
                    onClearHistory = { clearHistory() },
                    onRename = { newName -> Prefs.setName(ctx, newName); name = newName }
                )
            }
        }
    }
}

/* ---------------------------------------------------------
 * Play Screen (the game)
 * --------------------------------------------------------- */
@Composable
fun PlayScreen(
    vm: GameVm,
    onRename: (String) -> Unit,
    onShowDashboard: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Leaderboard state
    var showLb by remember { mutableStateOf(false) }
    var lbEntries by remember { mutableStateOf<List<Dreamlo.Entry>>(emptyList()) }

    // Settings/Dashboard sheet
    var showSettings by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var nameEdit by remember { mutableStateOf(vm.state.player) }

    // Letter dialog state
    var showLetterDialog by remember { mutableStateOf(false) }
    var letterInput by remember { mutableStateOf("") }

    // First-guess clue prompt state
    var showCluePrompt by remember { mutableStateOf(false) }
    var offeredClueThisRound by remember(vm.state.startedAt) { mutableStateOf(false) }

    // Developer: show secret answer
    var showAnswer by remember { mutableStateOf(false) }

    val s = vm.state
    val seconds = vm.timeElapsedSeconds()

    useHapticsOnStatus(s.status)

    Scaffold(
        topBar = {
            FancyTopBar(
                title = "Hi, ${s.player} — Level ${s.level}",
                iconTint = Color.Black,
                onSettings = { showSettings = true },
                onLeaderboard = {
                    showLb = true
                    scope.launch { lbEntries = Dreamlo.fetchTop() }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AssistChip(onClick = {}, label = { Text("Hello, ${s.player} 👋") })
            ScoreRow(score = s.score, attempts = s.attempts, seconds = seconds, modifier = Modifier.padding(top = 8.dp))

            if (showAnswer) {
                Spacer(Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text("🔒 Secret (debug): ${s.secret}") })
            }

            Spacer(Modifier.height(12.dp))
            Text("Guess the word", style = MaterialTheme.typography.titleMedium)

            var guess by remember { mutableStateOf("") }

            GuessField(
                value = guess,
                onChange = { guess = it },
                onSubmit = {
                    if (!offeredClueThisRound && s.attempts == 0 && s.score >= 5 && !s.loading) {
                        offeredClueThisRound = true
                        showCluePrompt = true
                    } else {
                        vm.guess(guess); guess = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!offeredClueThisRound && s.attempts == 0 && s.score >= 5 && !s.loading) {
                            offeredClueThisRound = true
                            showCluePrompt = true
                        } else {
                            vm.guess(guess); guess = ""
                        }
                    },
                    enabled = !s.loading
                ) { Text("Guess") }

                OutlinedButton(
                    onClick = { vm.revealLength() },
                    enabled = !s.loading
                ) { Text("How many letters? (−5)") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Clues", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showLetterDialog = true }) {
                    Icon(Icons.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Letter occurrences (−5)")
                }
                FilledTonalButton(
                    onClick = { scope.launch { vm.tip() } },
                    enabled = s.attempts >= 5 && !s.tipUsed
                ) { Text("Get tip (after 5 tries)") }
            }

            Spacer(Modifier.height(16.dp))
            CelebrationBanner(
                visible = s.status.startsWith("✅"),
                text = s.status
            )
            if (!s.status.startsWith("✅")) {
                Text(s.status, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(16.dp))
            val roundOver = s.attempts >= 10 || s.score == 0 || s.status.startsWith("✅")
            if (roundOver) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.start(level = s.level + 1) }) { Text("Next Level") }
                    OutlinedButton(onClick = { vm.start(level = 1) }) { Text("Restart") }
                }
            }
        }

        // Leaderboard dialog
        if (showLb) {
            LeaderboardDialog(entries = lbEntries, onClose = { showLb = false })
        }

        // Letter dialog
        if (showLetterDialog) {
            AlertDialog(
                onDismissRequest = { showLetterDialog = false },
                title = { Text("Check letter") },
                text = {
                    OutlinedTextField(
                        value = letterInput,
                        onValueChange = { if (it.length <= 1) letterInput = it },
                        label = { Text("Enter one letter (a-z)") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.letterCount(letterInput)
                        letterInput = ""
                        showLetterDialog = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showLetterDialog = false }) { Text("Cancel") }
                }
            )
        }

        // First-guess clue prompt
        if (showCluePrompt) {
            CluePromptDialog(
                onLength = { vm.revealLength(); showCluePrompt = false },
                onLetter = { showLetterDialog = true; showCluePrompt = false },
                onProceedGuess = {
                    // No-op here; user will press Guess again or use the field IME action
                    showCluePrompt = false
                },
                canAfford = s.score >= 5
            )
        }

        // Settings & quick dashboard sheet (rename + actions + debug)
        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = sheetState
            ) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    Text("Settings & Dashboard", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("Profile", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = nameEdit,
                        onValueChange = { nameEdit = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                val nn = nameEdit.trim()
                                if (nn.isNotBlank()) {
                                    onRename(nn)
                                    vm.attachPlayer(nn)
                                    showSettings = false
                                }
                            },
                            enabled = nameEdit.trim().isNotEmpty() && nameEdit.trim() != s.player
                        ) { Text("Save") }
                        OutlinedButton(onClick = { nameEdit = s.player }) { Text("Reset") }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.start(level = 1) }) { Text("Restart (Level 1)") }
                        OutlinedButton(onClick = { vm.start(level = s.level + 1) }) { Text("Next Level") }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Debug (don’t enable in demo)", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Show correct word")
                        Switch(checked = showAnswer, onCheckedChange = { showAnswer = it })
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

/* ---------------------------------------------------------
 * Dashboard Screen (history + aggregates + rename)
 * --------------------------------------------------------- */
@Composable
fun DashboardScreen(
    playerName: String,
    history: List<RoundRecord>,
    onClearHistory: () -> Unit,
    onRename: (String) -> Unit
) {
    var showRename by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var nameEdit by remember(playerName) { mutableStateOf(playerName) }

    val bestScore = history.filter { it.success }.maxByOrNull { it.score }?.score ?: 0
    val fastest = history.filter { it.success }.minByOrNull { it.seconds }?.seconds ?: 0
    val games = history.size
    val highestLevel = history.maxByOrNull { it.level }?.level ?: 1

    Scaffold(
        topBar = {
            FancyTopBar(
                title = "Dashboard",
                iconTint = Color.Black,
                onSettings = { showRename = true },
                onLeaderboard = {}
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Hello, $playerName 👋", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // 2-column grid for stats (no weight)
            val cards = listOf(
                "Best Score" to (if (bestScore == 0) "—" else "$bestScore"),
                "Fastest" to (if (fastest == 0) "—" else "%02d:%02d".format(fastest / 60, fastest % 60)),
                "Games" to "$games",
                "Highest Lv" to "$highestLevel"
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false
            ) {
                items(cards) { (title, value) ->
                    StatCard(title = title, value = value, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Previous rounds", style = MaterialTheme.typography.titleMedium)
            if (history.isEmpty()) {
                Text("No records yet. Play a game to see history here.")
            } else {
                val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(history.asReversed()) { _, r ->
                        val icon = if (r.success) "✅" else "❌"
                        val timeText = "%02d:%02d".format(r.seconds/60, r.seconds%60)
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Text("$icon  ${r.word} — L${r.level}", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Score ${r.score} • Attempts ${r.attempts}/10 • Time $timeText")
                                Text(fmt.format(r.timestamp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClearHistory) { Text("Clear history") }
            }
        }

        if (showRename) {
            ModalBottomSheet(
                onDismissRequest = { showRename = false },
                sheetState = sheetState
            ) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    Text("Change your name", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameEdit,
                        onValueChange = { nameEdit = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                val nn = nameEdit.trim()
                                if (nn.isNotBlank()) {
                                    onRename(nn)
                                    showRename = false
                                }
                            },
                            enabled = nameEdit.trim().isNotEmpty() && nameEdit.trim() != playerName
                        ) { Text("Save") }
                        OutlinedButton(onClick = { nameEdit = playerName }) { Text("Reset") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(modifier) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/* ---------------------------------------------------------
 * Fancy Top Bar with gradient (black icons by default)
 * --------------------------------------------------------- */
@Composable
fun FancyTopBar(
    title: String,
    iconTint: Color = Color.Black,
    onSettings: () -> Unit,
    onLeaderboard: () -> Unit
) {
    Box {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(bottom = 24.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
                        )
                    ),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
        )
        CenterAlignedTopAppBar(
            title = { Text(title, color = iconTint) },
            actions = {
                IconButton(onClick = onLeaderboard) {
                    Icon(Icons.Filled.List, contentDescription = "Leaderboard", tint = iconTint)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = iconTint)
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
        )
    }
}

/* ---------------------------------------------------------
 * Shared UI bits
 * --------------------------------------------------------- */
@Composable
fun ScoreRow(score: Int, attempts: Int, seconds: Int, modifier: Modifier = Modifier) {
    val animScore by animateIntAsState(targetValue = score, label = "score")
    val timeText = "%02d:%02d".format(seconds / 60, seconds % 60)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Score", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("$animScore", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Attempts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                AttemptDots(used = attempts, max = 10)
            }
        }
        AssistChip(onClick = {}, label = { Text("⏱ $timeText") })
    }
}

@Composable
fun AttemptDots(used: Int, max: Int, size: Dp = 10.dp, gap: Dp = 6.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(max) { i ->
            val filled = i < used
            Box(
                Modifier
                    .size(size)
                    .background(
                        if (filled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun CelebrationBanner(visible: Boolean, text: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "🎉 $text",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun GuessField(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Type your guess") },
        supportingText = { Text("Wrong guess −10 points • 10 total attempts") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onSubmit) {
                Icon(Icons.Filled.List, contentDescription = "Submit guess")
            }
        },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() })
    )
}

@Composable
fun useHapticsOnStatus(status: String) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(status) {
        if (status.startsWith("✅")) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        else if (status.startsWith("❌") || status.startsWith("Wrong")) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
    }
}

/* Dialogs */
@Composable
fun LeaderboardDialog(entries: List<Dreamlo.Entry>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Leaderboard") },
        text = {
            if (entries.isEmpty()) Text("No entries yet.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(entries) { i, e ->
                    val prefix = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }
                    ListItem(
                        headlineContent = { Text("$prefix ${e.name}") },
                        supportingContent = { Text("${e.score} pts • ${e.seconds}s") }
                    )
                    Divider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
fun CluePromptDialog(
    onLength: () -> Unit,
    onLetter: () -> Unit,
    onProceedGuess: () -> Unit,
    canAfford: Boolean
) {
    AlertDialog(
        onDismissRequest = onProceedGuess,
        title = { Text("Use a clue first?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You can take a clue before your first guess.")
                Text("• How many letters? (−5 points)\n• Check occurrences of a letter (−5 points)\n• Or guess now with no cost.")
            }
        },
        confirmButton = { TextButton(onClick = onProceedGuess) { Text("Guess now") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLength, enabled = canAfford) { Text("Length (−5)") }
                TextButton(onClick = onLetter, enabled = canAfford) { Text("Letter (−5)") }
            }
        }
    )
}
