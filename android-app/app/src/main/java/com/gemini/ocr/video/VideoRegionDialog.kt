package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gemini.ocr.ocr.OcrProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A private draft, separate from the project. Only Confirm reaches the persistence callback. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun VideoRegionDialog(project:VideoProject, timeMs:Long, ocr:Boolean,
    onCancel:()->Unit, onConfirm:(VideoSubtitleFrame)->Unit) {
    val initial=if(ocr)VideoSubtitleFrame.fromRoi(project.roi) else project.style.frame?:VideoSubtitleFrame.BOTTOM
    var values by rememberSaveable(project.id,ocr) { mutableStateOf(listOf(initial.left,initial.top,initial.right,initial.bottom)) }
    val draft=VideoSubtitleFrame(values[0],values[1],values[2],values[3])
    var discard by rememberSaveable { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var previewBusy by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf("") }
    var seekMs by rememberSaveable(project.id,ocr) { mutableLongStateOf(VideoRegionSeek.clamp(timeMs,project.durationMs)) }
    var sliderMs by remember(project.id) { mutableFloatStateOf(seekMs.toFloat()) }
    var scrubbing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    val frameMutex=remember { Mutex() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val liveRunning by com.gemini.ocr.SessionState.running.collectAsState()
    fun change(frame:VideoSubtitleFrame){values=listOf(frame.left,frame.top,frame.right,frame.bottom);previewText=""}
    fun seek(value:Long){seekMs=VideoRegionSeek.clamp(value,project.durationMs);sliderMs=seekMs.toFloat();previewText=""}
    LaunchedEffect(project.uri,seekMs) {
        val at=seekMs
        val request=++generation
        loading=true;loadError="";previewText=""
        var loaded:Bitmap?=null
        var published=false
        try {
            frameMutex.withLock { withContext(Dispatchers.IO){loaded=VideoDecoder.preview(context,Uri.parse(project.uri),at)} }
            bitmap=loaded
            published=true
            if(loaded==null)loadError="Không lấy được hình ở vị trí này. Tua sang cảnh khác rồi thử lại."
        }catch(e:CancellationException){throw e}
        catch(e:Exception){bitmap=null;loadError="Không đọc được hình video (${e.javaClass.simpleName})."}
        finally {if(!published)loaded?.recycle();if(request==generation)loading=false}
    }
    DisposableEffect(bitmap){val owned=bitmap;onDispose{owned?.recycle()}}
    Dialog(onDismissRequest={discard=true},properties=DialogProperties(usePlatformDefaultWidth=false,
        dismissOnBackPress=true,dismissOnClickOutside=false)) {
        Surface(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}) {
            Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(ocr)"Chọn vùng chữ Trung" else "Chọn khung phụ đề Việt",style=MaterialTheme.typography.titleLarge)
                Text("Kéo trong khung để di chuyển; kéo cạnh hoặc góc để đổi kích thước. Chưa lưu thay đổi.",style=MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged{size=it},contentAlignment=Alignment.Center) {
                    bitmap?.let{image->
                        Image(image.asImageBitmap(),"Hình video đang tạm dừng",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                        if(size.width>0 && size.height>0)SubtitleFrameEditor(draft,::change,
                            Modifier.fillMaxSize().testTag("video_region_canvas"),enabled=!previewBusy && !loading && !scrubbing,
                            color=if(ocr)Color(0xFF6EE7C7) else Color(0xFFFFD166),
                            viewport=VideoViewport.calculate(image.width,image.height,size.width,size.height,false))
                    }?:Text(loadError.ifBlank{"Đang lấy hình video…"},color=Color.White,modifier=Modifier.padding(16.dp))
                    if(loading && bitmap!=null)CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                Text("${VideoRegionSeek.time(sliderMs.toLong())} / ${VideoRegionSeek.time(project.durationMs)} · Tua đến cảnh có phụ đề để chọn vùng chữ",
                    style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("video_region_time"))
                Slider(value=sliderMs.coerceIn(0f,VideoRegionSeek.end(project.durationMs).toFloat().coerceAtLeast(1f)),
                    onValueChange={sliderMs=it;scrubbing=true;previewText=""},
                    onValueChangeFinished={scrubbing=false;seek(sliderMs.toLong())},
                    valueRange=0f..VideoRegionSeek.end(project.durationMs).toFloat().coerceAtLeast(1f),
                    enabled=!previewBusy && project.durationMs>1,modifier=Modifier.fillMaxWidth().testTag("video_region_seek"))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(-5000L,-1000L,1000L,5000L).forEach { delta ->
                        OutlinedButton(onClick={seek(seekMs+delta)},enabled=!previewBusy && !scrubbing,
                            modifier=Modifier.testTag("video_region_seek_$delta")) { Text("${if(delta>0)"+" else ""}${delta/1000} giây") }
                    }
                }
                if(previewText.isNotBlank())Surface(color=if(previewError)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(previewText,Modifier.fillMaxWidth().heightIn(max=100.dp).verticalScroll(rememberScrollState()).padding(10.dp)
                        .testTag("video_region_ocr_result"))
                }
                if(ocr && liveRunning)Text("Tạm dừng dịch trực tiếp để xem thử OCR.",style=MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=onCancel,modifier=Modifier.testTag("video_region_cancel")){Text("Hủy")}
                    OutlinedButton(onClick={change(if(ocr)VideoSubtitleFrame.fromRoi(listOf(.06f,.72f,.94f,.96f)) else VideoSubtitleFrame.BOTTOM)},
                        enabled=!previewBusy,modifier=Modifier.testTag("video_region_reset")){Text("Đặt lại")}
                    if(ocr)OutlinedButton(onClick={
                        val selected=draft;previewBusy=true;previewText="Đang nhận diện vùng đã chọn…";previewError=false
                        scope.launch {
                            try{previewText=VideoRoiPreview.recognize(context,project,seekMs,selected).ifBlank{"Không tìm thấy chữ Trung trong vùng này."}}
                            catch(e:CancellationException){throw e}
                            catch(e:Exception){previewError=true;previewText="Chưa xem thử được OCR (${e.javaClass.simpleName})."}
                            finally{previewBusy=false}
                        }
                    },enabled=!previewBusy && !loading && !scrubbing && bitmap!=null && !liveRunning,modifier=Modifier.testTag("video_region_preview")){Text("Xem thử OCR")}
                    Button(onClick={onConfirm(draft)},enabled=!previewBusy && !loading && !scrubbing && bitmap!=null,
                        modifier=Modifier.testTag("video_region_confirm")){Text("Xác nhận vùng")}
                }
            }
            if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("Bỏ thay đổi vùng?")},
                text={Text("Vùng đã lưu vẫn được giữ nếu bạn bỏ thay đổi.")},
                confirmButton={TextButton(onClick=onCancel,modifier=Modifier.testTag("video_region_discard")){Text("Bỏ thay đổi")}},
                dismissButton={TextButton(onClick={discard=false},modifier=Modifier.testTag("video_region_continue")){Text("Tiếp tục chỉnh")}})
        }
    }
}

