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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.setgo.tank.core.SuiteEngines
import com.setgo.tank.core.SuiteEngines.CoverProcess
import com.setgo.tank.core.SuiteEngines.PrismMethod
import com.setgo.tank.core.TuyinImages
import com.setgo.tank.core.UnifiedHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 光棱坦克二级页：原版算法（Uyanide/Mirage_Decode）——生成（棋盘穿插）+ 显影（阈值放大）。 */
@Composable
fun PrismTankScreen(onBack: () -> Unit, loadPath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var mode by remember { mutableStateOf(false) } // false=生成, true=显影
    var frontBmp by remember { mutableStateOf<Bitmap?>(null) }
    var backBmp by remember { mutableStateOf<Bitmap?>(null) }
    var stegoBmp by remember { mutableStateOf<Bitmap?>(null) }
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var previewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var fullBmp by remember { mutableStateOf<Bitmap?>(null) }
    var method by remember { mutableStateOf(PrismMethod.Chess) }
    var coverGray by remember { mutableStateOf(true) }
    var innerTh by remember { mutableIntStateOf(40) }
    var coverTh by remember { mutableIntStateOf(60) }
    var encodeRev by remember { mutableStateOf(false) }
    var revealTh by remember { mutableIntStateOf(120) }
    var coverProc by remember { mutableStateOf(CoverProcess.LuAvg) }
    var decodeRev by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var previewJob by remember { mutableStateOf<Job?>(null) }

    // 历史记录跳转加载：把历史缩略图作为表图载入（可继续生成）
    LaunchedEffect(loadPath) {
        val p = loadPath
        if (p != null) {
            try {
                val bmp = withContext(Dispatchers.Default) { TuyinImages.decodeFileScaled(p, 2048) }
                if (bmp != null) {
                    mode = false
                    frontBmp = bmp
                    status = t("已加载历史图片作为表图", "已載入歷史圖片作為表圖", "History image loaded as cover")
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
            if (ok) UnifiedHistory.add(context, "prism", "save", bmp)
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

    val pickFront = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 2048) }
                frontBmp = TuyinImages.imageDataToBitmap(data)
            } catch (e: Exception) { status = t("读取表图失败：", "讀取表圖失敗：", "Failed to read cover: ") + e.message }
        }
    }
    val pickBack = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 2048) }
                backBmp = TuyinImages.imageDataToBitmap(data)
            } catch (e: Exception) { status = t("读取里图失败：", "讀取裡圖失敗：", "Failed to read inner: ") + e.message }
        }
    }
    val pickStego = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 2048) }
                stegoBmp = TuyinImages.imageDataToBitmap(data)
            } catch (e: Exception) { status = t("读取光棱图失败：", "讀取光稜圖失敗：", "Failed to read prism image: ") + e.message }
        }
    }

    fun runEncode() {
        val f = frontBmp; val b = backBmp
        if (f == null || b == null) { status = t("请先选择表图和里图", "請先選擇表圖和裡圖", "Pick cover & inner first"); return }
        scope.launch {
            status = t("正在生成…", "正在生成…", "Generating…")
            try {
                val out = withContext(Dispatchers.Default) {
                    SuiteEngines.prismTank(f, b, innerTh, coverTh, method, coverGray, encodeRev)
                }
                resultBmp = out
                UnifiedHistory.add(context, "prism", "encode", out)
                previewBmp = withContext(Dispatchers.Default) {
                    SuiteEngines.prismReveal(out, revealTh, decodeRev, coverProc)
                }
                status = t("生成完成，用显影页按相同参数显现里图", "生成完成，用顯影頁按相同參數顯現裡圖", "Done. Reveal in the Reveal tab with the same params")
            } catch (e: Exception) { status = t("生成失败：", "生成失敗：", "Failed: ") + e.message }
        }
    }

    fun runReveal() {
        val s = stegoBmp
        if (s == null) { status = t("请先选择光棱坦克图", "請先選擇光稜坦克圖", "Pick the prism image first"); return }
        scope.launch {
            status = t("正在显影…", "正在顯影…", "Revealing…")
            try {
                resultBmp = withContext(Dispatchers.Default) {
                    SuiteEngines.prismReveal(s, revealTh, decodeRev, coverProc)
                }
                status = t("显影完成（阈值需与生成时匹配）", "顯影完成（閾值需與生成時匹配）", "Revealed (threshold should match encoding)")
            } catch (e: Exception) { status = t("显影失败：", "顯影失敗：", "Failed: ") + e.message }
        }
    }

    // 生成后实时显影预览（参数变化防抖重算）
    fun autoRevealPreview() {
        val r = resultBmp ?: return
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(250)
            try {
                previewBmp = withContext(Dispatchers.Default) {
                    // 预览降采样（最长边 480），计算量降 ~16 倍；最终显影仍走全分辨率
                    SuiteEngines.prismReveal(SuiteEngines.previewOf(r), revealTh, decodeRev, coverProc)
                }
            } catch (_: Exception) { }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SuBg)) {
        SuiteFadingTopBar(
            title = t("光棱坦克", "光稜坦克", "Prism Tank"),
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
                leftLabel = t("生成", "生成", "Encode"),
                rightLabel = t("显影", "顯影", "Reveal")
            )
            SuiteCard {
                SuiteTileRow(t("原理", "原理", "How it works"), t("原版算法（Uyanide/Mirage_Decode）：里图按棋盘/间隔穿插到表图中压暗，显影时按阈值放大显现", "原版算法（Uyanide/Mirage_Decode）：裡圖按棋盤/間隔穿插到表圖中壓暗，顯影時按閾值放大顯現", "Original Mirage_Decode: inner pixels interleaved & darkened; reveal amplifies below threshold")) { }
            }
            if (!mode) {
                SuitePickCard(
                    title = t("表图 · 正常显示", "表圖 · 正常顯示", "Cover (normal view)"),
                    hint = t("点击选择正常情况下显示的图片", "點擊選擇正常情況下顯示的圖片", "Tap to pick the cover image"),
                    color = Color(0xFF00B96B),
                    bmp = frontBmp,
                    onClick = { pickFront.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )
                SuitePickCard(
                    title = t("里图 · 显影后显示", "裡圖 · 顯影後顯示", "Inner (revealed)"),
                    hint = t("点击选择要藏入的图片", "點擊選擇要藏入的圖片", "Tap to pick the inner image"),
                    color = Color(0xFF00B96B),
                    bmp = backBmp,
                    onClick = { pickBack.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )
                SuiteCard {
                    SuiteTileRow(t("像素摆放", "像素擺放", "Layout"), t("里图像素如何穿插进表图（原版 method）", "裡圖像素如何穿插進表圖（原版 method）", "How inner pixels interleave (original method)")) { }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("棋盘格", "棋盤格", "Chess"), method == PrismMethod.Chess, { method = PrismMethod.Chess }, Modifier.weight(1f))
                        SuiteSegButton(t("隔 2", "隔 2", "Gap 2"), method == PrismMethod.Gap2, { method = PrismMethod.Gap2 }, Modifier.weight(1f))
                        SuiteSegButton(t("隔 3", "隔 3", "Gap 3"), method == PrismMethod.Gap3, { method = PrismMethod.Gap3 }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("隔 5", "隔 5", "Gap 5"), method == PrismMethod.Gap5, { method = PrismMethod.Gap5 }, Modifier.weight(1f))
                        SuiteSegButton(t("奇偶列", "奇偶列", "Col parity"), method == PrismMethod.Col1, { method = PrismMethod.Col1 }, Modifier.weight(1f))
                        SuiteSegButton(t("奇偶行", "奇偶行", "Row parity"), method == PrismMethod.Row1, { method = PrismMethod.Row1 }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(t("当前：", "當前：", "Now: ") + methodLabel(method) + " — " + methodHint(method), style = TextStyle(color = SuSub, fontSize = 12.sp))
                }
                SuiteCard {
                    SuiteTileRow(t("表图取色", "表圖取色", "Cover color"), t("灰度模式表图更干净；彩色保留表图颜色", "灰度模式表圖更乾淨；彩色保留表圖顏色", "Gray keeps cover clean; color keeps cover colors")) { }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("灰度", "灰度", "Gray"), coverGray, { coverGray = true }, Modifier.weight(1f))
                        SuiteSegButton(t("彩色", "彩色", "Color"), !coverGray, { coverGray = false }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("正向", "正向", "Forward"), !encodeRev, { encodeRev = false }, Modifier.weight(1f))
                        SuiteSegButton(t("反向", "反向", "Reverse"), encodeRev, { encodeRev = true }, Modifier.weight(1f))
                    }
                }
                SuiteCard {
                    SuiteTileRow(t("生成阈值", "生成閾值", "Thresholds"), t("里图阈值控制里图压暗程度；表图阈值控制表图亮度偏移", "裡圖閾值控制裡圖壓暗程度；表圖閾值控制表圖亮度偏移", "Inner threshold = inner darkness; cover threshold = cover offset")) { }
                    Spacer(Modifier.height(6.dp))
                    SuiteParamSlider(t("里图阈值", "裡圖閾值", "Inner thr"), innerTh, 10, 120) { innerTh = it }
                    SuiteParamSlider(t("表图阈值", "表圖閾值", "Cover thr"), coverTh, 20, 200) { coverTh = it }
                }
                SuiteMainButton(t("生成光棱坦克", "生成光稜坦克", "Generate"), enabled = frontBmp != null && backBmp != null) { runEncode() }
                if (resultBmp != null) {
                    SuiteCard {
                        SuiteTileRow(t("生成结果", "生成結果", "Result"), t("正常亮度看表图；下方为阈值显影预览", "正常亮度看表圖；下方為閾值顯影預覽", "Cover visible normally; below is reveal preview")) { }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = remember(resultBmp) { resultBmp!!.asImageBitmap() },
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SuiteParamSlider(t("显影阈值预览", "顯影閾值預覽", "Reveal thr"), revealTh, 30, 255) { revealTh = it; autoRevealPreview() }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SuiteSegButton(t("正向", "正向", "Fwd"), !decodeRev, { decodeRev = false; autoRevealPreview() }, Modifier.weight(1f))
                            SuiteSegButton(t("反向", "反向", "Rev"), decodeRev, { decodeRev = true; autoRevealPreview() }, Modifier.weight(1f))
                        }
                        if (previewBmp != null) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = remember(previewBmp) { previewBmp!!.asImageBitmap() },
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            SuiteGhostButton(t("保存原图", "保存原圖", "Save"), SuBlue) { requestSave(resultBmp, "prism_tank.png") }
                        }
                        Box(Modifier.weight(1f)) {
                            SuiteGhostButton(t("重置", "重置", "Reset"), Color(0xFFE5484D)) {
                                resultBmp = null; previewBmp = null; frontBmp = null; backBmp = null; status = ""
                            }
                        }
                    }
                }
            } else {
                SuitePickCard(
                    title = t("选择光棱坦克图", "選擇光稜坦克圖", "Pick prism image"),
                    hint = t("点击选择需要显影的图片", "點擊選擇需要顯影的圖片", "Tap to pick the image to reveal"),
                    color = Color(0xFF00B96B),
                    bmp = stegoBmp,
                    onClick = { pickStego.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )
                SuiteCard {
                    SuiteTileRow(t("显影参数", "顯影參數", "Reveal params"), t("阈值分割：低于（正向）或高于（反向）阈值的像素放大显现里图", "閾值分割：低於（正向）或高於（反向）閾值的像素放大顯現裡圖", "Pixels below (fwd) / above (rev) the threshold are amplified")) { }
                    Spacer(Modifier.height(6.dp))
                    SuiteParamSlider(t("显影阈值", "顯影閾值", "Threshold"), revealTh, 30, 255) { revealTh = it }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("正向", "正向", "Forward"), !decodeRev, { decodeRev = false }, Modifier.weight(1f))
                        SuiteSegButton(t("反向", "反向", "Reverse"), decodeRev, { decodeRev = true }, Modifier.weight(1f))
                    }
                }
                SuiteCard {
                    SuiteTileRow(t("表图区域处理", "表圖區域處理", "Cover fill"), t("显影时非里图区域的填充方式", "顯影時非裡圖區域的填充方式", "How non-inner pixels are filled")) { }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("左上平均", "左上平均", "Avg top-left"), coverProc == CoverProcess.LuAvg, { coverProc = CoverProcess.LuAvg }, Modifier.weight(1f))
                        SuiteSegButton(t("复制左侧", "複製左側", "Copy left"), coverProc == CoverProcess.LCopy, { coverProc = CoverProcess.LCopy }, Modifier.weight(1f))
                        SuiteSegButton(t("复制上方", "複製上方", "Copy top"), coverProc == CoverProcess.UCopy, { coverProc = CoverProcess.UCopy }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuiteSegButton(t("透明", "透明", "Transparent"), coverProc == CoverProcess.Trans, { coverProc = CoverProcess.Trans }, Modifier.weight(1f))
                        SuiteSegButton(t("黑色", "黑色", "Black"), coverProc == CoverProcess.Black, { coverProc = CoverProcess.Black }, Modifier.weight(1f))
                        SuiteSegButton(t("白色", "白色", "White"), coverProc == CoverProcess.White, { coverProc = CoverProcess.White }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(t("当前：", "當前：", "Now: ") + coverLabel(coverProc) + " — " + coverHint(coverProc), style = TextStyle(color = SuSub, fontSize = 12.sp))
                }
                SuiteMainButton(t("开始显影", "開始顯影", "Reveal"), enabled = stegoBmp != null) { runReveal() }
                if (resultBmp != null) {
                    SuiteCard {
                        SuiteTileRow(t("显影结果", "顯影結果", "Result"), t("点击图片可全屏查看", "點擊圖片可全屏查看", "Tap image to view full screen")) { }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                                .clickable { fullBmp = resultBmp },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = remember(resultBmp) { resultBmp!!.asImageBitmap() },
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            SuiteGhostButton(t("保存到相册", "保存到相冊", "Save"), SuBlue) { requestSave(resultBmp, "prism_reveal.png") }
                        }
                        Box(Modifier.weight(1f)) {
                            SuiteGhostButton(t("重置", "重置", "Reset"), Color(0xFFE5484D)) {
                                resultBmp = null; stegoBmp = null; status = ""
                            }
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
    SuiteImageViewer(bmp = fullBmp, onDismiss = { fullBmp = null })
}

/** 摆放方式名三语映射（数据结构的 label/hint 为中文，显示层翻译） */
@Composable
private fun methodLabel(m: PrismMethod): String = when (m) {
    PrismMethod.Chess -> t("棋盘格", "棋盤格", "Chess")
    PrismMethod.Gap2 -> t("隔 2", "隔 2", "Gap 2")
    PrismMethod.Gap3 -> t("隔 3", "隔 3", "Gap 3")
    PrismMethod.Gap5 -> t("隔 5", "隔 5", "Gap 5")
    PrismMethod.Col1 -> t("奇偶列", "奇偶列", "Col parity")
    PrismMethod.Row1 -> t("奇偶行", "奇偶行", "Row parity")
}

@Composable
private fun methodHint(m: PrismMethod): String = when (m) {
    PrismMethod.Chess -> t("斜线棋盘，隔一像素放里图", "斜線棋盤，隔一像素放裡圖", "Diagonal chessboard; inner every other pixel")
    PrismMethod.Gap2 -> t("斜线隔 2 像素放里图", "斜線隔 2 像素放裡圖", "Diagonal; inner every 2 pixels")
    PrismMethod.Gap3 -> t("斜线隔 3 像素放里图", "斜線隔 3 像素放裡圖", "Diagonal; inner every 3 pixels")
    PrismMethod.Gap5 -> t("斜线隔 5 像素放里图", "斜線隔 5 像素放裡圖", "Diagonal; inner every 5 pixels")
    PrismMethod.Col1 -> t("按列交替放里图", "按列交替放裡圖", "Inner on alternating columns")
    PrismMethod.Row1 -> t("按行交替放里图", "按行交替放裡圖", "Inner on alternating rows")
}

@Composable
private fun coverLabel(c: CoverProcess): String = when (c) {
    CoverProcess.LuAvg -> t("左上平均", "左上平均", "Avg top-left")
    CoverProcess.LCopy -> t("复制左侧", "複製左側", "Copy left")
    CoverProcess.UCopy -> t("复制上方", "複製上方", "Copy top")
    CoverProcess.Trans -> t("透明", "透明", "Transparent")
    CoverProcess.Black -> t("黑色", "黑色", "Black")
    CoverProcess.White -> t("白色", "白色", "White")
}

@Composable
private fun coverHint(c: CoverProcess): String = when (c) {
    CoverProcess.LuAvg -> t("用左上邻居均值填充", "用左上鄰居均值填充", "Fill with top-left neighbor average")
    CoverProcess.LCopy -> t("复制左边像素", "複製左邊像素", "Copy left pixel")
    CoverProcess.UCopy -> t("复制上方像素", "複製上方像素", "Copy pixel above")
    CoverProcess.Trans -> t("置为透明（保留原图）", "置為透明（保留原圖）", "Make transparent (keep original)")
    CoverProcess.Black -> t("置为黑色", "置為黑色", "Fill black")
    CoverProcess.White -> t("置为白色", "置為白色", "Fill white")
}
