package com.omnieditor.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.diff.syntax.TokenType
import com.omnieditor.core.model.UserTheme
import kotlinx.serialization.json.Json

/**
 * F-14: Theme editor screen.
 *
 * Lists built-in themes and user-created themes. For each user theme, the user
 * can edit hex colour values per TokenType, preview a static code sample with the
 * applied colours, and import/export the theme as JSON via the system share sheet.
 *
 * Colour input: simple hex-string fields (#RRGGBB). No visual colour wheel is
 * provided in this version — that would require an additional dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(
    userThemes: List<UserTheme> = emptyList(),
    onNavigateBack: () -> Unit = {},
    onCreateTheme: (UserTheme) -> Unit = {},
    onUpdateTheme: (UserTheme) -> Unit = {},
    onDeleteTheme: (id: String) -> Unit = {},
    onImportTheme: (json: String) -> Unit = {},
) {
    var editingTheme by remember { mutableStateOf<UserTheme?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (editingTheme != null) {
        ThemeEditView(
            theme = editingTheme!!,
            onSave = { updated ->
                onUpdateTheme(updated)
                editingTheme = null
            },
            onBack = { editingTheme = null },
            onExport = { theme ->
                val json = Json { prettyPrint = true; encodeDefaults = true }
                    .encodeToString(UserTheme.serializer(), theme)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "Theme: ${theme.name}")
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                context.startActivity(Intent.createChooser(intent, "Export theme"))
            },
            onDelete = { id ->
                onDeleteTheme(id)
                editingTheme = null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Themes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Share, "Import theme")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "New theme")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            // ── Built-in themes ──
            item {
                Text(
                    "BUILT-IN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                )
                HorizontalDivider()
            }
            item {
                ThemeRow(
                    name = "Default",
                    subtitle = "System theme colours",
                    isBuiltIn = true,
                    onClick = { /* Built-in themes are not editable */ },
                )
            }

            // ── User themes ──
            if (userThemes.isNotEmpty()) {
                item {
                    Text(
                        "MY THEMES",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                }
                items(userThemes, key = { it.id }) { theme ->
                    ThemeRow(
                        name = theme.name,
                        subtitle = "${theme.tokenColors.size} token colour overrides",
                        isBuiltIn = false,
                        onClick = { editingTheme = theme },
                    )
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "No custom themes yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Tap the + button to create a theme with custom token colours.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // New theme name dialog
    if (showCreateDialog) {
        ThemeNameDialog(
            title = "New theme",
            initialName = "",
            onConfirm = { name ->
                val newTheme = UserTheme(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                )
                onCreateTheme(newTheme)
                editingTheme = newTheme
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // Import theme JSON dialog
    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import theme") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Paste theme JSON here") },
                    minLines = 4,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (importText.isNotBlank()) {
                        onImportTheme(importText.trim())
                        showImportDialog = false
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ThemeRow(
    name: String,
    subtitle: String,
    isBuiltIn: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isBuiltIn) {
                Text(
                    "Built-in",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Theme edit view — shown when user taps a user theme
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeEditView(
    theme: UserTheme,
    onSave: (UserTheme) -> Unit,
    onBack: () -> Unit,
    onExport: (UserTheme) -> Unit,
    onDelete: (String) -> Unit,
) {
    val tokenColors = remember(theme.id) {
        mutableStateMapOf<String, String>().also { it.putAll(theme.tokenColors) }
    }
    var background by remember(theme.id) { mutableStateOf(theme.editorBackground ?: "") }
    var foreground by remember(theme.id) { mutableStateOf(theme.editorForeground ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun currentTheme() = theme.copy(
        tokenColors = tokenColors.toMap(),
        editorBackground = background.takeIf { it.isNotBlank() },
        editorForeground = foreground.takeIf { it.isNotBlank() },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(theme.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onExport(currentTheme()) }) {
                        Icon(Icons.Default.Share, "Export")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Delete theme")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Preview panel ──
            ThemePreviewPanel(tokenColors = tokenColors)

            Spacer(modifier = Modifier.height(8.dp))

            // ── Editor background / foreground ──
            Text(
                "EDITOR",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            HorizontalDivider()
            HexColorField(
                label = "Background",
                value = background,
                onValueChange = { background = it },
            )
            HexColorField(
                label = "Foreground",
                value = foreground,
                onValueChange = { foreground = it },
            )

            // ── Token colours ──
            Text(
                "TOKEN COLOURS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            HorizontalDivider()

            for (type in TokenType.entries) {
                val key = type.name
                HexColorField(
                    label = key.lowercase().replaceFirstChar { it.uppercaseChar() },
                    value = tokenColors[key] ?: "",
                    onValueChange = { value ->
                        if (value.isBlank()) tokenColors.remove(key)
                        else tokenColors[key] = value
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSave(currentTheme()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("Save theme")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete theme") },
            text = { Text("Delete \"${theme.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(theme.id) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Hex colour input field
// ---------------------------------------------------------------------------

@Composable
private fun HexColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val hexColor = parseHexColor(value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text("#RRGGBB") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        // Colour preview swatch
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = hexColor ?: MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                ),
        )
    }
}

/** Parse a hex colour string of the form #RRGGBB or #AARRGGBB. Returns null if invalid. */
private fun parseHexColor(hex: String): Color? {
    val cleaned = hex.trim().removePrefix("#")
    return when (cleaned.length) {
        6 -> {
            val rgb = cleaned.toLongOrNull(16) ?: return null
            Color(
                red = ((rgb shr 16) and 0xFF).toInt() / 255f,
                green = ((rgb shr 8) and 0xFF).toInt() / 255f,
                blue = (rgb and 0xFF).toInt() / 255f,
            )
        }
        8 -> {
            val argb = cleaned.toLongOrNull(16) ?: return null
            Color(
                alpha = ((argb shr 24) and 0xFF).toInt() / 255f,
                red = ((argb shr 16) and 0xFF).toInt() / 255f,
                green = ((argb shr 8) and 0xFF).toInt() / 255f,
                blue = (argb and 0xFF).toInt() / 255f,
            )
        }
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Preview panel — static code sample with applied token colours
// ---------------------------------------------------------------------------

@Composable
private fun ThemePreviewPanel(tokenColors: Map<String, String>) {
    fun tokenColor(type: TokenType): Color? = parseHexColor(tokenColors[type.name] ?: "")

    val sampleCode = buildAnnotatedString {
        // fun keyword
        withStyle(SpanStyle(color = tokenColor(TokenType.KEYWORD) ?: Color(0xFF569CD6))) {
            append("fun ")
        }
        // function name
        withStyle(SpanStyle(color = tokenColor(TokenType.FUNCTION) ?: Color(0xFFDCDCAA))) {
            append("greet")
        }
        append("(")
        // parameter name (plain)
        append("name")
        append(": ")
        // type
        withStyle(SpanStyle(color = tokenColor(TokenType.TYPE) ?: Color(0xFF4EC9B0))) {
            append("String")
        }
        append(")")
        append(": ")
        withStyle(SpanStyle(color = tokenColor(TokenType.TYPE) ?: Color(0xFF4EC9B0))) {
            append("String")
        }
        append(" {\n    ")
        // return keyword
        withStyle(SpanStyle(color = tokenColor(TokenType.KEYWORD) ?: Color(0xFF569CD6))) {
            append("return ")
        }
        // string
        withStyle(SpanStyle(color = tokenColor(TokenType.STRING) ?: Color(0xFFCE9178))) {
            append("\"Hello, \$name\"")
        }
        append("\n}\n\n")
        // comment
        withStyle(
            SpanStyle(
                color = tokenColor(TokenType.COMMENT) ?: Color(0xFF6A9955),
                fontStyle = FontStyle.Italic,
            ),
        ) {
            append("// number literal\n")
        }
        // keyword
        withStyle(SpanStyle(color = tokenColor(TokenType.KEYWORD) ?: Color(0xFF569CD6))) {
            append("val ")
        }
        append("count")
        append(" = ")
        // number
        withStyle(SpanStyle(color = tokenColor(TokenType.NUMBER) ?: Color(0xFFB5CEA8))) {
            append("42")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(
                color = parseHexColor(tokenColors["editorBackground"] ?: "") ?: Color(0xFF1E1E1E),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(16.dp),
    ) {
        BasicText(
            text = sampleCode,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = parseHexColor(tokenColors["editorForeground"] ?: "") ?: Color(0xFFD4D4D4),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Dialog helpers
// ---------------------------------------------------------------------------

@Composable
private fun ThemeNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Theme name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
