package com.kenznguyen.seotaskai

import android.webkit.WebView
import android.provider.OpenableColumns
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.kenznguyen.seotaskai.ai.GgufModelManager
import com.kenznguyen.seotaskai.ai.LocalTaskAnalyzer
import com.kenznguyen.seotaskai.ai.LlamaCppEngine
import com.kenznguyen.seotaskai.browser.BrowserController
import com.kenznguyen.seotaskai.data.database.TaskDatabase
import com.kenznguyen.seotaskai.data.database.TaskEntity
import com.kenznguyen.seotaskai.data.repository.TaskRepository
import com.kenznguyen.seotaskai.domain.analyzer.TaskAnalyzer
import com.kenznguyen.seotaskai.ocr.TextRecognitionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        Text("v0.3 local AI · on-device inference")
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
    val context = LocalContext.current
    val tasks by repository.observeTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val modelManager = remember { GgufModelManager(context.contentResolver, java.io.File(context.filesDir, "models")) }
    var engine by remember { mutableStateOf<LlamaCppEngine?>(null) }
    var models by remember { mutableStateOf(emptyList<java.io.File>()) }
    var selectedModelName by remember { mutableStateOf<String?>(null) }
    var modelStatus by remember { mutableStateOf("No local model loaded. Import a GGUF file to enable AI analysis.") }
    var inferenceMetrics by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val modelPreferences = remember {
        context.getSharedPreferences("local_ai_model", android.content.Context.MODE_PRIVATE)
    }

    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { modelManager.listModels() }
        selectedModelName = modelPreferences.getString("selected_model", null)
            ?.takeIf { name -> models.any { it.name == name } }
            ?: models.firstOrNull()?.name
    }
    var title by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var aiType by remember { mutableStateOf<String?>(null) }
    var aiSource by remember { mutableStateOf<String?>(null) }
    var aiWarning by remember { mutableStateOf<String?>(null) }
    val plan = remember(instruction) { TaskAnalyzer().analyze(instruction) }

    DisposableEffect(engine) {
        onDispose { engine?.close() }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            modelStatus = "Importing model into private app storage…"
            try {
                val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast(':')
                    ?: "imported-model.gguf"
                val expectedChecksum = if (displayName == "Qwen3-1.7B-Q4_K_M.gguf") {
                    "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
                } else null
                val imported = withContext(Dispatchers.IO) { modelManager.import(uri, displayName, expectedChecksum) }
                models = withContext(Dispatchers.IO) { modelManager.listModels() }
                selectedModelName = imported.file.name
                modelPreferences.edit().putString("selected_model", imported.file.name).apply()
                engine?.close()
                val loaded = LlamaCppEngine(imported.file)
                modelStatus = "Imported ${imported.file.name} (${imported.sizeBytes / 1_000_000} MB). Loading model…"
                loaded.load()
                engine = loaded
                modelStatus = "Model ready: ${imported.file.name} · Load: ${loaded.loadTimeMillis ?: 0} ms · SHA-256 ${imported.sha256.take(12)}…"
                inferenceMetrics = null
                aiType = null
            } catch (error: Exception) {
                modelStatus = "Model import/load failed: ${error.message ?: "unknown error"}. Rule-based fallback remains available."
            } finally {
                busy = false
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Local AI model", style = MaterialTheme.typography.titleMedium)
                Text(modelStatus, style = MaterialTheme.typography.bodySmall)
                if (models.isNotEmpty()) {
                    Text("Imported models", style = MaterialTheme.typography.labelLarge)
                    models.forEach { model ->
                        TextButton(
                            onClick = {
                                selectedModelName = model.name
                                modelPreferences.edit().putString("selected_model", model.name).apply()
                            },
                            enabled = !busy
                        ) {
                            Text((if (selectedModelName == model.name) "● " else "○ ") + model.name)
                        }
                    }
                    Button(
                        onClick = {
                            val selected = models.firstOrNull { it.name == selectedModelName }
                            if (selected != null) scope.launch {
                                busy = true
                                modelStatus = "Loading ${selected.name}…"
                                try {
                                    engine?.close()
                                    engine = null
                                    val loaded = LlamaCppEngine(selected)
                                    loaded.load()
                                    engine = loaded
                                    inferenceMetrics = null
                                    modelStatus = "Model ready: ${selected.name} · Load: ${loaded.loadTimeMillis ?: 0} ms"
                                } catch (error: Exception) {
                                    modelStatus = "Model load failed: ${error.message ?: "unknown error"}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = selectedModelName != null && !busy
                    ) { Text(if (busy) "Please wait…" else "Load selected model") }
                }
                Button(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) {
                    Text("Import GGUF model")
                }
                Text("Selected model is remembered after restart. Models stay in app-private storage. Qwen3-1.7B Q4_K_M is about 1.28 GB.", style = MaterialTheme.typography.bodySmall)
                inferenceMetrics?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Task title") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = instruction, onValueChange = { instruction = it },
            label = { Text("Task instruction") }, modifier = Modifier.fillMaxWidth()
        )
        Text("Rule-based type: ${plan.type}")
        aiType?.let { Text("AI type: $it · $aiSource") }
        aiWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (plan.steps.isNotEmpty()) {
            Text("Suggested workflow")
            plan.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (plan.requiresAccountAction) {
                Text("Human review required. Artificial engagement is not automated.",
                    color = MaterialTheme.colorScheme.error)
            }
        }
        Button(
            enabled = instruction.isNotBlank() && engine?.isReady == true && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    val activeEngine = engine!!
                    val result = LocalTaskAnalyzer(activeEngine).analyze(instruction)
                    activeEngine.lastMetrics?.let { metrics ->
                        inferenceMetrics = "Prompt: ${metrics.promptTokens} tokens · Output: ${metrics.generatedTokens} tokens · Prefill: ${metrics.prefillMillis} ms · Decode: ${metrics.decodeMillis} ms · ${"%.2f".format(metrics.decodeTokensPerSecond)} tok/s"
                    }
                    aiType = result.type.name
                    aiSource = result.source.name
                    aiWarning = result.warning
                    busy = false
                }
            }
        ) { Text(if (busy) "Analyzing…" else "Analyze with local AI") }
        Button(
            enabled = title.isNotBlank() && instruction.isNotBlank() && !busy,
            onClick = {
                scope.launch {
                    val detectedType = aiType ?: plan.type.name
                    repository.addTask(title.trim(), instruction.trim(), detectedType)
                    title = ""
                    instruction = ""
                    aiType = null
                    aiSource = null
                    aiWarning = null
                }
            }
        ) { Text("Save task") }
        Text("Saved tasks (${tasks.size})", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tasks.forEach { task ->
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
