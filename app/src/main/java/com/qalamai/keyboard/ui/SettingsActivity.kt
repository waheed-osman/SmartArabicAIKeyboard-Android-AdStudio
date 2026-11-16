package com.qalamai.keyboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qalamai.keyboard.data.ClipboardRepository
import com.qalamai.keyboard.data.SecurePrefsManager
import com.qalamai.keyboard.ui.ads.AdStudioActivity
import com.qalamai.keyboard.ui.theme.KeyboardThemeId

class SettingsActivity : ComponentActivity() {

    private lateinit var prefsManager: SecurePrefsManager
    private lateinit var clipboardRepo: ClipboardRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefsManager = SecurePrefsManager(this)
        clipboardRepo = ClipboardRepository(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        prefsManager = prefsManager,
                        onClearClipboard = { clipboardRepo.clearAll() },
                        onOpenAdStudio = {
                            startActivity(Intent(this@SettingsActivity, AdStudioActivity::class.java))
                        },
                        onOpenImeSettings = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onShowImePicker = {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showInputMethodPicker()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefsManager: SecurePrefsManager,
    onClearClipboard: () -> Unit,
    onOpenAdStudio: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit
) {
    var apiKey by remember { mutableStateOf(prefsManager.getGeminiApiKey()) }
    var showKey by remember { mutableStateOf(false) }
    var selectedTheme by remember { mutableStateOf(prefsManager.getSelectedTheme()) }
    var hapticEnabled by remember { mutableStateOf(prefsManager.isHapticEnabled()) }
    var autoPcLandscape by remember { mutableStateOf(prefsManager.isAutoPcInLandscape()) }
    var clipboardEnabled by remember { mutableStateOf(prefsManager.isClipboardHistoryEnabled()) }
    var clipboardCleared by remember { mutableStateOf(false) }
    var savedBanner by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إعدادات لوحة القلم الذكية", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // خطوات تفعيل لوحة المفاتيح
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. تفعيل لوحة المفاتيح في النظام", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenImeSettings, modifier = Modifier.weight(1f)) {
                            Text("تفعيل من الإعدادات")
                        }
                        OutlinedButton(onClick = onShowImePicker, modifier = Modifier.weight(1f)) {
                            Text("اختيار اللوحة")
                        }
                    }
                }
            }

            // استوديو الإعلانات
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("استوديو الإعلانات بالذكاء الاصطناعي", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ارفع صورة منتجك، ودع الذكاء الاصطناعي يقترح الفكرة ويكتب الإعلان ويصمم الصورة ويجهّز المحتوى، " +
                            "وكل مرحلة تحتاج موافقتك قبل الانتقال للتالية. يتطلب مفتاح Gemini أدناه.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onOpenAdStudio, modifier = Modifier.fillMaxWidth()) {
                        Text("فتح استوديو الإعلانات")
                    }
                }
            }

            // تخزين مفتاح Gemini API المشفر
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. مفتاح Gemini API (مشفر محلياً)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "يُحفظ المفتاح مشفراً على جهازك. عند ضغطك على زر ذكاء اصطناعي يُرسل النص المحدد (أو نص الحقل) إلى خدمة Gemini من Google، " +
                            "ولا يُرسل أي شيء تلقائياً أثناء الكتابة. الأدوات معطلة في حقول كلمات المرور.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; savedBanner = false },
                        label = { Text("Gemini API Key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "إخفاء" else "إظهار")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            prefsManager.saveGeminiApiKey(apiKey)
                            savedBanner = true
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("حفظ المفتاح بأمان")
                    }
                    if (savedBanner) {
                        Text("تم حفظ المفتاح المشفر بنجاح.", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // التفضيلات والاختصارات
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. التجربة ووضع الحاسوب", style = MaterialTheme.typography.titleMedium)
                    SwitchRow("تفعيل وضع الحاسوب الكامل عند التدوير الأفقي", autoPcLandscape) {
                        autoPcLandscape = it
                        prefsManager.setAutoPcInLandscape(it)
                    }
                    SwitchRow("حفظ سجل الحافظة (مشفر على جهازك)", clipboardEnabled) {
                        clipboardEnabled = it
                        prefsManager.setClipboardHistoryEnabled(it)
                    }
                    OutlinedButton(
                        onClick = {
                            onClearClipboard()
                            clipboardCleared = true
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("مسح سجل الحافظة")
                    }
                    if (clipboardCleared) {
                        Text("تم مسح سجل الحافظة.", color = MaterialTheme.colorScheme.primary)
                    }
                    SwitchRow("الاهتزاز اللمسي عند الضغط", hapticEnabled) {
                        hapticEnabled = it
                        prefsManager.setHapticEnabled(it)
                    }
                }
            }

            // الثيم
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("4. ثيم لوحة المفاتيح", style = MaterialTheme.typography.titleMedium)
                    KeyboardThemeId.entries.forEach { theme ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedTheme == theme,
                                onClick = {
                                    selectedTheme = theme
                                    prefsManager.saveSelectedTheme(theme)
                                }
                            )
                            Text(theme.titleAr)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
