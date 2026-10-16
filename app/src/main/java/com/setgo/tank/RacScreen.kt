package com.setgo.tank

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.setgo.tank.core.TuyinCore
import com.setgo.tank.core.TuyinImages
import com.setgo.tank.core.TuyinRuler
import com.setgo.tank.core.UnifiedHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import java.io.ByteArrayOutputStream

private val RacBg get() = SuBg
private val RacTitle get() = SuTitle
private val RacSub get() = SuSub
private val RacBlue get() = suiteAccent
/** accent 作前景文字/图标的安全色：accent 过浅（如瓷白）时退化深色，保证可读 */
private val RacBlueFg get() = SuAccentFg
private val RacRed = Color(0xFFE5484D)
private val RacGreen = Color(0xFF00A854)
private val RacSegBg get() = SuSegBg

// 封面增强档位（对齐 web coverUpscale）
private val ENHANCE_VALUES = listOf(0, 2048, 2560, 3072, 4096, -1)
// 通道模拟档位（对齐 web simScale / simQuality）
private val SCALE_VALUES = listOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5)
private val QUALITY_VALUES = listOf(100, 90, 80, 70, 60, 50, 30)

@Composable
private fun enhanceLabelList(): List<String> = listOf(
    t("不放大", "不放大", "No upscale"),
    t("长边缩放到 2048", "長邊縮放到 2048", "Long edge 2048"),
    t("长边缩放到 2560", "長邊縮放到 2560", "Long edge 2560"),
    t("长边缩放到 3072", "長邊縮放到 3072", "Long edge 3072"),
    t("长边缩放到 4096", "長邊縮放到 4096", "Long edge 4096"),
    t("自定义", "自訂", "Custom")
)

@Composable
private fun scaleLabelList(): List<String> = listOf(
    t("不缩放（100%）", "不縮放（100%）", "No scale (100%)"),
    t("缩放 90%", "縮放 90%", "Scale 90%"),
    t("缩放 80%", "縮放 80%", "Scale 80%"),
    t("缩放 70%", "縮放 70%", "Scale 70%"),
    t("缩放 60%", "縮放 60%", "Scale 60%"),
    t("缩放 50%", "縮放 50%", "Scale 50%")
)

@Composable
private fun qualityLabelList(): List<String> = listOf(
    t("q100（无损）", "q100（無損）", "q100 (lossless)"),
    t("q90", "q90", "q90"),
    t("q80", "q80", "q80"),
    t("q70", "q70", "q70"),
    t("q60", "q60", "q60"),
    t("q50（很狠）", "q50（很狠）", "q50 (harsh)"),
    t("q30", "q30", "q30")
)

/** 封面增强放大护栏：目标总像素上限（≈4096×5850，避免超大位图 OOM）。 */
private const val MAX_TARGET_PIXELS = 24_000_000L

