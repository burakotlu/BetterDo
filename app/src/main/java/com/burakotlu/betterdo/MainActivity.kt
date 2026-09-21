package com.burakotlu.betterdo

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Forest = Color(0xFF183E34)
private val Cream = Color(0xFFF7F8F4)
private val Lime = Color(0xFFDEEDBA)
private val Muted = Color(0xFF637460)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White,
                primaryContainer = Lime, onPrimaryContainer = Forest, background = Cream,
                surface = Cream, onSurface = Forest, secondaryContainer = Color(0xFFE8EDDE), outline = Color(0xFF778870))) {
                BetterDoApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BetterDoApp(model: LearningViewModel = viewModel()) {
    val videoSettings: VideoSettings = viewModel()
    var showVideoSettings by remember { mutableStateOf(false) }
    if (showVideoSettings) VideoSettingsDialog(videoSettings) { showVideoSettings = false }
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var languageMenu by remember { mutableStateOf(false) }
    var speechMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val speech = remember { SpeechPlayer(context) { speechMessage = it } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) speech.stop()
            if (event == Lifecycle.Event.ON_RESUME) model.onResume()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); speech.close() }
    }
    LaunchedEffect(state.language, state.today) { selectedId = null; speech.stop() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, speechMessage) {
        val text = speechMessage ?: state.message
        if (text != null) { snackbar.showSnackbar(text, withDismissAction = true); speechMessage = null; model.dismissMessage() }
    }
    BackHandler(selectedId != null || tab != 0) { if (selectedId != null) selectedId = null else tab = 0 }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name) + ".", fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp) },
                actions = {
                    IconButton(onClick = { model.refreshCatalog() }, enabled = !state.syncing && !state.loading) {
                        if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Sync, contentDescription = "Refresh lessons")
                    }
                    Box {
                        TextButton(onClick = { languageMenu = true }, enabled = !state.loading && !state.fatalError) {
                            Text(if (state.language == "en") "EN · English" else "DE · German", fontSize = 13.sp)
                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose a language")
                        }
                        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            listOf("en" to "English", "de" to "German").forEach { (code, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { speech.stop(); model.changeLanguage(code); languageMenu = false; selectedId = null })
                            }
                        }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream))
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val labels = listOf("Today", "Review", "My words")
                val icons = listOf(Icons.Outlined.WbSunny, Icons.Outlined.Refresh, Icons.Outlined.MenuBook)
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, enabled = !state.loading && !state.fatalError,
                        onClick = { tab = index; selectedId = null; speech.stop() },
                        icon = { BadgedBox(badge = { if (index == 1 && state.due.isNotEmpty()) Badge { Text(state.due.size.toString()) } }) { Icon(icons[index], contentDescription = null) } },
                        label = { Text(label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(48.dp))
                !state.catalogReady -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EmptyCard("Download your first lessons", state.syncError ?: "Download lessons online, then use them offline.")
                    Button(onClick = { model.refreshCatalog() }, enabled = !state.syncing) { Text("Try again") }
                }
                else -> {
                    val selected = state.catalog.find { it.id == selectedId }
                    val lesson = selected ?: if (tab == 0) state.daily else null
                    if (lesson != null) {
                        key(lesson.id, state.today, tab) {
                            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (selected != null) TextButton(onClick = { selectedId = null; speech.stop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text(if (tab == 0) " Back to today's word" else " Back to the list") }
                                if (tab == 0) Intro(state)
                                if (state.syncError != null) Text(state.syncError.orEmpty(), color = Muted, fontSize = 12.sp)
                                if (!state.writable) Text("Saved progress could not be read. Changes in this session are not being saved.", color = MaterialTheme.colorScheme.error)
                                LessonContent(lesson, state, model, speech) { if (tab == 1) selectedId = null }
                                AiVideoSection(lesson, videoSettings, { showVideoSettings = true }, { speech.stop() })
                                if (tab == 0 && state.course.cards[lesson.id]?.lastPracticed == state.today) {
                                    val next = Scheduler.nextUnseen(state.available, state.course)
                                    if (next != null) {
                                        Button(onClick = { speech.stop(); selectedId = next.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                            Text("Learn another word")
                                        }
                                    } else {
                                        EmptyCard("You have explored every published word", "There is no daily limit. Refresh for newly published lessons, or revisit your words while more lessons are added.")
                                        OutlinedButton(onClick = { selectedId = null; tab = 2; speech.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Explore my words") }
                                    }
                                }
                                Text("A little progress, every day.", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp))
                            }
                        }
                    } else if (tab == 0) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            EmptyCard("New lessons are on the way", "No lessons have been published in this language yet. Choose another language or refresh the lessons.")
                            state.syncError?.let { Text(it, color = Muted) }
                            Button(onClick = { model.refreshCatalog() }, enabled = !state.syncing) { Text("Refresh lessons") }
                        }
                    } else {
                        val list = if (tab == 1) state.due else state.catalog.filter { it.id in state.course.cards }
                        WordList(list, state, tab == 1, { selectedId = it.id }, { tab = 0 })
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(state: LearningState) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(state.today.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.ENGLISH)).uppercase(Locale.ENGLISH), fontSize = 10.sp, letterSpacing = 1.6.sp, color = Muted)
        Text("One more word today.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Text(stringResource(R.string.app_motto), color = Muted, fontSize = 13.sp)
        val practiced = Scheduler.practicedToday(state.course, state.today)
        Text(if (practiced == 0) "Daily minimum: practice 1 word. Keep going whenever you like."
            else "Daily goal reached! $practiced ${if (practiced == 1) "word" else "words"} practiced today. No daily limit.", color = Muted, fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("${Scheduler.streak(state.course.activity, state.today)}", "day streak", Modifier.weight(1f))
        Stat("${state.course.cards.values.count { it.learned }}", "learned", Modifier.weight(1f))
        Stat("${state.due.size}", "due", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text(label, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
private fun LessonContent(lesson: Lesson, state: LearningState, model: LearningViewModel, speech: SpeechPlayer, onDone: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("WORD PRACTICE · ${lesson.level}", color = Lime, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Text(lesson.word, color = Color.White, fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp)
            Text("${lesson.pronunciation} · ${lesson.partOfSpeech}", color = Color(0xFFC5D6C7), fontSize = 12.sp)
            Text(lesson.meaning, color = Lime, fontSize = 22.sp)
            Text(lesson.context, color = Color(0xFFD0DED2), fontSize = 13.sp, lineHeight = 21.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { speech.speak(lesson.word, lesson.language) }, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Forest), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Listen")
                }
                TextButton(onClick = { speech.speak(lesson.word, lesson.language, true) }, colors = ButtonDefaults.textButtonColors(contentColor = Lime)) { Text("Listen slowly") }
            }
        }
    }
    Panel("Everyday examples", "Words are easier to remember in context.") {
        lesson.examples.forEachIndexed { index, example ->
            if (index > 0) HorizontalDivider(color = Color(0xFFEDF0E9))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("0${index + 1}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(example.text, fontSize = 15.sp)
                    if (example.translation != example.text) Text(example.translation, fontSize = 12.sp, color = Muted)
                }
                IconButton(onClick = { speech.speak(example.text, lesson.language) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Listen to example ${index + 1}", tint = Muted) }
            }
        }
    }
    Panel("A short conversation", lesson.dialogueContext, Color(0xFFEDF1E4)) {
        lesson.dialogue.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Text(line.speaker, fontWeight = FontWeight.Bold, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, end = 10.dp))
                Surface(Modifier.weight(1f), color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(line.text, fontSize = 14.sp)
                        if (line.translation != line.text) Text(line.translation, fontSize = 12.sp, color = Muted)
                    }
                }
                IconButton(onClick = { speech.speak(line.text, lesson.language) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Listen to speaker ${line.speaker}", tint = Muted) }
            }
        }
    }
    val answers = state.answers[lesson.id].orEmpty()
    Panel("Your turn", "Check what you learned with two quick questions.") {
        lesson.quiz.forEachIndexed { index, quiz ->
            Text("${index + 1}. ${quiz.question}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            quiz.options.forEachIndexed { optionIndex, option ->
                val chosen = answers[index] == optionIndex
                val color = if (!chosen) Color.White else if (optionIndex == quiz.answer) Lime else Color(0xFFFFE5DC)
                OutlinedButton(onClick = { model.answer(lesson, index, optionIndex) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = color), contentPadding = PaddingValues(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (chosen) { Icon(if (optionIndex == quiz.answer) Icons.Outlined.Check else Icons.Outlined.Close, if (optionIndex == quiz.answer) "Correct answer" else "Incorrect answer", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(option, fontSize = 13.sp)
                    }
                }
            }
            answers[index]?.let { answer ->
                Text(if (answer == quiz.answer) "Correct! ${quiz.explanation}" else "Not quite. Check the examples and try again.", color = if (answer == quiz.answer) Muted else Color(0xFF98442E), fontSize = 12.sp)
            }
        }
    }
    val practiced = state.course.cards[lesson.id]?.lastPracticed == state.today
    if (practiced) Text("Today’s practice is complete. Next review: ${state.course.cards.getValue(lesson.id).due.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))}", color = Muted, fontSize = 13.sp)
    Button(onClick = { model.practice(lesson, true); onDone() }, enabled = lesson.quiz.indices.all { answers[it] == lesson.quiz[it].answer }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp)); Text("Mark as learned")
    }
    OutlinedButton(onClick = { model.practice(lesson, false); onDone() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review tomorrow") }
    Text("Answer both questions correctly to mark this word as learned. Review intervals: 1, 3, 7, 14, 30, and 60 days.", color = Muted, fontSize = 11.sp)
}

@Composable
private fun Panel(title: String, subtitle: String, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = color) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp)
            content()
        }
    }
}

