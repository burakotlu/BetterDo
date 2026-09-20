package com.burakotlu.betterdo

import android.os.Bundle
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
            if (event == Lifecycle.Event.ON_RESUME) model.refreshDate()
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
                    Box {
                        TextButton(onClick = { languageMenu = true }, enabled = !state.loading && !state.fatalError) {
                            Text(if (state.language == "en") "EN · English" else "DE · Deutsch", fontSize = 13.sp)
                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Dil seç")
                        }
                        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            listOf("en" to "English", "de" to "Deutsch").forEach { (code, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { speech.stop(); model.changeLanguage(code); languageMenu = false; selectedId = null })
                            }
                        }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream))
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val labels = listOf("Bugün", "Tekrar", "Kelimelerim")
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
                state.fatalError -> EmptyCard("Dersler yüklenemedi", state.message.orEmpty())
                else -> {
                    val selected = state.catalog.find { it.id == selectedId }
                    val lesson = selected ?: if (tab == 0) state.daily else null
                    if (lesson != null) {
                        key(lesson.id, state.today, tab) {
                            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (selected != null) TextButton(onClick = { selectedId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text(" Listeye dön") }
                                else Intro(state)
                                if (!state.writable) Text("Kayıtlı ilerleme okunamadı. Bu oturumdaki değişiklikler kaydedilmiyor.", color = MaterialTheme.colorScheme.error)
                                LessonContent(lesson, state, model, speech) { if (tab == 1) selectedId = null }
                                Text("Bir anda değil. Her gün biraz.", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp))
                            }
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
        Text(state.today.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.forLanguageTag("tr"))).uppercase(Locale.forLanguageTag("tr")), fontSize = 10.sp, letterSpacing = 1.6.sp, color = Muted)
        Text("Bugün, bir kelime daha.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Text(stringResource(R.string.app_motto), color = Muted, fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("${Scheduler.streak(state.course.activity, state.today)}", "günlük seri", Modifier.weight(1f))
        Stat("${state.course.cards.values.count { it.learned }}", "öğrenilen", Modifier.weight(1f))
        Stat("${state.due.size}", "tekrar", Modifier.weight(1f))
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
            Text("GÜNÜN KELİMESİ · ${lesson.level}", color = Lime, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Text(lesson.word, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp)
            Text("${lesson.pronunciation} · ${lesson.partOfSpeech}", color = Color(0xFFC5D6C7), fontSize = 12.sp)
            Text(lesson.meaning, color = Lime, fontSize = 22.sp)
            Text(lesson.context, color = Color(0xFFD0DED2), fontSize = 13.sp, lineHeight = 21.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { speech.speak(lesson.word, lesson.language) }, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Forest), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Dinle")
                }
                TextButton(onClick = { speech.speak(lesson.word, lesson.language, true) }, colors = ButtonDefaults.textButtonColors(contentColor = Lime)) { Text("Yavaş dinle") }
            }
        }
    }
    Panel("Hayatın içinden", "Kelimeler cümlelerin içinde akılda kalır.") {
        lesson.examples.forEachIndexed { index, example ->
            if (index > 0) HorizontalDivider(color = Color(0xFFEDF0E9))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("0${index + 1}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(example.text, fontSize = 15.sp)
                    Text(example.translation, fontSize = 12.sp, color = Muted)
                }
                IconButton(onClick = { speech.speak(example.text, lesson.language) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "${index + 1}. örneği dinle", tint = Muted) }
            }
        }
    }
    Panel("Küçük bir sohbet", lesson.dialogueContext, Color(0xFFEDF1E4)) {
        lesson.dialogue.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Text(line.speaker, fontWeight = FontWeight.Bold, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, end = 10.dp))
                Surface(Modifier.weight(1f), color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(line.text, fontSize = 14.sp)
                        Text(line.translation, fontSize = 12.sp, color = Muted)
                    }
                }
                IconButton(onClick = { speech.speak(line.text, lesson.language) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "${line.speaker} kişisinin cümlesini dinle", tint = Muted) }
            }
        }
    }
    val answers = state.answers[lesson.id].orEmpty()
    Panel("Bir de sen dene", "İki küçük soruyla öğrendiğini pekiştir.") {
        lesson.quiz.forEachIndexed { index, quiz ->
            Text("${index + 1}. ${quiz.question}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            quiz.options.forEachIndexed { optionIndex, option ->
                val chosen = answers[index] == optionIndex
                val color = if (!chosen) Color.White else if (optionIndex == quiz.answer) Lime else Color(0xFFFFE5DC)
                OutlinedButton(onClick = { model.answer(lesson, index, optionIndex) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = color), contentPadding = PaddingValues(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (chosen) { Icon(if (optionIndex == quiz.answer) Icons.Outlined.Check else Icons.Outlined.Close, if (optionIndex == quiz.answer) "Doğru cevap" else "Yanlış cevap", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(option, fontSize = 13.sp)
                    }
                }
            }
            answers[index]?.let { answer ->
                Text(if (answer == quiz.answer) "Doğru! ${quiz.explanation}" else "Henüz değil. Örneklere bakıp yeniden dene.", color = if (answer == quiz.answer) Muted else Color(0xFF98442E), fontSize = 12.sp)
            }
        }
    }
    val practiced = state.course.cards[lesson.id]?.lastPracticed == state.today
    if (practiced) Text("Bugünkü pratik tamamlandı. Sonraki tekrar: ${state.course.cards.getValue(lesson.id).due.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("tr")))}", color = Muted, fontSize = 13.sp)
    Button(onClick = { model.practice(lesson, true); onDone() }, enabled = lesson.quiz.indices.all { answers[it] == lesson.quiz[it].answer }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp)); Text("Öğrendim")
    }
    OutlinedButton(onClick = { model.practice(lesson, false); onDone() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Yarın tekrar et") }
    Text("“Öğrendim” için iki soruyu da doğru yanıtla. Tekrar aralıkları: 1, 3, 7, 14, 30 ve 60 gün.", color = Muted, fontSize = 11.sp)
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
        Text(if (review) "Küçük bir hatırlatma." else "Senin kelimelerin.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (lessons.isEmpty()) {
            EmptyCard(if (review) "Şimdilik hepsi tamam." else "İlk kelimen seni bekliyor.", if (review) "Zamanı gelen tekrarın yok. Öğrendiklerin tekrar günü gelince burada görünecek." else "Bir dersi tamamla; kelime defterin burada büyüsün.")
            Button(onClick = goToday) { Text("Bugünün dersine dön") }
        } else {
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Kelime veya anlam ara") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            val matches = lessons.filter { it.word.contains(search, ignoreCase = true) || it.meaning.contains(search, ignoreCase = true) }
            if (matches.isEmpty()) Text("Eşleşen kelime yok.", color = Muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(matches, key = { it.id }) { lesson ->
                    Card(onClick = { select(lesson) }, colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(lesson.word, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text(lesson.meaning, color = Muted, fontSize = 13.sp)
                            state.course.cards[lesson.id]?.let { card -> Text("${if (card.learned) "Öğrenildi" else "Pratik yapılıyor"} · Tekrar: ${card.due}", fontSize = 11.sp, color = Muted) }
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