/** RAC 图隐二级页：嵌入 / 提取（功能与旧版 App 对齐，一个不差） */
@Composable
fun RacScreen(onBack: () -> Unit, loadPath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val enhanceLabels = enhanceLabelList()

    var mode by remember { mutableStateOf(true) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var secretUri by remember { mutableStateOf<Uri?>(null) }
    var stegoUri by remember { mutableStateOf<Uri?>(null) }
    var coverData by remember { mutableStateOf<TuyinCore.ImageData?>(null) }
    var secretData by remember { mutableStateOf<TuyinCore.ImageData?>(null) }
    var stegoData by remember { mutableStateOf<TuyinCore.ImageData?>(null) }
    var coverBmp by remember { mutableStateOf<Bitmap?>(null) }
    var secretBmp by remember { mutableStateOf<Bitmap?>(null) }
    var stegoBmp by remember { mutableStateOf<Bitmap?>(null) }
    var payloadBytes by remember { mutableStateOf(0) }
    var enhanceIndex by remember { mutableStateOf(0) }
    var enhancePrevIndex by remember { mutableStateOf(0) }
    var enhanceCustom by remember { mutableStateOf(0) }
    var scaleIndex by remember { mutableStateOf(0) }
    var qualityIndex by remember { mutableStateOf(5) }
    var tier by remember { mutableStateOf(50) }
    var showCustom by remember { mutableStateOf(false) }
    var ppb by remember { mutableStateOf(2) }
    var repeat by remember { mutableStateOf(1) }
    var nsym by remember { mutableStateOf(48) }
    var embedWorking by remember { mutableStateOf(false) }
    var embedProgress by remember { mutableStateOf(0f) }
    var embedStatus by remember { mutableStateOf("") }
    var extractWorking by remember { mutableStateOf(false) }
    var extractStatus by remember { mutableStateOf("") }
    var simWorking by remember { mutableStateOf(false) }
    var simStatus by remember { mutableStateOf("") }
    var manualW by remember { mutableStateOf("") }
    var manualH by remember { mutableStateOf("") }
    var simAttackedBmp by remember { mutableStateOf<Bitmap?>(null) }
    var simRecoveredBmp by remember { mutableStateOf<Bitmap?>(null) }
    var stegoResultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var extractResultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var customDialog by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }

    // 历史记录跳转加载：把历史缩略图作为封面图载入（可继续嵌入）
    LaunchedEffect(loadPath) {
        val p = loadPath
        if (p != null) {
            try {
                val bmp = withContext(Dispatchers.Default) { TuyinImages.decodeFileScaled(p, 2048) }
                if (bmp != null) {
                    mode = true
                    coverData = TuyinImages.bitmapToImageData(bmp)
                    coverBmp = bmp
                    payloadBytes = 0
                    embedStatus = t("已加载历史图片作为封面", "已載入歷史圖片作為封面", "History image loaded as cover")
                } else {
                    embedStatus = t("历史图片读取失败", "歷史圖片讀取失敗", "Failed to load history image")
                }
            } catch (e: Exception) { embedStatus = t("读取图片失败：", "讀取圖片失敗：", "Failed to read image: ") + friendlyError(e) }
        }
    }

    fun currentOptions(): TuyinCore.Options {
        val o = TuyinCore.Options()
        val t = sliderToTier(tier)
        o.ppb = t[0]; o.repeat = t[1]; o.nsym = t[2]
        if (showCustom) { o.ppb = ppb; o.repeat = repeat; o.nsym = nsym }
        o.marginMin = 40; o.marginGain = 1.4; o.marginMax = 200
        o.secretMax = TuyinImages.SECRET_MAX
        return o
    }

    fun enhanceTargetLong(): Int {
        val v = ENHANCE_VALUES[enhanceIndex]
        return if (v == -1) enhanceCustom else v
    }

    fun enhanceDecodeLong(): Int {
        val t = enhanceTargetLong()
        return if (t > 0) t else TuyinImages.MAX_LONG
    }

    fun enhanceCover(raw: TuyinCore.ImageData): TuyinCore.ImageData {
        val target = enhanceTargetLong()
        if (target <= 0) return raw
        val scale = target.toDouble() / maxOf(raw.width, raw.height)
        var nw = maxOf(1, Math.round(raw.width * scale).toInt())
        var nh = maxOf(1, Math.round(raw.height * scale).toInt())
        val px = nw.toLong() * nh
        if (px > MAX_TARGET_PIXELS) {
            val s = Math.sqrt(MAX_TARGET_PIXELS.toDouble() / px)
            nw = maxOf(1, (nw * s).toInt())
            nh = maxOf(1, (nh * s).toInt())
        }
        if (nw == raw.width && nh == raw.height) return raw
        return TuyinImages.resizeImageData(raw, nw, nh)
    }

    fun loadCover(uri: Uri) {
        scope.launch {
            try {
                embedStatus = t("正在处理封面…", "正在處理封面…", "Processing cover…")
                val data = withContext(Dispatchers.Default) {
                    val raw = TuyinImages.decodeUri(context, uri, enhanceDecodeLong())
                    enhanceCover(raw)
                }
                coverData = data
                coverBmp = TuyinImages.imageDataToBitmap(data)
                payloadBytes = 0
                embedStatus = ""
            } catch (e: Exception) {
                embedStatus = t("处理封面失败：", "處理封面失敗：", "Cover failed: ") + friendlyError(e)
            }
        }
    }

    fun reapplyCover() {
        val uri = coverUri ?: return
        scope.launch {
            try {
                embedStatus = t("正在重新处理封面…", "正在重新處理封面…", "Re-processing cover…")
                val data = withContext(Dispatchers.Default) {
                    val raw = TuyinImages.decodeUri(context, uri, enhanceDecodeLong())
                    enhanceCover(raw)
                }
                coverData = data
                coverBmp = TuyinImages.imageDataToBitmap(data)
                payloadBytes = 0
                embedStatus = ""
            } catch (e: Exception) {
                embedStatus = t("重新处理封面失败：", "重新處理封面失敗：", "Re-cover failed: ") + friendlyError(e)
            }
        }
    }

    fun doEmbed() {
        val cover = coverData ?: run { embedStatus = t("请先选择封面图和秘密图", "請先選擇封面圖和祕密圖", "Pick a cover & secret first"); return }
        val secret = secretData ?: run { embedStatus = t("请先选择封面图和秘密图", "請先選擇封面圖和祕密圖", "Pick a cover & secret first"); return }
        val opts = currentOptions()
        val cap = TuyinCore.capacityBytes(cover.width, cover.height, opts)
        if (cap < 64) { embedStatus = t("封面太小，无法嵌入", "封面太小，無法嵌入", "Cover too small to embed"); return }
        embedWorking = true
        embedStatus = t("正在嵌入…", "正在嵌入…", "Embedding…")
        embedProgress = 0f
        scope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    val jpeg = TuyinImages.fitSecretJpeg(secret, cap, opts.secretMax)
                        ?: throw EmbedTooBigException()
                    TuyinCore.buildPayload(jpeg, opts)
                }
                val stego = withContext(Dispatchers.Default) {
                    TuyinCore.embed(cover, payload, opts) { f -> embedProgress = f }
                }
                payloadBytes = payload.size
                stegoData = stego
                stegoBmp = TuyinImages.imageDataToBitmap(stego)
                stegoResultBmp = stegoBmp
                UnifiedHistory.add(context, "rac", "encode", stegoBmp)
                embedProgress = 1f
                val w = stego.width; val h = stego.height
                embedStatus = t("嵌入完成 ", "嵌入完成 ", "Embedded ") + "${w}×${h}" +
                    t("，可保存隐写图、切“提取”验证或下滑“通道模拟”验证鲁棒性", "，可保存隱寫圖、切「提取」驗證或下滑「通道模擬」驗證魯棒性", ". Save the stego, verify via Extract, or scroll to Channel Sim")
            } catch (e: EmbedTooBigException) {
                embedStatus = t("秘密图太大，塞不进这张封面（可把画质档位往左调）", "祕密圖太大，塞不進這張封面（可把畫質檔位往左調）", "Secret too big for this cover (try a lower quality tier)")
            } catch (e: Exception) {
                embedStatus = t("嵌入失败：", "嵌入失敗：", "Embed failed: ") + friendlyError(e)
            } finally {
                embedWorking = false
            }
        }
    }

    fun doExtract() {
        val data = stegoData ?: run { extractStatus = t("请先选择隐写图", "請先選擇隱寫圖", "Pick a stego image first"); return }
        val w = manualW.trim().toIntOrNull() ?: 0
        val h = manualH.trim().toIntOrNull() ?: 0
        if ((w >= 16) != (h >= 16)) { extractStatus = t("宽和高需同时填写，或都留空自动检测封面尺寸", "寬和高需同時填寫，或都留空自動偵測封面尺寸", "Enter width & height together, or leave both empty for auto"); return }
        val manual = w >= 16 && h >= 16
        extractWorking = true
        extractStatus = if (manual) t("正在提取（按 ", "正在提取（按 ", "Extracting (") + "${w}×${h}" + t(" 恢复）…", " 恢復）…", "）…") else t("正在提取（自动检测封面尺寸）…", "正在提取（自動偵測封面尺寸）…", "Extracting (auto-detect)…")
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    if (manual) {
                        val working = TuyinImages.resizeImageData(data, w, h)
                        TuyinCore.extract(working)
                    } else {
                        extractAuto(data)
                    }
                }
                if (res == null) {
                    extractStatus = t("未找到隐藏图片：图片可能不是图隐产物，或已被大幅修改", "未找到隱藏圖片：圖片可能不是圖隱產物，或已被大幅修改", "No hidden image found: not a stego, or heavily modified")
                    return@launch
                }
                val bmp = withContext(Dispatchers.Default) {
                    val raw = BitmapFactory.decodeByteArray(res.jpeg, 0, res.jpeg.size)
                    TuyinImages.applyExifRotationBytes(res.jpeg, raw)
                }
                extractResultBmp = bmp
                extractStatus = t("提取成功 ", "提取成功 ", "Extracted ") + "${bmp.width}×${bmp.height}" +
                        (if (manual) t("（已按 ", "（已按 ", " (restored ") + "${w}×${h}" + t(" 恢复）", " 恢復）", ")") else t("（已自动恢复封面尺寸）", "（已自動恢復封面尺寸）", " (auto-restored cover size)")) +
                        t("，可保存到相册", "，可保存到相冊", "; save to gallery")
            } catch (e: Exception) {
                extractStatus = t("提取失败：", "提取失敗：", "Extract failed: ") + friendlyError(e)
            } finally {
                extractWorking = false
            }
        }
    }

    fun doSimulate() {
        val data = stegoData ?: run { simStatus = t("请先在“嵌入”页生成隐写图，或在“提取”页选择隐写图", "請先在「嵌入」頁生成隱寫圖，或在「提取」頁選擇隱寫圖", "Generate a stego in Embed, or pick one in Extract first"); return }
        simWorking = true
        simStatus = t("模拟通道：缩放 → JPEG 重压缩 → 提取…", "模擬通道：縮放 → JPEG 重壓縮 → 提取…", "Simulating: scale → JPEG recompress → extract…")
        scope.launch {
            try {
                val (attacked, res) = withContext(Dispatchers.Default) {
                    var img = data
                    val scale = SCALE_VALUES[scaleIndex]
                    if (scale < 1.0) {
                        img = TuyinImages.resizeImageData(img,
                            maxOf(8, Math.round(img.width * scale).toInt()),
                            maxOf(8, Math.round(img.height * scale).toInt()))
                    }
                    val q = QUALITY_VALUES[qualityIndex]
                    if (q < 100) {
                        val b = TuyinImages.imageDataToBitmap(img)
                        val baos = ByteArrayOutputStream()
                        b.compress(Bitmap.CompressFormat.JPEG, q, baos)
                        val jpeg = baos.toByteArray()
                        val dec = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        img = TuyinImages.bitmapToImageData(dec)
                        b.recycle(); dec.recycle()
                    }
                    img to extractAuto(img)
                }
                simAttackedBmp = TuyinImages.imageDataToBitmap(attacked)
                if (res != null) {
                    val rec = withContext(Dispatchers.Default) {
                        val raw = BitmapFactory.decodeByteArray(res.jpeg, 0, res.jpeg.size)
                        TuyinImages.applyExifRotationBytes(res.jpeg, raw)
                    }
                    simRecoveredBmp = rec
                    simStatus = t("提取成功：秘密图完好恢复，扛住了这个通道。", "提取成功：祕密圖完好恢復，扛住了這個通道。", "Recovered: the secret survived this channel.")
                } else {
                    simRecoveredBmp = null
                    simStatus = t("提取失败：载荷已超出纠错预算。试试更轻的通道或更稳健的工作点。", "提取失敗：載荷已超出糾錯預算。試試更輕的通道或更穩健的工作點。", "Recovery failed: payload exceeded ECC budget. Try a lighter channel.")
                }
            } catch (e: Exception) {
                simStatus = t("模拟失败：", "模擬失敗：", "Sim failed: ") + friendlyError(e)
            } finally {
                simWorking = false
            }
        }
    }

    fun resetEmbed() {
        coverUri = null; coverData = null; coverBmp = null
        secretUri = null; secretData = null; secretBmp = null
        stegoData = null; stegoBmp = null
        stegoResultBmp = null
        simAttackedBmp = null; simRecoveredBmp = null
        payloadBytes = 0
        embedStatus = ""; simStatus = ""
    }

    fun resetExtract() {
        stegoUri = null; stegoData = null; stegoBmp = null
        extractResultBmp = null
        manualW = ""; manualH = ""
        extractStatus = ""
    }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { coverUri = uri; loadCover(uri) }
    }
    val pickSecret = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            secretUri = uri
            scope.launch {
                try {
                    secretData = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, TuyinImages.SECRET_MAX) }
                    secretBmp = TuyinImages.imageDataToBitmap(secretData!!)
                } catch (e: Exception) {
                    embedStatus = t("读取秘密图失败：", "讀取祕密圖失敗：", "Failed to read secret: ") + friendlyError(e)
                }
            }
        }
    }
    val pickStego = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            stegoUri = uri
            scope.launch {
                try {
                    stegoData = withContext(Dispatchers.Default) { TuyinImages.decodeUri(context, uri, 0) }
                    stegoBmp = TuyinImages.imageDataToBitmap(stegoData!!)
                } catch (e: Exception) {
                    extractStatus = t("读取隐写图失败：", "讀取隱寫圖失敗：", "Failed to read stego: ") + friendlyError(e)
                }
            }
        }
    }

    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingSave?.invoke()
        }
    }
    fun requestSave(bmp: Bitmap?, name: String, onResult: (Boolean) -> Unit) {
        if (bmp == null) { embedStatus = t("还没有可保存的图片", "還沒有可保存的圖片", "Nothing to save yet"); return }
        val doSave: () -> Unit = { scope.launch {
            val ok = withContext(Dispatchers.Default) { TuyinImages.saveBitmapToGallery(context, bmp, name, "image/png", 100) }
            if (ok) UnifiedHistory.add(context, "rac", "save", bmp)
            onResult(ok)
        } }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = doSave
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave()
    }

    if (customDialog) {
        Dialog(onDismissRequest = { customDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(20.dp)
            ) {
                BasicText(t("自定义封面长边", "自訂封面長邊", "Custom cover long edge"), style = TextStyle(color = RacTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(16.dp))
                NumberField(value = customInput, onValue = { customInput = it }, hint = "64 ~ 8192", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    BasicText(t("取消", "取消", "Cancel"), style = TextStyle(color = RacSub, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier
                        .clickable { customDialog = false; enhanceIndex = enhancePrevIndex }.padding(12.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicText(t("确定", "確定", "OK"), style = TextStyle(color = RacBlueFg, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier
                        .clickable {
                            val v = customInput.trim().toIntOrNull()
                            if (v != null && v in 64..8192) {
                                enhanceCustom = v
                                enhancePrevIndex = enhanceIndex
                                enhanceIndex = enhanceLabels.size - 1
                                if (coverUri != null) reapplyCover()
                            } else {
                                embedStatus = t("请输入 64~8192 的数字", "請輸入 64~8192 的數字", "Enter a number from 64 to 8192")
                            }
                            customDialog = false
                        }
                        .padding(12.dp))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RacBg)
    ) {
        SuiteFadingTopBar(
            title = t("RAC 图隐", "RAC 圖隱", "RAC Stegano"),
            scrollOffset = scrollState.value.toFloat(),
            onBack = onBack
        )
        ModeSwitch(mode = mode, onMode = { mode = it })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            val capacity = remember(coverData, tier, showCustom, ppb, repeat, nsym) {
                val cover = coverData
                if (cover != null) {
                    val o = TuyinCore.Options()
                    val t = sliderToTier(tier)
                    o.ppb = t[0]; o.repeat = t[1]; o.nsym = t[2]
                    if (showCustom) { o.ppb = ppb; o.repeat = repeat; o.nsym = nsym }
                    o.marginMin = 40; o.marginGain = 1.4; o.marginMax = 200
                    o.secretMax = TuyinImages.SECRET_MAX
                    TuyinCore.capacityBytes(cover.width, cover.height, o)
                } else 0
            }
            if (mode) {
                RacEmbedPanel(
                    coverUri = coverUri, coverBmp = coverBmp,
                    secretUri = secretUri, secretBmp = secretBmp,
                    capacity = capacity,
                    payloadBytes = payloadBytes,
                    enhanceIndex = enhanceIndex,
                    tier = tier, tierName = tierName(tier),
                    showCustom = showCustom, ppb = ppb, repeat = repeat, nsym = nsym,
                    embedWorking = embedWorking, embedProgress = embedProgress, embedStatus = embedStatus,
                    stegoResultBmp = stegoResultBmp,
                    scaleIndex = scaleIndex, qualityIndex = qualityIndex,
                    simWorking = simWorking, simStatus = simStatus,
                    simAttackedBmp = simAttackedBmp, simRecoveredBmp = simRecoveredBmp,
                    onPickCover = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickSecret = { pickSecret.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onEnhanceChange = { idx ->
                        val prev = enhanceIndex
                        enhanceIndex = idx
                        if (idx == enhanceLabels.size - 1) {
                            enhancePrevIndex = prev
                            customInput = if (enhanceCustom > 0) enhanceCustom.toString() else ""
                            customDialog = true
                        } else {
                            enhancePrevIndex = idx
                            if (coverUri != null) reapplyCover()
                        }
                    },
                    onTierChange = { tier = it },
                    onToggleCustom = { showCustom = !showCustom },
                    onPpbChange = { ppb = it }, onRepeatChange = { repeat = it }, onNsymChange = { nsym = it },
                    onEmbed = { doEmbed() },
                    onSaveStego = { requestSave(stegoResultBmp ?: stegoBmp, "tuyin-stego") { ok -> embedStatus = if (ok) t("已保存到相册", "已保存到相冊", "Saved to gallery") else t("保存失败", "保存失敗", "Save failed") } },
                    onResetEmbed = { resetEmbed() },
                    onScaleChange = { scaleIndex = it }, onQualityChange = { qualityIndex = it },
                    onSimulate = { doSimulate() },
                    onSaveSim = { requestSave(simRecoveredBmp, "tuyin-recovered") { ok -> simStatus = if (ok) t("已保存到相册", "已保存到相冊", "Saved to gallery") else t("保存失败", "保存失敗", "Save failed") } }
                )
            } else {
                RacExtractPanel(
                    stegoUri = stegoUri, stegoBmp = stegoBmp,
                    manualW = manualW, manualH = manualH,
                    extractWorking = extractWorking, extractStatus = extractStatus,
                    extractResultBmp = extractResultBmp,
                    onPickStego = { pickStego.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onManualW = { manualW = it }, onManualH = { manualH = it },
                    onExtract = { doExtract() },
                    onSaveExtracted = { requestSave(extractResultBmp, "tuyin-extracted") { ok -> extractStatus = if (ok) t("已保存到相册", "已保存到相冊", "Saved to gallery") else t("保存失败", "保存失敗", "Save failed") } },
                    onResetExtract = { resetExtract() }
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

private class EmbedTooBigException : Exception()

private fun friendlyError(e: Throwable): String {
    if (e is OutOfMemoryError) return t("内存不足，请降低封面增强尺寸或画质档位", "記憶體不足，請降低封面增強尺寸或畫質檔位", "Out of memory — lower the cover upscale or quality")
    return e.message ?: e.toString()
}

private fun fmtKB(bytes: Int): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "<1 KB"
    return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
}

/** 对齐 GUI sliderToTier。 */
private fun sliderToTier(value: Int): IntArray {
    if (value <= 50) {
        val t = value / 50.0
        return intArrayOf(Math.round(6 - t * 4).toInt(), 1, Math.round(32 + t * 16).toInt())
    }
    val t = (value - 50) / 50.0
    return intArrayOf(Math.round(2 - t).toInt(), if (t < 0.5) 1 else 3, 48)
}

private fun tierName(progress: Int): String {
    val t = sliderToTier(progress)
    return when {
        t[0] >= 5 -> t("容量优先", "容量優先", "Capacity-first")
        t[0] <= 1 && t[1] >= 3 -> t("抗压缩", "抗壓縮", "Compression-proof")
        t[0] <= 2 && t[2] >= 48 -> t("均衡", "均衡", "Balanced")
        else -> t("自定义 ", "自訂 ", "Custom ") + "${t[0]}/${t[1]}/${t[2]}"
    }
}

/** 自动检测封面标尺并提取（对齐 web extractImage 无 manual 分支）。 */
private fun extractAuto(stego: TuyinCore.ImageData): TuyinCore.ExtractResult? {
    var working = stego
    val rgb = toRgb(stego.rgba)
    val detected = TuyinRuler.detectRulerSize(rgb, stego.width, stego.height)
    if (detected != null && (detected[0] != stego.width || detected[1] != stego.height)) {
        working = TuyinImages.resizeImageData(stego, detected[0], detected[1])
    }
    return TuyinCore.extract(working)
}

private fun toRgb(rgba: ByteArray): ByteArray {
    val rgb = ByteArray(rgba.size / 4 * 3)
    for (i in 0 until rgba.size / 4) {
        rgb[i * 3] = rgba[i * 4]
        rgb[i * 3 + 1] = rgba[i * 4 + 1]
        rgb[i * 3 + 2] = rgba[i * 4 + 2]
    }
    return rgb
}

/* ================= UI 组件 ================= */

@Composable
private fun ModeSwitch(mode: Boolean, onMode: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(RacSegBg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeTab(t("嵌入", "嵌入", "Embed"), selected = mode, onClick = { onMode(true) })
        ModeTab(t("提取", "提取", "Extract"), selected = !mode, onClick = { onMode(false) })
    }
}

@Composable
private fun RowScope.ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(
            color = if (selected) RacBlueFg else RacSub,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        ))
    }
}

/** 标题 + 双行文字行（去掉单字方框头像，标题顶格）。 */
@Composable
private fun TileRow(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = RacTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            BasicText(subtitle, style = TextStyle(color = RacSub, fontSize = 12.sp))
        }
        trailing?.invoke()
    }
}

/** 信息卡容器（对齐旧版 makeCard）。 */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardColors(SuCardTint, RacTitle)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 14.dp)) { content() }
    }
}

/** 主按钮：蓝底胶囊。 */
@Composable
private fun MainButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(if (enabled) RacBlue else Color(0xFFC9C9CE))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextStyle(color = RacBlue.onAccentFg(), fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

/** 次按钮：白底 + 彩色描边。 */
@Composable
private fun GhostButton(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextStyle(color = if (accent.isLight()) RacTitle else accent, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

/** 图片选择卡：未选时 16:9 长条占位，选图后卡片高度跟随图片宽高比（对齐旧版 fitImage）。 */
@Composable
private fun ImagePickCard(
    title: String, hint: String, color: Color,
    bmp: Bitmap?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardColors(SuCardTint, RacTitle)
    ) {
        if (bmp == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(color, RoundedCornerShape(28.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("＋", style = TextStyle(color = color.onAccentFg(), fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.height(12.dp))
                    BasicText(title, style = TextStyle(color = RacTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(6.dp))
                    BasicText(hint, style = TextStyle(color = RacSub, fontSize = 12.sp))
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val ratio = bmp.height.toFloat() / bmp.width.toFloat()
                val imgH = maxWidth * ratio
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imgH)
                        .padding(10.dp, 8.dp, 10.dp, 10.dp)
                )
            }
        }
    }
}

/** 结果展示卡：标题 + 图片区（高度跟随图片比例）。 */
@Composable
private fun ResultImageCard(title: String, bmp: Bitmap?, emptyHint: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardColors(SuCardTint, RacTitle)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 14.dp)) {
            BasicText(title, style = TextStyle(color = RacTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            if (bmp == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(emptyHint, style = TextStyle(color = RacSub, fontSize = 13.sp))
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val ratio = bmp.height.toFloat() / bmp.width.toFloat()
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(maxWidth * ratio)
                    )
                }
            }
        }
    }
}

/** 滑条行：左标签 + 右数值 + 滑条（对齐旧版 addParamSlider / tierSlider）。 */
@Composable
private fun ParamSliderRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit, leftIndent: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (leftIndent) Modifier.padding(start = 58.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, style = TextStyle(color = RacSub, fontSize = 12.sp), modifier = Modifier.weight(1f))
        BasicText("$value", style = TextStyle(color = RacTitle, fontSize = 12.sp))
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = min.toFloat()..max.toFloat(),
        colors = SliderDefaults.sliderColors(
            foregroundColor = RacBlue,
            backgroundColor = Color(0xFFE2E2E7),
            thumbColor = Color.White
        ),
        height = 20.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (leftIndent) Modifier.padding(start = 58.dp) else Modifier)
    )
}

