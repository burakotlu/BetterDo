package com.burakotlu.betterdo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AccountDialog(model: AccountViewModel, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember(model.user?.id) { mutableStateOf(model.user?.displayName.orEmpty()) }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                if (!model.configured) {
                    Text("Accounts are temporarily unavailable. Please try an updated app later.")
                } else if (model.user != null) {
                    Text(model.user!!.email)
                    Text(if (model.user!!.verified) "Email verified" else "Email verification pending")
                    OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { model.updateName(name) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save name") }
                    OutlinedButton(onClick = { model.signOut() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sign out") }
                    Text("Your lessons and learning progress are stored on this device. Account details are stored on the BetterDo server.")
                } else {
                    if (model.awaitingEmail != null) {
                        Text("A confirmation code was sent to ${model.awaitingEmail}.")
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("Email confirmation code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { model.verify(code) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Verify email") }
                    }
                    Text(if (creating) "Create an account" else "Sign in")
                    if (creating) OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, label = { Text("Email address") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (creating) model.signUp(email, password, name) else model.signIn(email, password)
                    }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (creating) "Create account" else "Sign in") }
                    TextButton(onClick = { creating = !creating }) { Text(if (creating) "Already have an account? Sign in" else "New here? Create an account") }
                }
                model.notice?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
