package com.burakotlu.betterdo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CourseLevelScreen(progress: LearningProgress, message: String?, onContinue: (String, String) -> Unit) {
    var language by rememberSaveable { mutableStateOf(progress.language) }
    var level by rememberSaveable(language) { mutableStateOf(progress.levels[language]) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Learn a word instead of scrolling.", style = MaterialTheme.typography.titleMedium)
        Text("Choose your course level", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Pick a language and the level that feels right. You can change it later.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("en" to "English", "de" to "German").forEach { (code, label) ->
                FilterChip(selected = language == code, onClick = { language = code; level = progress.levels[code] },
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        LanguageLevels.labels.forEach { (code, label) ->
            OutlinedButton(onClick = { level = code }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("$code · $label${if (level == code) " · Selected" else ""}")
            }
        }
        Button(onClick = { level?.let { onContinue(language, it) } }, enabled = level != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start learning") }
        Text("Your saved words and review dates stay available if you change levels.")
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun ChangeLevelDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose your level") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageLevels.labels.forEach { (code, label) ->
                OutlinedButton(onClick = { onSelect(code); onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("$code · $label${if (current == code) " · Current" else ""}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