/** 下拉选择字段：点击弹单选对话框。 */
@Composable
private fun SelectField(
    labels: List<String>,
    selectedIndex: Int,
    accent: Color = RacBlue,
    widthDp: Int = 150,
    onChange: (Int) -> Unit
) {
    var dialog by remember { mutableStateOf(false) }
    val fieldFg = if (accent.isLight()) RacTitle else accent
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF1F1F3))
            .clickable { dialog = true }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(labels.getOrElse(selectedIndex) { "" },
            style = TextStyle(color = fieldFg, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
    if (dialog) {
        Dialog(onDismissRequest = { dialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(20.dp)
            ) {
                BasicText(t("请选择", "請選擇", "Select"), style = TextStyle(color = RacTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                labels.forEachIndexed { i, label ->
                    val sel = i == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(i); dialog = false }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (sel) RacBlue else Color(0xFFE8E8EC))
                                .padding(3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(RacBlue.onAccentFg()))
                        }
                        Spacer(Modifier.width(12.dp))
                        BasicText(label, style = TextStyle(
                            color = if (sel) RacBlueFg else RacTitle,
                            fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                        ))
                    }
                }
            }
        }
    }
}

/** 数字输入框。 */
@Composable
private fun NumberField(value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.filter { c -> c.isDigit() }) },
        textStyle = TextStyle(color = RacTitle, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        singleLine = true,
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty()) BasicText(hint, style = TextStyle(color = RacSub, fontSize = 13.sp))
                inner()
            }
        }
    )
}