internal object VideoRegionSeek {
    fun end(durationMs:Long)=durationMs.coerceAtLeast(1)-1
    fun clamp(timeMs:Long,durationMs:Long)=timeMs.coerceIn(0L,end(durationMs))
    fun time(timeMs:Long):String {
        val seconds=timeMs.coerceAtLeast(0)/1000
        return if(seconds>=3600) "%d:%02d:%02d".format(java.util.Locale.ROOT,seconds/3600,seconds/60%60,seconds%60)
            else "%02d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)
    }
}

/** One local frame and one local OCR task. Never starts a translation job or cloud request. */
internal object VideoRoiPreview {
    suspend fun recognize(context:Context,project:VideoProject,timeMs:Long,frame:VideoSubtitleFrame):String=withContext(Dispatchers.IO) {
        require(!com.gemini.ocr.SessionState.running.value){"Tạm dừng dịch trực tiếp để xem thử OCR."}
        val retriever=MediaMetadataRetriever()
        val original=try {
            retriever.setDataSource(context,Uri.parse(project.uri))
            requireNotNull(retriever.getFrameAtTime(timeMs.coerceIn(0,project.durationMs)*1000,MediaMetadataRetriever.OPTION_CLOSEST))
        }finally{retriever.release()}
        var crop:Bitmap?=null;var input:Bitmap?=null
        val processor=OcrProcessor("zh")
        try {
            val left=(original.width*frame.left).toInt().coerceIn(0,original.width-1)
            val top=(original.height*frame.top).toInt().coerceIn(0,original.height-1)
            val right=(original.width*frame.right).toInt().coerceIn(left+1,original.width)
            val bottom=(original.height*frame.bottom).toInt().coerceIn(top+1,original.height)
            crop=Bitmap.createBitmap(original,left,top,right-left,bottom-top)
            val target=VideoOcrInputSize.forRoi(crop.width,crop.height)
            input=if(target.first==crop.width && target.second==crop.height)crop else Bitmap.createScaledBitmap(crop,target.first,target.second,true)
            // Native ML Kit retains this bitmap until its Task completes, even if the dialog closes.
            withContext(NonCancellable){processor.process(input,retainSourceFragments=true,fastDownscale=false,ocrPreCorrection=false)
                .map{it.text}.filter{com.gemini.ocr.ocr.OcrRecoveryPolicy.hasHan(it)}.joinToString("\n")}
        }finally{processor.close();if(input!==crop && input!==original)input?.recycle();if(crop!==original)crop?.recycle();original.recycle()}
    }
}
