package com.setgo.tank

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.setgo.tank.core.SuiteEngines
import com.setgo.tank.core.TuyinImages
import com.setgo.tank.core.UnifiedHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 幻影坦克二级页：表图（白底）+ 里图（黑底）合成一张带透明通道的 PNG。 */
@Composable
fun MirageTankScreen(onBack: () -> Unit, loadPath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var frontBmp by remember { mutableStateOf<Bitmap?>(null) }
    var backBmp by remember { mutableStateOf<Bitmap?>(null) }
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var previewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var previewWhite by remember { mutableStateOf(true) }
    var backMix by remember { mutableIntStateOf(50) }      // 里图混合权重 0-100
    var frontGain by remember { mutableIntStateOf(100) }   // 表图亮度 50-200 -> /100
    var frontDesat by remember { mutableIntStateOf(0) }    // 表图去色 0-100
    var backGain by remember { mutableIntStateOf(100) }    // 里图亮度 50-200 -> /100
    var backDesat by remember { mutableIntStateOf(0) }     // 里图去色 0-100
    var outScale by remember { mutableFloatStateOf(1f) }
    var status by remember { mutableStateOf("") }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val scrollState = rememberScrollState()

    // 历史记录跳转加载：把历史缩略图作为表图载入（可继续合成）
    LaunchedEffect(loadPath) {
        val p = loadPath
        if (p != null) {
            try {
                val bmp = withContext(Dispatchers.Default) { TuyinImages.decodeFileScaled(p, 2048) }
                if (bmp != null) {
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
            if (ok) UnifiedHistory.add(context, "phantom", "save", bmp)
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

    fun generate() {
        val f = frontBmp; val b = backBmp
        if (f == null || b == null) { status = t("请先选择表图和里图", "請先選擇表圖和裡圖", "Pick cover & inner first"); return }
        scope.launch {
            status = t("正在合成…", "正在合成…", "Compositing…")
            try {
                val out = withContext(Dispatchers.Default) {
                    SuiteEngines.mirageTank(
                        f, b,
                        backMix / 100f, frontGain / 100f, frontDesat / 100f,
                        backGain / 100f, backDesat / 100f, outScale
                    )
                }
                resultBmp = out
                UnifiedHistory.add(context, "phantom", "encode", out)
                previewBmp = withContext(Dispatchers.Default) {
                    SuiteEngines.compositeOn(out, if (previewWhite) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                }
                status = t("合成完成，可用下方黑白背景预览效果", "合成完成，可用下方黑白背景預覽效果", "Done. Preview on white/black background below")
            } catch (e: Exception) { status = t("合成失败：", "合成失敗：", "Failed: ") + e.message }
        }
    }

    fun refreshPreview() {
        val r = resultBmp ?: return
        scope.launch {
            previewBmp = withContext(Dispatchers.Default) {
                SuiteEngines.compositeOn(r, if (previewWhite) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            }
        }
    }

    // 参数变化实时预览（250ms 防抖，避免拖动滑块频繁重算）
    fun autoPreview() {
        val f = frontBmp; val b = backBmp
        if (f == null || b == null) return
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(250)
            try {
                val out = withContext(Dispatchers.Default) {
                    SuiteEngines.mirageTank(
                        f, b,
                        backMix / 100f, frontGain / 100f, frontDesat / 100f,
                        backGain / 100f, backDesat / 100f, outScale
                    )
                }
                resultBmp = out
                previewBmp = withContext(Dispatchers.Default) {
                    SuiteEngines.compositeOn(out, if (previewWhite) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                }
                status = t("参数已更新（实时预览）", "參數已更新（即時預覽）", "Params updated (live preview)")
            } catch (e: Exception) { status = t("预览失败：", "預覽失敗：", "Preview failed: ") + e.message }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SuBg)) {
        SuiteFadingTopBar(
            title = t("幻影坦克", "幻影坦克", "Phantom Tank"),
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
            SuiteCard {
                SuiteTileRow(t("原理", "原理", "How it works"),
                    t("同一张 PNG：白底显示表图，黑底显示里图（利用透明通道）", "同一張 PNG：白底顯示表圖，黑底顯示裡圖（利用透明通道）", "One PNG: cover on white, inner on black (via alpha)")) { }
            }
            SuitePickCard(
                title = t("表图 · 白底显示", "表圖 · 白底顯示", "Cover (white bg)"),
                hint = t("点击选择白色背景下显示的图片", "點擊選擇白色背景下顯示的圖片", "Tap to pick the image shown on white"),
                color = Color(0xFF3482FF),
                bmp = frontBmp,
                onClick = { pickFront.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
            SuitePickCard(
                title = t("里图 · 黑底显示", "裡圖 · 黑底顯示", "Inner (black bg)"),
                hint = t("点击选择黑色背景下显示的图片", "點擊選擇黑色背景下顯示的圖片", "Tap to pick the image shown on black"),
                color = Color(0xFF9C5BFF),
                bmp = backBmp,
                onClick = { pickBack.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
            SuiteCard {
                SuiteTileRow(t("参数调节", "參數調節", "Adjustments"),
                    t("参考 Mirage_Colored：混合权重、亮度（色阶缩放）与去色程度，全部实时预览", "參考 Mirage_Colored：混合權重、亮度（色階縮放）與去色程度，全部即時預覽", "From Mirage_Colored: mix, brightness (levels) & desaturation, live preview")) { }
                Spacer(Modifier.height(6.dp))
                SuiteParamSlider(t("里图混合权重", "裡圖混合權重", "Inner mix"), backMix, 0, 100) { backMix = it; autoPreview() }
                SuiteParamSlider(t("表图亮度", "表圖亮度", "Cover brightness"), frontGain, 50, 200) { frontGain = it; autoPreview() }
                SuiteParamSlider(t("表图去色", "表圖去色", "Cover desat"), frontDesat, 0, 100) { frontDesat = it; autoPreview() }
                SuiteParamSlider(t("里图亮度", "裡圖亮度", "Inner brightness"), backGain, 50, 200) { backGain = it; autoPreview() }
                SuiteParamSlider(t("里图去色", "裡圖去色", "Inner desat"), backDesat, 0, 100) { backDesat = it; autoPreview() }
                Spacer(Modifier.height(4.dp))
                // 表里互换
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        SuiteGhostButton(t("交换表里图", "交換表裡圖", "Swap cover/inner"), SuBlue) {
                            val t = frontBmp; frontBmp = backBmp; backBmp = t
                            autoPreview()
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        SuiteGhostButton(t("重置图片", "重置圖片", "Reset images"), Color(0xFFE5484D)) {
                            frontBmp = null; backBmp = null; resultBmp = null; previewBmp = null; status = ""
                        }
                    }
                }
            }
            SuiteCard {
                SuiteTileRow(t("输出尺寸", "輸出尺寸", "Output size"),
                    t("缩小输出可减少体积，画布按最大边等比缩放", "縮小輸出可減少體積，畫布按最大邊等比縮放", "Smaller output = smaller file; scales by longest edge")) { }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteSegButton(t("原尺寸", "原尺寸", "Full"), outScale == 1f, { outScale = 1f }, Modifier.weight(1f))
                    SuiteSegButton("75%", outScale == 0.75f, { outScale = 0.75f }, Modifier.weight(1f))
                    SuiteSegButton("50%", outScale == 0.5f, { outScale = 0.5f }, Modifier.weight(1f))
                }
            }
            SuiteMainButton(t("生成幻影坦克", "生成幻影坦克", "Generate Phantom"), enabled = frontBmp != null && backBmp != null) { generate() }
            if (resultBmp != null) {
                SuiteCard {
                    SuiteTileRow(t("黑白背景预览", "黑白背景預覽", "Black/white preview"),
                        if (previewWhite) t("当前：白色背景", "當前：白色背景", "Now: white bg") else t("当前：黑色背景", "當前：黑色背景", "Now: black bg")) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(SuSegBg)
                                .clickable { previewWhite = !previewWhite; refreshPreview() }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            BasicText(if (previewWhite) t("切到黑底", "切到黑底", "Switch to black") else t("切到白底", "切到白底", "Switch to white"), style = TextStyle(color = SuTitle, fontSize = 12.sp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (previewBmp != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (previewWhite) Color.White else Color.Black)
                                .clickable { previewWhite = !previewWhite; refreshPreview() },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = previewBmp!!.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        SuiteGhostButton(t("保存到相册", "保存到相冊", "Save"), SuBlue) { requestSave(resultBmp, "mirage_tank.png") }
                    }
                    Box(Modifier.weight(1f)) {
                        SuiteGhostButton(t("重置", "重置", "Reset"), Color(0xFFE5484D)) {
                            resultBmp = null; previewBmp = null; frontBmp = null; backBmp = null; status = ""
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