/* ================= 嵌入面板 ================= */

@Composable
private fun RacEmbedPanel(
    coverUri: Uri?, coverBmp: Bitmap?,
    secretUri: Uri?, secretBmp: Bitmap?,
    capacity: Int, payloadBytes: Int,
    enhanceIndex: Int,
    tier: Int, tierName: String,
    showCustom: Boolean, ppb: Int, repeat: Int, nsym: Int,
    embedWorking: Boolean, embedProgress: Float, embedStatus: String,
    stegoResultBmp: Bitmap?,
    scaleIndex: Int, qualityIndex: Int,
    simWorking: Boolean, simStatus: String,
    simAttackedBmp: Bitmap?, simRecoveredBmp: Bitmap?,
    onPickCover: () -> Unit, onPickSecret: () -> Unit,
    onEnhanceChange: (Int) -> Unit,
    onTierChange: (Int) -> Unit,
    onToggleCustom: () -> Unit,
    onPpbChange: (Int) -> Unit, onRepeatChange: (Int) -> Unit, onNsymChange: (Int) -> Unit,
    onEmbed: () -> Unit,
    onSaveStego: () -> Unit, onResetEmbed: () -> Unit,
    onScaleChange: (Int) -> Unit, onQualityChange: (Int) -> Unit,
    onSimulate: () -> Unit, onSaveSim: () -> Unit
) {
    Spacer(Modifier.height(10.dp))
    ImagePickCard(t("封面图 · 宿主", "封面圖 · 宿主", "Cover · host"), t("点击选择要藏秘密图的封面", "點擊選擇要藏祕密圖的封面", "Tap to pick the cover image"), RacBlue, coverBmp, onPickCover)
    Spacer(Modifier.height(12.dp))
    ImagePickCard(t("秘密图 · 被隐藏", "祕密圖 · 被隱藏", "Secret · hidden"), t("点击选择要藏进封面的图片", "點擊選擇要藏進封面的圖片", "Tap to pick the secret image"), Color(0xFF9C5BFF), secretBmp, onPickSecret)

    Spacer(Modifier.height(12.dp))
    InfoCard {
        TileRow(t("可用容量", "可用容量", "Capacity"), t("封面可隐藏的最大字节数，随参数实时更新", "封面可隱藏的最大位元組數，隨參數即時更新", "Max bytes the cover can hide; updates with params")) {
            BasicText("≈ ${fmtKB(capacity)}", style = TextStyle(color = RacBlueFg, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(12.dp))
        val occupied = if (payloadBytes > 0 && capacity > 0) {
            (payloadBytes * 100f / capacity).coerceAtMost(100f)
        } else 0f
        LinearProgressIndicator(
            progress = { occupied / 100f },
            modifier = Modifier.fillMaxWidth().padding(start = 58.dp),
            color = RacBlue,
            trackColor = Color(0xFFE2E2E7),
            drawStopIndicator = {}
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (payloadBytes > 0 && capacity > 0) t("实际载荷：", "實際載荷：", "Payload: ") + "${fmtKB(payloadBytes)} · " + t("占用 ", "佔用 ", "uses ") + "${occupied.toInt()}%"
            else t("实际载荷：尚未嵌入", "實際載荷：尚未嵌入", "Payload: none yet"),
            style = TextStyle(color = RacSub, fontSize = 12.sp),
            modifier = Modifier.padding(start = 58.dp)
        )
    }

    Spacer(Modifier.height(12.dp))
    InfoCard {
        TileRow(t("封面增强", "封面增強", "Cover upscale"), t("把封面长边重采样到目标尺寸，放大可提升隐藏图清晰度", "把封面長邊重採樣到目標尺寸，放大可提升隱藏圖清晰度", "Resample the cover's long edge; upscaling sharpens the hidden image"))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(t("增强", "增強", "Upscale"), style = TextStyle(color = RacSub, fontSize = 13.sp), modifier = Modifier.weight(1f))
            SelectField(enhanceLabelList(), enhanceIndex, onChange = onEnhanceChange)
        }
    }

    Spacer(Modifier.height(12.dp))
    InfoCard {
        TileRow(t("画质与容量", "畫質與容量", "Quality vs capacity"), t("画质与可嵌入容量的平衡，滑动即时重算容量", "畫質與可嵌入容量的平衡，滑動即時重算容量", "Trade-off slider; capacity recalculates live")) {
            BasicText(tierName, style = TextStyle(color = RacTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
        Slider(
            value = tier.toFloat(),
            onValueChange = { onTierChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.sliderColors(
                foregroundColor = RacBlue,
                backgroundColor = Color(0xFFE2E2E7),
                thumbColor = Color.White
            ),
            height = 20.dp,
            modifier = Modifier.fillMaxWidth().padding(start = 58.dp)
        )
    }

    Spacer(Modifier.height(12.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(onClick = onToggleCustom)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(if (showCustom) t("收起参数", "收起參數", "Hide params") else t("自定义参数", "自訂參數", "Custom params"),
            style = TextStyle(color = RacBlueFg, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
    if (showCustom) {
        Spacer(Modifier.height(4.dp))
        InfoCard {
            ParamSliderRow(t("每块系数对 ppb", "每塊係數對 ppb", "Coeff pairs ppb"), ppb, 1, 12, onPpbChange)
            Spacer(Modifier.height(4.dp))
            ParamSliderRow(t("重复 repeat", "重複 repeat", "Repeat"), repeat, 1, 5, onRepeatChange)
            Spacer(Modifier.height(4.dp))
            ParamSliderRow(t("校验 nsym", "校驗 nsym", "Checksum nsym"), nsym, 8, 64, onNsymChange)
        }
    }

    Spacer(Modifier.height(16.dp))
    MainButton(t("开始嵌入", "開始嵌入", "Embed"), enabled = coverBmp != null && secretBmp != null && !embedWorking, onClick = onEmbed)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) { GhostButton(t("保存到相册", "保存到相冊", "Save"), RacBlue, onSaveStego) }
        Box(Modifier.weight(1f)) { GhostButton(t("重置", "重置", "Reset"), RacSub, onResetEmbed) }
    }
    if (embedWorking) {
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { embedProgress },
            modifier = Modifier.fillMaxWidth(),
            color = RacBlue,
            trackColor = Color(0xFFE2E2E7),
            drawStopIndicator = {}
        )
        Spacer(Modifier.height(6.dp))
        BasicText("${(embedProgress * 100).toInt()}%", style = TextStyle(color = RacSub, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth())
    }
    if (embedStatus.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        val embedErrPrefixes = listOf(
            t("嵌入失败", "嵌入失敗", "Embed failed"),
            t("秘密图太大", "祕密圖太大", "Secret too big"),
            t("封面太小", "封面太小", "Cover too small"),
            t("处理封面失败", "處理封面失敗", "Cover failed"),
            t("重新处理封面失败", "重新處理封面失敗", "Re-cover failed"),
            t("读取", "讀取", "Failed to read"),
            t("保存失败", "保存失敗", "Save failed")
        )
        BasicText(embedStatus, style = TextStyle(
            color = if (embedErrPrefixes.any { embedStatus.startsWith(it) }) RacRed else RacBlueFg,
            fontSize = 12.sp), modifier = Modifier.fillMaxWidth())
    }

    Spacer(Modifier.height(12.dp))
    ResultImageCard(t("隐写结果", "隱寫結果", "Stego result"), stegoResultBmp, t("嵌入完成后在这里显示", "嵌入完成後在這裡顯示", "The embedded image shows here"))

    Spacer(Modifier.height(12.dp))
    InfoCard {
        TileRow(t("通道模拟 · 验证鲁棒性", "通道模擬 · 驗證魯棒性", "Channel sim · robustness"), t("模拟真实平台链路：缩放 → JPEG 重压缩 → 再提取", "模擬真實平台鏈路：縮放 → JPEG 重壓縮 → 再提取", "Simulates a real pipeline: scale → JPEG recompress → extract"))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(t("缩放", "縮放", "Scale"), style = TextStyle(color = RacSub, fontSize = 13.sp), modifier = Modifier.weight(1f))
            SelectField(scaleLabelList(), scaleIndex, widthDp = 170, onChange = onScaleChange)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(t("JPEG 重压缩质量", "JPEG 重壓縮品質", "JPEG quality"), style = TextStyle(color = RacSub, fontSize = 13.sp), modifier = Modifier.weight(1f))
            SelectField(qualityLabelList(), qualityIndex, widthDp = 170, onChange = onQualityChange)
        }
        Spacer(Modifier.height(14.dp))
        MainButton(t("模拟通道并提取", "模擬通道並提取", "Simulate & extract"), enabled = !simWorking && (stegoResultBmp != null), onClick = onSimulate)
        if (simStatus.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val simErrPrefixes = listOf(
                t("提取失败", "提取失敗", "Recovery failed"),
                t("模拟失败", "模擬失敗", "Sim failed")
            )
            BasicText(simStatus, style = TextStyle(
                color = if (simErrPrefixes.any { simStatus.startsWith(it) }) RacRed else RacBlueFg,
                fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(12.dp))
    ResultImageCard(t("传输后的图像", "傳輸後的圖像", "After channel"), simAttackedBmp, t("模拟后在这里显示被通道处理过的图像", "模擬後在這裡顯示被通道處理過的圖像", "The channel-processed image shows here"))
    Spacer(Modifier.height(12.dp))
    ResultImageCard(t("提取结果 · 恢复的秘密图", "提取結果 · 恢復的祕密圖", "Extracted secret"), simRecoveredBmp, t("模拟提取成功后在这里显示", "模擬提取成功後在這裡顯示", "Recovered secret shows here"))
    if (simRecoveredBmp != null) {
        Spacer(Modifier.height(12.dp))
        GhostButton(t("保存恢复图到相册", "保存恢復圖到相冊", "Save recovered"), RacBlue, onSaveSim)
    }
}

/* ================= 提取面板 ================= */

@Composable
private fun RacExtractPanel(
    stegoUri: Uri?, stegoBmp: Bitmap?,
    manualW: String, manualH: String,
    extractWorking: Boolean, extractStatus: String,
    extractResultBmp: Bitmap?,
    onPickStego: () -> Unit,
    onManualW: (String) -> Unit, onManualH: (String) -> Unit,
    onExtract: () -> Unit,
    onSaveExtracted: () -> Unit, onResetExtract: () -> Unit
) {
    Spacer(Modifier.height(10.dp))
    ImagePickCard(t("隐写图 · 要解密的图片", "隱寫圖 · 要解密的圖片", "Stego · to decrypt"), t("点击选择需要解密的图片", "點擊選擇需要解密的圖片", "Tap to pick the image to decrypt"), Color(0xFF00B96B), stegoBmp, onPickStego)

    Spacer(Modifier.height(12.dp))
    InfoCard {
        TileRow(t("原图尺寸（可选）", "原圖尺寸（可選）", "Original size (optional)"), t("仅当空间标尺读取失败时用于手动恢复原始尺寸", "僅當空間標尺讀取失敗時用於手動恢復原始尺寸", "Only needed when the size ruler fails; restores manually"))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(manualW, onManualW, t("宽", "寬", "W"), Modifier.weight(1f))
            BasicText("×", style = TextStyle(color = RacSub, fontSize = 14.sp))
            NumberField(manualH, onManualH, t("高", "高", "H"), Modifier.weight(1f))
        }
    }

    Spacer(Modifier.height(16.dp))
    MainButton(t("提取秘密图", "提取祕密圖", "Extract secret"), enabled = stegoBmp != null && !extractWorking, onClick = onExtract)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) { GhostButton(t("保存到相册", "保存到相冊", "Save"), RacBlue, onSaveExtracted) }
        Box(Modifier.weight(1f)) { GhostButton(t("重置", "重置", "Reset"), RacSub, onResetExtract) }
    }
    if (extractWorking) {
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { 0.5f },
            modifier = Modifier.fillMaxWidth(),
            color = RacBlue,
            trackColor = Color(0xFFE2E2E7),
            drawStopIndicator = {}
        )
        Spacer(Modifier.height(6.dp))
        BasicText(t("正在提取…", "正在提取…", "Extracting…"), style = TextStyle(color = RacSub, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth())
    }
    if (extractStatus.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        val extractErrPrefixes = listOf(
            t("提取失败", "提取失敗", "Extract failed"),
            t("未找到", "未找到", "No hidden"),
            t("读取", "讀取", "Failed to read"),
            t("保存失败", "保存失敗", "Save failed")
        )
        BasicText(extractStatus, style = TextStyle(
            color = if (extractErrPrefixes.any { extractStatus.startsWith(it) }) RacRed else RacBlueFg,
            fontSize = 12.sp), modifier = Modifier.fillMaxWidth())
    }

    Spacer(Modifier.height(12.dp))
    ResultImageCard(t("提取结果", "提取結果", "Extracted"), extractResultBmp, t("提取成功后在这里显示", "提取成功後在這裡顯示", "The extracted image shows here"))
}
