package com.kenznguyen.seotaskai

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kenznguyen.seotaskai.browser.BrowserController
import com.kenznguyen.seotaskai.data.database.TaskDatabase
import com.kenznguyen.seotaskai.data.database.TaskEntity
import com.kenznguyen.seotaskai.data.repository.TaskRepository
import com.kenznguyen.seotaskai.domain.analyzer.TaskAnalyzer
import com.kenznguyen.seotaskai.ocr.TextRecognitionEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = TaskRepository(TaskDatabase.getInstance(applicationContext).taskDao())
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(repository)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(repository: TaskRepository) {
    var page by remember { mutableStateOf("Tasks") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("SEO Task AI", style = MaterialTheme.typography.headlineMedium)
        Text("v0.2 foundation · local-first")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tasks", "Browser", "OCR").forEach { item ->
                TextButton(onClick = { page = item }) { Text(item) }
            }
        }
        when (page) {
            "Tasks" -> TaskScreen(repository)
            "Browser" -> BrowserScreen()
            else -> OcrScreen()
        }
    }
}

@Composable
private fun TaskScreen(repository: TaskRepository) {
    val tasks by repository.observeTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    val plan = remember(instruction) { TaskAnalyzer().analyze(instruction) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Task title") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = instruction, onValueChange = { instruction = it },
            label = { Text("Task instruction") }, modifier = Modifier.fillMaxWidth()
        )
        Text("Detected type: ${plan.type}")
        if (plan.steps.isNotEmpty()) {
            Text("Suggested workflow")
            plan.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (plan.requiresAccountAction) {
                Text("Human review required. Artificial engagement is not automated.",
                    color = MaterialTheme.colorScheme.error)
            }
        }
        Button(
            enabled = title.isNotBlank() && instruction.isNotBlank(),
            onClick = {
                scope.launch {
                    repository.addTask(title.trim(), instruction.trim(), plan.type.name)
                    title = ""
                    instruction = ""
                }
            }
        ) { Text("Save task") }
        Text("Saved tasks (${tasks.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    onComplete = { scope.launch { repository.updateStatus(task.id, "COMPLETED") } },
                    onDelete = { scope.launch { repository.deleteTask(task) } }
                )
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskEntity, onComplete: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(task.category)
            Text(task.instruction, style = MaterialTheme.typography.bodySmall)
            Text("Status: ${task.status}")
            Row {
                if (task.status != "COMPLETED") TextButton(onClick = onComplete) { Text("Complete") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun BrowserScreen() {
    val controller = remember { BrowserController() }
    var input by remember { mutableStateOf("https://example.com") }
    var currentUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(input, { input = it }, label = { Text("Website URL") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            runCatching { controller.normalizeUrl(input) }
                .onSuccess { currentUrl = it; error = null }
                .onFailure { error = it.message }
        }) { Text("Open website") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (currentUrl.isNotBlank()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> WebView(context).also(controller::configure) },
                update = { view ->
                    if (view.tag != currentUrl) {
                        view.tag = currentUrl
                        view.loadUrl(currentUrl)
                    }
                }
            )
        }
    }
}

@Composable
private fun OcrScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("Choose an image to extract text on-device.") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                result = runCatching { TextRecognitionEngine().recognize(context, uri) }
                    .getOrElse { "OCR failed: ${it.message ?: "unknown error"}" }
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
            Text(if (busy) "Recognizing…" else "Choose screenshot")
        }
        Text(result)
    }
}
