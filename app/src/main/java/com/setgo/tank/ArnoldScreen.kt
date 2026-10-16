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
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.setgo.tank.core.SuiteEngines
import com.setgo.tank.core.TuyinImages
import com.setgo.tank.core.UnifiedHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片混淆二级页：多种可逆混淆算法。
 * 链式操作：混淆/还原结果直接作为下一次输入，可连续操作；保存统一为 PNG 无损（JPEG 会失真导致还原失败）。
 */
@Composable
fun ArnoldScreen(onBack: () -> Unit, loadPath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var originalBmp by remember { mutableStateOf<Bitmap?>(null) }  // 原始图（相册选入 / 历史加载）
    var currentBmp by remember { mutableStateOf<Bitmap?>(null) }   // 当前工作图（链式结果）
    var scrambles by remember { mutableIntStateOf(0) }             // 已混淆次数
    var restores by remember { mutableIntStateOf(0) }              // 已还原次数
    var algo by remember { mutableStateOf(SuiteEngines.ScrambleAlgo.Gilbert) }
    var key by remember { mutableStateOf("114514") }
    var status by remember { mutableStateOf("") }
    var previewBmp by remember { mutableStateOf<Bitmap?>(null) }

    // 历史记录跳转加载：把历史缩略图作为新的原始图载入
    LaunchedEffect(loadPath) {
        val p = loadPath
        if (p != null) {
            try {
                val bmp = withContext(Dispatchers.Default) { TuyinImages.decodeFileScaled(p, 2048) }
                if (bmp != null) {
                    originalBmp = bmp; currentBmp = bmp; scrambles = 0; restores = 0
                    status = t("已加载历史图片，可继续操作", "已載入歷史圖片，可繼續操作", "History image loaded")
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
            if (ok) UnifiedHistory.add(context, "arnold", "save", bmp)
            status = if (ok) t("已保存到相册（PNG 无损）", "已保存到相冊（PNG 無損）", "Saved to gallery (lossless PNG)") else t("保存失败", "保存失敗", "Save failed")
        } }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = doSave
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave()
    }

    val pickSrc = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 2048) }
                val bmp = TuyinImages.imageDataToBitmap(data)
                originalBmp = bmp; currentBmp = bmp; scrambles = 0; restores = 0
                status = t("已选图，可连续混淆或还原", "已選圖，可連續混淆或還原", "Image picked; scramble or restore anytime")
            } catch (e: Exception) { status = t("读取图片失败：", "讀取圖片失敗：", "Failed to read image: ") + e.message }
        }
    }

    fun runScramble() {
        val cur = currentBmp
        if (cur == null) { status = t("请先选择要混淆的图片", "請先選擇要混淆的圖片", "Pick an image first"); return }
        if (algo.needsKey && key.isBlank()) { status = t("请先输入密钥", "請先輸入密鑰", "Enter a key first"); return }
        scope.launch {
            status = t("正在混淆…", "正在混淆…", "Scrambling…")
            try {
                val out = withContext(Dispatchers.Default) { SuiteEngines.obfuscate(cur, algo, key, reverse = false) }
                currentBmp = out; scrambles++
                UnifiedHistory.add(context, "arnold", "scramble", out)
                status = t("已混淆 " + scrambles + " 次，继续点可链式操作", "已混淆 " + scrambles + " 次，繼續點可鏈式操作", "Scrambled x" + scrambles + "; keep tapping to chain")
            } catch (e: Exception) { status = t("混淆失败：", "混淆失敗：", "Failed: ") + e.message }
        }
    }

    fun runRestore() {
        val cur = currentBmp
        if (cur == null) { status = t("请先选择要还原的图片", "請先選擇要還原的圖片", "Pick an image first"); return }
        if (algo.needsKey && key.isBlank()) { status = t("请先输入密钥", "請先輸入密鑰", "Enter a key first"); return }
        scope.launch {
            status = t("正在还原…", "正在還原…", "Restoring…")
            try {
                val out = withContext(Dispatchers.Default) { SuiteEngines.obfuscate(cur, algo, key, reverse = true) }
                currentBmp = out; restores++
                UnifiedHistory.add(context, "arnold", "restore", out)
                status = t("已还原 " + restores + " 次（需与混淆时算法、密钥一致）", "已還原 " + restores + " 次（需與混淆時算法、密鑰一致）", "Restored x" + restores + " (same algo & key)")
            } catch (e: Exception) { status = t("还原失败：", "還原失敗：", "Failed: ") + e.message }
        }
    }

    fun saveName(): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return algo.name.lowercase() + "_" + date + ".png"
    }

    fun stateText(): String = when {
        scrambles == 0 && restores == 0 -> t("原始图", "原始圖", "Original")
        restores == 0 -> t("已混淆 " + scrambles + " 次", "已混淆 " + scrambles + " 次", "Scrambled x" + scrambles)
        scrambles == 0 -> t("已还原 " + restores + " 次", "已還原 " + restores + " 次", "Restored x" + restores)
        else -> t("已混淆 " + scrambles + " 次 · 已还原 " + restores + " 次", "已混淆 " + scrambles + " 次 · 已還原 " + restores + " 次", "Scrambled x" + scrambles + " · restored x" + restores)
    }

    Column(modifier = Modifier.fillMaxSize().background(SuBg)) {
        SuiteFadingTopBar(
            title = t("图片混淆", "圖片混淆", "Image Scramble"),
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
                SuiteTileRow(t("原理", "原理", "How it works"), t("参考 ObfuscationUtils：多种可逆混淆算法，用相同算法与密钥即可无损还原；混淆结果即下一张输入图，可链式连续操作", "參考 ObfuscationUtils：多種可逆混淆算法，用相同算法與密鑰即可無損還原；混淆結果即下一張輸入圖，可鏈式連續操作", "From ObfuscationUtils: reversible scrambles; each result feeds the next operation")) { }
            }
            // ===== 顶部当前状态 =====
            SuiteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SuSegBg),
                        contentAlignment = Alignment.Center
                    ) {
                        val cb = currentBmp
                        if (cb != null) {
                            Image(
                                bitmap = cb.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            BasicText("图", style = TextStyle(color = SuSub, fontSize = 15.sp))
                        }
                    }
                    Spacer(Modifier.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(t("当前状态", "當前狀態", "Current state"), style = TextStyle(color = SuTitle, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                        BasicText(stateText(), style = TextStyle(color = SuSub, fontSize = 13.sp))
                    }
                }
            }
            SuitePickCard(
                title = if (originalBmp == null) t("选择原始图片", "選擇原始圖片", "Pick original image") else t("更换图片", "更換圖片", "Change image"),
                hint = t("点击选择图片（重新选图会重置链式状态）", "點擊選擇圖片（重新選圖會重置鏈式狀態）", "Tap to pick an image (resets the chain)"),
                color = Color(0xFFFF7A00),
                bmp = currentBmp,
                onClick = { pickSrc.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
            SuiteCard {
                SuiteTileRow(t("混淆算法", "混淆算法", "Algorithm"), t("算法与密钥需牢记，还原时保持一致", "算法與密鑰需牢記，還原時保持一致", "Remember the algo & key for restore")) { }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteSegButton(t("Gilbert曲线", "Gilbert曲線", "Gilbert curve"), algo == SuiteEngines.ScrambleAlgo.Gilbert, { algo = SuiteEngines.ScrambleAlgo.Gilbert }, Modifier.weight(1f))
                    SuiteSegButton(t("全局置乱", "全局置亂", "Global shuffle"), algo == SuiteEngines.ScrambleAlgo.Tomato, { algo = SuiteEngines.ScrambleAlgo.Tomato }, Modifier.weight(1f))
                    SuiteSegButton(t("分块", "分塊", "Block"), algo == SuiteEngines.ScrambleAlgo.Block, { algo = SuiteEngines.ScrambleAlgo.Block }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteSegButton(t("行像素", "行像素", "Row pixel"), algo == SuiteEngines.ScrambleAlgo.RowPixel, { algo = SuiteEngines.ScrambleAlgo.RowPixel }, Modifier.weight(1f))
                    SuiteSegButton(t("像素级", "像素級", "Per pixel"), algo == SuiteEngines.ScrambleAlgo.PerPixel, { algo = SuiteEngines.ScrambleAlgo.PerPixel }, Modifier.weight(1f))
                    SuiteSegButton(t("行平移", "行平移", "Row shift"), algo == SuiteEngines.ScrambleAlgo.PicEncryptRow, { algo = SuiteEngines.ScrambleAlgo.PicEncryptRow }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteSegButton(t("行列平移", "行列平移", "Row+col shift"), algo == SuiteEngines.ScrambleAlgo.PicEncryptRowColumn, { algo = SuiteEngines.ScrambleAlgo.PicEncryptRowColumn }, Modifier.weight(1f))
                    SuiteSegButton(t("排序", "排序", "Sort"), algo == SuiteEngines.ScrambleAlgo.Sort, { algo = SuiteEngines.ScrambleAlgo.Sort }, Modifier.weight(1f))
                    SuiteSegButton(t("种子随机", "種子隨機", "Seed random"), algo == SuiteEngines.ScrambleAlgo.Random, { algo = SuiteEngines.ScrambleAlgo.Random }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                BasicText(t("当前：", "當前：", "Now: ") + algoLabel(algo) + " — " + algoHint(algo), style = TextStyle(color = SuSub, fontSize = 12.sp))
            }
            if (algo.needsKey) {
                SuiteCard {
                    SuiteTileRow(t("密钥", "密鑰", "Key"), t("混淆与还原必须使用相同密钥", "混淆與還原必須使用相同密鑰", "Same key needed for restore")) { }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SuSegBg)
                            .border(1.dp, SuBlue.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = key,
                            onValueChange = { if (algo.needsKey || it.isBlank()) key = it },
                            textStyle = TextStyle(color = SuTitle, fontSize = 14.sp),
                            singleLine = true,
                            enabled = algo.needsKey,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (key.isEmpty() && algo.needsKey) {
                        BasicText(t("输入密钥（全局置乱=数字；行平移/行列平移=浮点；其余任意字符串）", "輸入密鑰（全局置亂=數字；行平移/行列平移=浮點；其餘任意字符串）", "Key: Global=number; Row/RowColumn=float; others any string"), style = TextStyle(color = SuSub, fontSize = 12.sp))
                    }
                }
            }
            SuiteMainButton(t("开始混淆", "開始混淆", "Scramble"), enabled = currentBmp != null) { runScramble() }
            Spacer(Modifier.height(0.dp))
            SuiteGhostButton(t("还原（对当前图）", "還原（對當前圖）", "Restore (current)"), SuBlue) { runRestore() }
            if (currentBmp != null) {
                SuiteCard {
                    SuiteTileRow(t("当前图片", "當前圖片", "Current image"), t("点击图片可全屏查看", "點擊圖片可全屏查看", "Tap image to view full screen")) { }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .clickable { previewBmp = currentBmp },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = currentBmp!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        SuiteGhostButton(t("保存到相册", "保存到相冊", "Save"), SuBlue) { requestSave(currentBmp, saveName()) }
                    }
                    Box(Modifier.weight(1f)) {
                        SuiteGhostButton(t("重置", "重置", "Reset"), Color(0xFFE5484D)) {
                            currentBmp = null; originalBmp = null; scrambles = 0; restores = 0; status = ""
                        }
                    }
                }
                BasicText(
                    t("提示：结果保存为 PNG 无损格式（JPEG 会失真导致无法还原）", "提示：結果保存為 PNG 無損格式（JPEG 會失真導致無法還原）", "Note: results are saved as lossless PNG (JPEG corrupts pixels and breaks restore)"),
                    style = TextStyle(color = SuSub, fontSize = 12.sp)
                )
            }
            if (status.isNotEmpty()) {
                BasicText(status, style = TextStyle(color = SuSub, fontSize = 13.sp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    SuiteImageViewer(bmp = previewBmp, onDismiss = { previewBmp = null })
}

/** 混淆算法名三语映射（数据结构的 label 为中文，显示层翻译） */
@Composable
private fun algoLabel(a: SuiteEngines.ScrambleAlgo): String = when (a) {
    SuiteEngines.ScrambleAlgo.Tomato -> t("全局置乱", "全局置亂", "Global shuffle")
    SuiteEngines.ScrambleAlgo.Block -> t("分块", "分塊", "Block")
    SuiteEngines.ScrambleAlgo.RowPixel -> t("行像素", "行像素", "Row pixel")
    SuiteEngines.ScrambleAlgo.PerPixel -> t("像素级", "像素級", "Per pixel")
    SuiteEngines.ScrambleAlgo.PicEncryptRow -> t("行平移", "行平移", "Row shift")
    SuiteEngines.ScrambleAlgo.PicEncryptRowColumn -> t("行列平移", "行列平移", "Row+col shift")
    SuiteEngines.ScrambleAlgo.Sort -> t("排序", "排序", "Sort")
    SuiteEngines.ScrambleAlgo.Random -> t("种子随机", "種子隨機", "Seed random")
    SuiteEngines.ScrambleAlgo.Gilbert -> t("Gilbert曲线", "Gilbert曲線", "Gilbert curve")
}

@Composable
private fun algoHint(a: SuiteEngines.ScrambleAlgo): String = when (a) {
    SuiteEngines.ScrambleAlgo.Tomato -> t("数字密钥，全局像素置乱", "數字密鑰，全域像素置亂", "Numeric key; global pixel shuffle")
    SuiteEngines.ScrambleAlgo.Block -> t("8×8 分块，块内按密钥打乱", "8×8 分塊，塊內按密鑰打亂", "8×8 blocks shuffled by key")
    SuiteEngines.ScrambleAlgo.RowPixel -> t("逐行像素按密钥打乱", "逐行像素按密鑰打亂", "Row-wise pixels shuffled by key")
    SuiteEngines.ScrambleAlgo.PerPixel -> t("逐像素通道交换 + 异或", "逐像素通道交換 + 異或", "Per-pixel channel swap + XOR")
    SuiteEngines.ScrambleAlgo.PicEncryptRow -> t("浮点密钥，按行循环平移", "浮點密鑰，按行循環平移", "Float key; cyclic row shift")
    SuiteEngines.ScrambleAlgo.PicEncryptRowColumn -> t("浮点密钥，行列双重平移", "浮點密鑰，行列雙重平移", "Float key; row+col dual shift")
    SuiteEngines.ScrambleAlgo.Sort -> t("按亮度排序，无需密钥", "按亮度排序，無需密鑰", "Sort by luminance; no key")
    SuiteEngines.ScrambleAlgo.Random -> t("种子随机置乱", "種子隨機置亂", "Seed-based random shuffle")
    SuiteEngines.ScrambleAlgo.Gilbert -> t("Gilbert 曲线重排，固定偏移，无需密钥", "Gilbert 曲線重排，固定偏移，無需密鑰", "Gilbert curve reorder, fixed offset, no key")
}
