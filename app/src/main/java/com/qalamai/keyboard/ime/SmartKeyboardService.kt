package com.qalamai.keyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.qalamai.keyboard.ai.AIAssistantManager
import com.qalamai.keyboard.ai.TonePreset
import com.qalamai.keyboard.data.ClipboardCategory
import com.qalamai.keyboard.data.ClipboardEntry
import com.qalamai.keyboard.data.ClipboardRepository
import com.qalamai.keyboard.data.SecurePrefsManager
import com.qalamai.keyboard.ui.KeyboardScreen
import com.qalamai.keyboard.ui.SettingsActivity
import com.qalamai.keyboard.ui.ads.AdStudioActivity
import com.qalamai.keyboard.ui.theme.KeyboardThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class KeyboardLayoutMode {
    ARABIC,
    ENGLISH,
    SYMBOLS,
    FULL_PC
}

enum class ActiveToolbarPanel {
    NONE,
    AI_TOOLS,
    CLIPBOARD,
    CURSOR_PC,
    TASHKEEL,
    EMOJI,
    THEMES
}

class SmartKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var prefsManager: SecurePrefsManager
    private lateinit var clipboardRepo: ClipboardRepository
    private lateinit var aiManager: AIAssistantManager
    private lateinit var systemClipboard: ClipboardManager

    // حالة لوحة المفاتيح التفاعلية
    var layoutMode by mutableStateOf(KeyboardLayoutMode.ARABIC)
    var isShifted by mutableStateOf(false)
    var isSelectionMode by mutableStateOf(false)
    var isCtrlPressed by mutableStateOf(false)
    var activePanel by mutableStateOf(ActiveToolbarPanel.NONE)
    var currentTheme by mutableStateOf(KeyboardThemeId.OBSIDIAN)
    var aiLoading by mutableStateOf(false)
    var aiStatusMessage by mutableStateOf<String?>(null)

    val suggestions = mutableStateListOf<String>()
    val clipboardEntries = mutableStateListOf<ClipboardEntry>()

    // مكدس التراجع والإعادة (Undo / Redo Stack) لحالات InputConnection
    private val undoStack = ArrayDeque<String>(30)
    private val redoStack = ArrayDeque<String>(30)
    private var lastCapturedText = ""

    /** حقل كلمة مرور أو وضع تصفح خفي: لا ذكاء اصطناعي، لا تنبؤ، لا تسجيل للنص. */
    private var sensitiveField = false

    /** يزداد مع كل حقل إدخال جديد لتجاهل نتائج الذكاء الاصطناعي المتأخرة القادمة لحقل سابق. */
    private var inputSessionId = 0L

    /** صحيح عندما فعّلنا وضع الحاسوب تلقائياً بسبب التدوير الأفقي (لنُرجعه عند العودة للعمودي). */
    private var autoPcActive = false

    private val localArabicPredictor = listOf(
        "السلام عليكم", "السلام عليكم ورحمة الله", "شكراً جزيلاً", "شكراً لك", "إن شاء الله",
        "ما شاء الله", "الحمد لله", "جزاك الله خيراً", "بارك الله فيك", "يرجى التكرم",
        "مع أطيب التحيات", "مع خالص الشكر", "بكل سرور", "في أقرب وقت", "في أقرب وقت ممكن",
        "الموافقة على", "بخصوص", "بالنسبة إلى", "أرجو منكم", "نشكركم على",
        "كيف حالك", "صباح الخير", "مساء الخير", "تصبح على خير", "أهلاً وسهلاً",
        "عذراً على التأخير", "تم الإرسال", "تم الاستلام", "حاضر", "تمام",
        "تطوير التطبيق", "الذكاء الاصطناعي", "لوحة المفاتيح"
    )

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!prefsManager.isClipboardHistoryEnabled()) return@OnPrimaryClipChangedListener
        val clip = systemClipboard.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        // مدراء كلمات المرور يضعون هذه العلامة على المحتوى الحساس: لا نحفظه أبداً.
        val sensitiveFlag = clip.description?.extras
            ?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false
        if (sensitiveFlag || sensitiveField) return@OnPrimaryClipChangedListener

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            clipboardRepo.addClip(text)
            refreshClipboardList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        prefsManager = SecurePrefsManager(this)
        clipboardRepo = ClipboardRepository(this)
        aiManager = AIAssistantManager(prefsManager)
        currentTheme = prefsManager.getSelectedTheme()

        systemClipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        systemClipboard.addPrimaryClipChangedListener(clipListener)
        refreshClipboardList()
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KeyboardScreen(
                    service = this@SmartKeyboardService
                )
            }
        }

        // ربط ViewTree Owners لتمكين Jetpack Compose داخل نافذة IME
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentTheme = prefsManager.getSelectedTheme()
        clipboardRepo.reload() // قد يكون المستخدم مسح السجل من شاشة الإعدادات
        refreshClipboardList()

        sensitiveField = isSensitiveEditor(info)
        if (!restarting) {
            inputSessionId++
            aiStatusMessage = null
            aiLoading = false
            activePanel = ActiveToolbarPanel.NONE
            resetUndoState()
        }

        // وضع الحاسوب الكامل تلقائياً في الوضع الأفقي (وإرجاعه عند العودة للعمودي)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape && prefsManager.isAutoPcInLandscape()) {
            if (layoutMode != KeyboardLayoutMode.FULL_PC) {
                layoutMode = KeyboardLayoutMode.FULL_PC
                autoPcActive = true
            }
        } else if (autoPcActive) {
            if (layoutMode == KeyboardLayoutMode.FULL_PC) layoutMode = KeyboardLayoutMode.ARABIC
            autoPcActive = false
        }

        // حقول كلمات المرور تحتاج غالباً أحرفاً لاتينية
        if (sensitiveField && layoutMode == KeyboardLayoutMode.ARABIC) {
            layoutMode = KeyboardLayoutMode.ENGLISH
        }

        updateLocalPredictions()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // لا نحتفظ بنص أي حقل في الذاكرة بعد انتهاء الإدخال
        undoStack.clear()
        redoStack.clear()
        lastCapturedText = ""
        sensitiveField = false
    }

    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val isPassword = when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return isPassword || incognito
    }

    override fun onDestroy() {
        systemClipboard.removePrimaryClipChangedListener(clipListener)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    // =========================================================================
    // إدارة الإدخال والكتابة (InputConnection Core)
    // =========================================================================

    fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        performHapticFeedback()
        aiStatusMessage = null

        // معالجة اختصارات Ctrl إذا كان زر Ctrl مفعلاً في وضع الحاسوب
        if (isCtrlPressed && text.length == 1) {
            when (text.lowercase()) {
                "a" -> { selectAll(); isCtrlPressed = false; return }
                "c" -> { copySelection(); isCtrlPressed = false; return }
                "v" -> { pasteFromClipboard(); isCtrlPressed = false; return }
                "z" -> { undo(); isCtrlPressed = false; return }
                "y" -> { redo(); isCtrlPressed = false; return }
            }
            isCtrlPressed = false
        }

        snapshotForUndo()
        ic.commitText(text, 1)

        if (isShifted && layoutMode == KeyboardLayoutMode.ENGLISH) {
            isShifted = false
        }
        updateLocalPredictions()
    }

    fun deleteBackward() {
        val ic = currentInputConnection ?: return
        performHapticFeedback()
        aiStatusMessage = null
        snapshotForUndo()

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        updateLocalPredictions()
    }

    fun sendEnterOrAction() {
        val ic = currentInputConnection ?: return
        performHapticFeedback()
        val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun sendTab() {
        performHapticFeedback()
        val ic = currentInputConnection ?: return
        ic.commitText("\t", 1)
    }

    // =========================================================================
    // اختصارات الحاسوب الحقيقية (Ctrl+A / Ctrl+C / Ctrl+V / Ctrl+Z / Ctrl+Y)
    // =========================================================================

    fun selectAll() {
        performHapticFeedback()
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    fun copySelection() {
        performHapticFeedback()
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            // مستمع الحافظة يسجّل النسخة تلقائياً (إن كان السجل مفعلاً)، فلا نضيفها يدوياً هنا
            ic.performContextMenuAction(android.R.id.copy)
        }
    }

    fun pasteFromClipboard() {
        performHapticFeedback()
        snapshotForUndo()
        val ic = currentInputConnection ?: return
        val handled = ic.performContextMenuAction(android.R.id.paste)
        if (!handled) {
            val latest = clipboardEntries.firstOrNull()?.text
            if (!latest.isNullOrEmpty()) {
                ic.commitText(latest, 1)
            }
        }
    }

    fun pasteSpecificClip(text: String) {
        performHapticFeedback()
        snapshotForUndo()
        currentInputConnection?.commitText(text, 1)
    }

    fun undo() {
        performHapticFeedback()
        val ic = currentInputConnection ?: return
        if (undoStack.isEmpty()) {
            ic.performContextMenuAction(android.R.id.undo)
            return
        }
        val currentFull = readAllText(ic)
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentFull)
        replaceEntireFieldText(ic, previous)
        lastCapturedText = previous
    }

    fun redo() {
        performHapticFeedback()
        val ic = currentInputConnection ?: return
        if (redoStack.isEmpty()) {
            ic.performContextMenuAction(android.R.id.redo)
            return
        }
        val currentFull = readAllText(ic)
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentFull)
        replaceEntireFieldText(ic, next)
        lastCapturedText = next
    }

    // =========================================================================
    // تحريك المؤشر وتحديد النص عبر InputConnection و KeyEvents
    // =========================================================================

    fun moveCursor(keyCode: Int) {
        performHapticFeedback()
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val metaState = if (isSelectionMode) KeyEvent.META_SHIFT_ON else 0

        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    private fun sendDownUpKeyEvent(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // =========================================================================
    // ميزات الذكاء الاصطناعي (Gemini API - بطلب يدوي فقط)
    // =========================================================================

    fun runAiProofread() = executeAiTransformation { text ->
        aiManager.proofread(text)
    }

    fun runAiToneChange(preset: TonePreset) = executeAiTransformation { text ->
        aiManager.rewriteTone(text, preset)
    }

    fun runAiTranslate(targetLanguage: String) = executeAiTransformation { text ->
        aiManager.translate(text, targetLanguage)
    }

    fun runAiTashkeel() = executeAiTransformation { text ->
        aiManager.addTashkeel(text)
    }

    fun runAiAutocomplete() {
        if (!canUseAi()) return
        val ic = currentInputConnection ?: return
        val contextText = ic.getTextBeforeCursor(400, 0)?.toString()?.trim().orEmpty()
        if (contextText.isEmpty()) {
            aiStatusMessage = "اكتب بعض الكلمات أولاً لاقتراح الإكمال."
            return
        }
        aiLoading = true
        aiStatusMessage = null
        val session = inputSessionId

        serviceScope.launch {
            val result = aiManager.autocomplete(contextText)
            if (session != inputSessionId) return@launch // انتقل المستخدم إلى حقل آخر
            aiLoading = false
            result.onSuccess { options ->
                if (options.isEmpty()) {
                    aiStatusMessage = "لا توجد اقتراحات لهذا النص."
                } else {
                    suggestions.clear()
                    suggestions.addAll(options)
                    aiStatusMessage = null
                }
            }.onFailure { err ->
                aiStatusMessage = err.message ?: "تعذر الاتصال بـ Gemini API"
            }
        }
    }

    private fun canUseAi(): Boolean {
        if (sensitiveField) {
            aiStatusMessage = "أدوات الذكاء الاصطناعي معطلة في حقول كلمات المرور والتصفح الخفي."
            return false
        }
        if (aiLoading) return false
        return true
    }

    private fun executeAiTransformation(block: suspend (String) -> Result<String>) {
        if (!canUseAi()) return
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()?.trim()
        val hasSelection = !selected.isNullOrEmpty()
        val targetText = if (hasSelection) selected.orEmpty() else readAllText(ic).trim()

        if (targetText.isEmpty()) {
            aiStatusMessage = "لا يوجد نص محدد أو مكتوب للمعالجة."
            return
        }

        aiLoading = true
        aiStatusMessage = null
        val session = inputSessionId

        serviceScope.launch {
            val result = block(targetText)
            if (session != inputSessionId) return@launch // انتقل المستخدم إلى حقل آخر
            aiLoading = false
            // نستخدم الاتصال الحالي وقت وصول النتيجة وليس الاتصال القديم
            val liveIc = currentInputConnection
            result.onSuccess { transformed ->
                if (liveIc == null) {
                    aiStatusMessage = "تعذر إدراج النتيجة: الحقل لم يعد متاحاً."
                    return@onSuccess
                }
                snapshotForUndo()
                if (hasSelection) {
                    liveIc.commitText(transformed, 1)
                } else {
                    replaceEntireFieldText(liveIc, transformed)
                }
                aiStatusMessage = "تم التحديث بنجاح"
            }.onFailure { err ->
                aiStatusMessage = err.message ?: "خطأ في الاتصال بـ Gemini API"
            }
        }
    }

    // =========================================================================
    // التنبؤ المحلي (Local Predictor)
    // =========================================================================

    fun applySuggestion(suggestion: String) {
        val ic = currentInputConnection ?: return
        snapshotForUndo()
        val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
        val lastToken = before.takeLastWhile { !it.isWhitespace() }
        if (lastToken.isNotEmpty() && suggestion.startsWith(lastToken)) {
            ic.deleteSurroundingText(lastToken.length, 0)
        }
        ic.commitText("$suggestion ", 1)
        updateLocalPredictions()
    }

    private fun updateLocalPredictions() {
        suggestions.clear()
        // لا نقرأ نص حقول كلمات المرور إطلاقاً
        if (sensitiveField) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
        val token = before.takeLastWhile { !it.isWhitespace() }

        if (token.isNotEmpty()) {
            suggestions.addAll(
                localArabicPredictor.filter { it.startsWith(token) && it != token }.take(4)
            )
        }
        if (suggestions.isEmpty()) {
            suggestions.addAll(listOf("إن شاء الله", "شكراً", "السلام عليكم"))
        }
    }

    // =========================================================================
    // الحافظة وإدارة السياق
    // =========================================================================

    fun refreshClipboardList(category: ClipboardCategory = ClipboardCategory.ALL) {
        clipboardEntries.clear()
        clipboardEntries.addAll(clipboardRepo.getClips(category))
    }

    fun togglePinClip(id: String) {
        clipboardRepo.togglePin(id)
        refreshClipboardList()
    }

    fun deleteClip(id: String) {
        clipboardRepo.deleteClip(id)
        refreshClipboardList()
    }

    fun selectTheme(themeId: KeyboardThemeId) {
        currentTheme = themeId
        prefsManager.saveSelectedTheme(themeId)
    }

    fun openAdStudio() {
        val intent = Intent(this, AdStudioActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    fun openSettingsScreen() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun readAllText(ic: InputConnection): String {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        return extracted?.text?.toString()
            ?: ((ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()) +
                (ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()))
    }

    private fun replaceEntireFieldText(ic: InputConnection, newText: String) {
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
    }

    private fun resetUndoState() {
        undoStack.clear()
        redoStack.clear()
        val ic = currentInputConnection
        lastCapturedText = if (ic != null && !sensitiveField) readAllText(ic) else ""
    }

    private fun snapshotForUndo() {
        if (sensitiveField) return // لا نخزّن نص كلمات المرور في الذاكرة
        val ic = currentInputConnection ?: return
        val current = readAllText(ic)
        if (current != lastCapturedText) {
            if (undoStack.size >= 30) undoStack.removeFirst()
            undoStack.addLast(lastCapturedText)
            redoStack.clear()
            lastCapturedText = current
        }
    }

    private fun performHapticFeedback() {
        if (!prefsManager.isHapticEnabled()) return
        val effect = VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(effect)
        }
    }
}