@Composable
private fun WordList(lessons: List<Lesson>, state: LearningState, review: Boolean, select: (Lesson) -> Unit, goToday: () -> Unit) {
    var search by rememberSaveable(state.language, review) { mutableStateOf("") }
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(if (review) "A quick refresher." else "Your word library.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (lessons.isEmpty()) {
            EmptyCard(if (review) "You are all caught up." else "Your first word is waiting.", if (review) "No reviews are due. Your words will appear here when it is time to review them." else "Complete a lesson to start building your word library.")
            Button(onClick = goToday) { Text("Back to today’s lesson") }
        } else {
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search words or meanings") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            val matches = lessons.filter { it.word.contains(search, ignoreCase = true) || it.meaning.contains(search, ignoreCase = true) }
            if (matches.isEmpty()) Text("No matching words.", color = Muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(matches, key = { it.id }) { lesson ->
                    Card(onClick = { select(lesson) }, colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(lesson.word, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text(lesson.meaning, color = Muted, fontSize = 13.sp)
                            state.course.cards[lesson.id]?.let { card -> Text("${if (card.learned) "Learned" else "Practicing"} · Review: ${card.due}", fontSize = 11.sp, color = Muted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, description: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.padding(top = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Spa, null, tint = Muted, modifier = Modifier.size(36.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(description, color = Muted, fontSize = 14.sp)
        }
    }
}
