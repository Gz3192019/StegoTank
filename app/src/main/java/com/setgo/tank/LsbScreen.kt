package com.setgo.tank

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.setgo.tank.core.LsbTank
import com.setgo.tank.core.TuyinImages
import com.setgo.tank.core.UnifiedHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** LSB 隐写二级页：把文本或任意文件藏进 PNG 像素末位（2-bit/通道），可选密码加密。 */
@Composable
fun LsbScreen(onBack: () -> Unit, onOpenHistory: () -> Unit = {}, loadPath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var mode by remember { mutableStateOf(false) } // false=嵌入, true=提取
    var carrierBmp by remember { mutableStateOf<Bitmap?>(null) }
    var stegoBmp by remember { mutableStateOf<Bitmap?>(null) }
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var textMode by remember { mutableStateOf(true) } // true=文本, false=文件
    var textInput by remember { mutableStateOf("") }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var extractedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var extractedType by remember { mutableIntStateOf(LsbTank.TYPE_TEXT) }

    // 历史记录跳转加载：把历史缩略图作为载体图载入（可继续嵌入）
    LaunchedEffect(loadPath) {
        val p = loadPath
        if (p != null) {
            try {
                val bmp = withContext(Dispatchers.Default) { TuyinImages.decodeFileScaled(p, 2048) }
                if (bmp != null) {
                    mode = false
                    carrierBmp = bmp
                    status = t("已加载历史图片，可继续嵌入", "已載入歷史圖片，可繼續嵌入", "History image loaded as carrier")
                } else {
                    status = t("历史图片读取失败", "歷史圖片讀取失敗", "Failed to load history image")
                }
            } catch (e: Exception) { status = t("读取图片失败：", "讀取圖片失敗：", "Failed to read image: ") + e.message }
        }
    }

    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingSave?.invoke()
    }
    fun requestSave(bmp: Bitmap?, name: String) {
        if (bmp == null) { status = t("还没有可保存的图片", "還沒有可保存的圖片", "Nothing to save yet"); return }
        val doSave: () -> Unit = { scope.launch {
            val ok = withContext(Dispatchers.Default) { TuyinImages.saveBitmapToGallery(context, bmp, name, "image/png", 100) }
            if (ok) UnifiedHistory.add(context, "lsb", "save", bmp)
            status = if (ok) t("已保存到相册", "已保存到相冊", "Saved to gallery") else t("保存失败", "保存失敗", "Save failed")
        } }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = doSave
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave()
    }

    val pickCarrier = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 2048) }
                carrierBmp = TuyinImages.imageDataToBitmap(data)
            } catch (e: Exception) { status = t("读取载体图失败：", "讀取載體圖失敗：", "Failed to read carrier: ") + e.message }
        }
    }
    val pickStego = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 2048) }
                stegoBmp = TuyinImages.imageDataToBitmap(data)
            } catch (e: Exception) { status = t("读取图片失败：", "讀取圖片失敗：", "Failed to read image: ") + e.message }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) { fileBytes = bytes; fileName = uri.lastPathSegment ?: "file" }
            } catch (e: Exception) { status = t("读取文件失败：", "讀取檔案失敗：", "Failed to read file: ") + e.message }
        }
    }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = extractedBytes
            if (bytes == null) { status = t("没有可保存的文件", "沒有可保存的檔案", "Nothing to save"); return@launch }
            val ok = withContext(Dispatchers.Default) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            status = if (ok) t("文件已保存", "檔案已儲存", "File saved") else t("保存失败", "儲存失敗", "Save failed")
        }
    }

    fun formatSize(n: Int): String = when {
        n >= 1024 * 1024 -> String.format("%.1f MB", n / 1048576f)
        n >= 1024 -> String.format("%.1f KB", n / 1024f)
        else -> "$n B"
    }

    fun runEncode() {
        val c = carrierBmp
        if (c == null) { status = t("请先选择载体图片", "請先選擇載體圖片", "Pick a carrier image first"); return }
        val data = if (textMode) textInput.toByteArray(Charsets.UTF_8) else fileBytes
        if (data == null || data.isEmpty()) { status = t("请先输入文字或选择文件", "請先輸入文字或選擇檔案", "Enter text or pick a file first"); return }
        val cap = LsbTank.capacityOf(c.width * c.height)
        val pwd = password
        val encrypted = pwd.isNotBlank()
        val headBits = 8 * 8 + if (encrypted) 16 * 8 else 0
        if (data.size * 8 + headBits > cap * 8) {
            status = t("内容超出图片容量（可用 ", "內容超出圖片容量（可用 ", "Payload exceeds capacity (") + formatSize(cap) + t("）", "）", ")")
            return
        }
        scope.launch {
            status = t("正在嵌入…", "正在嵌入…", "Encoding…")
            try {
                resultBmp = withContext(Dispatchers.Default) {
                    LsbTank.encode(c, if (textMode) LsbTank.TYPE_TEXT else LsbTank.TYPE_FILE, data, pwd)
                }
                UnifiedHistory.add(context, "lsb", "encode", resultBmp)
                status = if (encrypted) t("嵌入完成（已加密），肉眼几乎看不出差异", "嵌入完成（已加密），肉眼幾乎看不出差異", "Done (encrypted), visually identical")
                else t("嵌入完成，肉眼几乎看不出差异", "嵌入完成，肉眼幾乎看不出差異", "Done, visually identical")
            } catch (e: Exception) { status = t("嵌入失败：", "嵌入失敗：", "Failed: ") + e.message }
        }
    }

    fun runExtract() {
        val s = stegoBmp
        if (s == null) { status = t("请先选择 LSB 图片", "請先選擇 LSB 圖片", "Pick the LSB image first"); return }
        scope.launch {
            status = t("正在提取…", "正在提取…", "Decoding…")
            try {
                val r = withContext(Dispatchers.Default) { LsbTank.decode(s, password.ifBlank { null }) }
                extractedType = r.type
                if (r.type == LsbTank.TYPE_TEXT) {
                    extractedText = String(r.data, Charsets.UTF_8)
                    extractedBytes = null
                    status = t("提取完成", "提取完成", "Done")
                } else {
                    extractedBytes = r.data
                    extractedText = null
                    status = t("已提取文件：", "已提取檔案：", "Extracted file: ") + formatSize(r.data.size)
                }
            } catch (e: Exception) {
                val m = e.message ?: ""
                status = when {
                    m.contains("wrong password") -> t("密码错误", "密碼錯誤", "Wrong password")
                    m.contains("password required") -> t("该图已加密，请输入密码", "該圖已加密，請輸入密碼", "This image is encrypted; enter the password")
                    m.contains("not an LSB image") -> t("这不是 LSB 图或数据已损坏", "這不是 LSB 圖或資料已損壞", "Not an LSB image or data corrupted")
                    else -> t("提取失败：", "提取失敗：", "Failed: ") + m
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SuBg)) {
        SuiteFadingTopBar(
            title = t("LSB 隐写", "LSB 隱寫", "LSB Stegano"),
            scrollOffset = scrollState.value.toFloat(),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SuiteModeSwitch(
                mode = mode,
                onMode = { mode = it },
                leftLabel = t("嵌入", "嵌入", "Encode"),
                rightLabel = t("提取", "提取", "Decode")
            )
            SuiteCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenHistory)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(t("历史记录", "歷史記錄", "History"), style = TextStyle(color = SuTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        BasicText(t("查看所有功能的操作记录", "查看所有功能的操作記錄", "View all feature history"), style = TextStyle(color = SuSub, fontSize = 12.sp))
                    }
                    BasicText("›", style = TextStyle(color = SuSub, fontSize = 22.sp), modifier = Modifier.padding(start = 8.dp))
                }
            }
            SuiteCard {
                SuiteTileRow(
                    t("原理", "原理", "How it works"),
                    t("把文字或文件的每一位写进 PNG 像素通道的末 2 位，肉眼几乎看不出差异；只能存无损图（PNG）", "把文字或檔案的每一位寫進 PNG 像素通道的末 2 位，肉眼幾乎看不出差異；只能存無損圖（PNG）", "Each bit of your text/file is stored in the 2 low bits of PNG pixel channels; lossless PNG only"))
                { }
            }
            if (!mode) {
                SuitePickCard(
                    title = t("载体图片 · 选 PNG", "載體圖片 · 選 PNG", "Carrier (PNG)"),
                    hint = t("点击选择承载图片", "點擊選擇承載圖片", "Tap to pick the carrier image"),
                    color = Color(0xFF00A3C4),
                    bmp = carrierBmp,
                    onClick = { pickCarrier.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )
                SuiteCard {
                    SuiteTileRow(t("嵌入内容", "嵌入內容", "Payload"), t("文字或任意文件（不超过图片容量）", "文字或任意檔案（不超過圖片容量）", "Text or any file (within capacity)")) { }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("文本", "文本", "Text"), textMode, { textMode = true }, Modifier.weight(1f))
                        SuiteSegButton(t("文件", "檔案", "File"), !textMode, { textMode = false }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (textMode) {
                        LsbTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = t("输入要隐藏的文字…", "輸入要隱藏的文字…", "Type the text to hide…"),
                            singleLine = false,
                            minHeight = 96.dp
                        )
                        Spacer(Modifier.height(6.dp))
                        BasicText(
                            t("已输入 ", "已輸入 ", "Text: ") + textInput.toByteArray(Charsets.UTF_8).size + t(" 字节", " 字節", " B"),
                            style = TextStyle(color = SuSub, fontSize = 12.sp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SuSegBg)
                                .clickable { pickFile.launch(arrayOf("application/octet-stream")) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicText(
                                if (fileBytes != null) fileName + t("（", "（", " (") + formatSize(fileBytes!!.size) + t("）", "）", ")")
                                else t("点击选择要隐藏的文件", "點擊選擇要隱藏的檔案", "Tap to pick a file to hide"),
                                style = TextStyle(color = if (fileBytes != null) SuTitle else SuSub, fontSize = 13.sp)
                            )
                        }
                    }
                }
                SuiteCard {
                    SuiteTileRow(t("密码（可选）", "密碼（可選）", "Password (optional)"), t("留空不加密；提取时需输入相同密码", "留空不加密；提取時需輸入相同密碼", "Leave blank = no encryption; same password needed to decode")) { }
                    Spacer(Modifier.height(10.dp))
                    LsbTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = t("输入密码…", "輸入密碼…", "Enter a password…"),
                        singleLine = true,
                        password = true
                    )
                }
                val cc = carrierBmp
                if (cc != null) {
                    val cap = LsbTank.capacityOf(cc.width * cc.height)
                    val data = if (textMode) textInput.toByteArray(Charsets.UTF_8) else fileBytes
                    val need = data?.let { it.size + 8 + if (password.isNotBlank()) 16 else 0 } ?: 0
                    val overflow = data != null && data.isNotEmpty() && need > cap
                    val frac = if (data != null && data.isNotEmpty() && cap > 0) (need.toFloat() / cap).coerceIn(0f, 1f) else 0f
                    Column {
                        // 容量进度池
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(SuSegBg)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(frac)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (overflow) Color(0xFFE5484D) else suiteAccent)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        BasicText(
                            t("已用 ", "已用 ", "Used ") + formatSize(need) + t(" / 容量 ", " / 容量 ", " / cap ") + formatSize(cap) +
                                    if (data != null && data.isNotEmpty()) String.format("（%.0f%%）", need.toFloat() / cap * 100f) else "",
                            style = TextStyle(color = if (overflow) Color(0xFFE5484D) else SuSub, fontSize = 12.sp)
                        )
                    }
                }
                SuiteMainButton(t("生成 LSB 图", "生成 LSB 圖", "Generate"), enabled = carrierBmp != null) { runEncode() }
                if (resultBmp != null) {
                    SuiteResultCard(
                        title = t("嵌入结果", "嵌入結果", "Result"),
                        bmp = resultBmp,
                        emptyHint = ""
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            SuiteGhostButton(t("保存到相册", "保存到相冊", "Save"), SuBlue) { requestSave(resultBmp, "lsb_embed.png") }
                        }
                        Box(Modifier.weight(1f)) {
                            SuiteGhostButton(t("重置", "重置", "Reset"), Color(0xFFE5484D)) {
                                resultBmp = null; carrierBmp = null; textInput = ""; fileBytes = null; fileName = ""; status = ""
                            }
                        }
                    }
                }
            } else {
                SuitePickCard(
                    title = t("选择 LSB 图片", "選擇 LSB 圖片", "Pick LSB image"),
                    hint = t("点击选择要提取的图片", "點擊選擇要提取的圖片", "Tap to pick the image to decode"),
                    color = Color(0xFF00A3C4),
                    bmp = stegoBmp,
                    onClick = { pickStego.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )
                SuiteCard {
                    SuiteTileRow(t("密码", "密碼", "Password"), t("嵌入时设置了密码则必须输入", "嵌入時設置了密碼則必須輸入", "Required if the payload was encrypted")) { }
                    Spacer(Modifier.height(10.dp))
                    LsbTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = t("输入密码…（未加密可留空）", "輸入密碼…（未加密可留空）", "Password… (blank if unencrypted)"),
                        singleLine = true,
                        password = true
                    )
                }
                SuiteMainButton(t("提取内容", "提取內容", "Decode"), enabled = stegoBmp != null) { runExtract() }
                if (stegoBmp != null) {
                    SuiteGhostButton(t("重置", "重置", "Reset"), Color(0xFFE5484D)) {
                        stegoBmp = null; extractedText = null; extractedBytes = null; status = ""
                    }
                }
                if (extractedText != null) {
                    SuiteCard {
                        SuiteTileRow(t("提取的文字", "提取的文字", "Decoded text"), "") { }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SuSegBg)
                                .padding(12.dp)
                        ) {
                            BasicText(extractedText ?: "", style = TextStyle(color = SuTitle, fontSize = 13.sp))
                        }
                    }
                }
                if (extractedBytes != null) {
                    SuiteCard {
                        SuiteTileRow(
                            t("提取的文件", "提取的檔案", "Decoded file"),
                            t("大小：", "大小：", "Size: ") + formatSize(extractedBytes!!.size)
                        ) { }
                        Spacer(Modifier.height(10.dp))
                        SuiteGhostButton(t("保存文件", "儲存檔案", "Save file"), SuBlue) {
                            saveFileLauncher.launch("tuyin_extract.bin")
                        }
                    }
                }
            }
            if (status.isNotEmpty()) {
                BasicText(status, style = TextStyle(color = SuSub, fontSize = 13.sp))
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

/** LSB 页输入框：圆角底色 + 占位符，支持多行与密码遮罩 */
@Composable
private fun LsbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 48.dp,
    password: Boolean = false
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = SuTitle, fontSize = 14.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(SuSegBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) BasicText(placeholder, style = TextStyle(color = SuSub, fontSize = 14.sp))
                inner()
            }
        }
    )
}
