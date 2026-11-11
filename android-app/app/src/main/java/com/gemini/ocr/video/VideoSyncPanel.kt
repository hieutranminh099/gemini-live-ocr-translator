package com.gemini.ocr.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemini.ocr.video.sync.CloudVideoSnapshot
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun VideoSyncPanel(enabled:Boolean,onRestored:(VideoProject)->Unit) {
    val context=LocalContext.current
    val sync=remember{VideoCloudSync.get(context)}
    val state by sync.state.collectAsState()
    val scope=rememberCoroutineScope()
    var notice by remember{mutableStateOf("")}
    var snapshots by remember{mutableStateOf<List<CloudVideoSnapshot>>(emptyList())}
    var working by remember{mutableStateOf(false)}
    var expanded by rememberSaveable{mutableStateOf(false)}
    fun action(block:suspend ()->Unit){scope.launch {
        working=true;notice=""
        try{block()}catch(e:CancellationException){throw e}
        catch(e:Exception){notice=e.message?:"Đồng bộ chưa hoàn tất. Dữ liệu trong máy vẫn được giữ."}
        finally{working=false}
    }}
    val resolution=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        action{sync.completeAuthorization(result.data,result.resultCode)}
    }
    val available=enabled && !working && !state.busy
    Card(Modifier.fillMaxWidth().testTag("video_cloud_panel")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row {
                Text("Đồng bộ Google Drive",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Thu gọn" else "Thiết lập")}
            }
            Text(state.message)
            if(expanded) {
            Text(if(state.accountEmail.isBlank())"Chưa chọn tài khoản Google" else state.accountEmail)
            Text("Khi bật, video nguồn, phụ đề và cấu hình được lưu riêng trong Drive của tài khoản đã chọn. Đăng nhập cùng tài khoản trên thiết bị khác để tải về và xem. Video lớn dùng dung lượng Drive của bạn.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={action {
                val pending=sync.authorize(selectAccount=true)
                if(pending!=null)resolution.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            }},enabled=available,modifier=Modifier.testTag("video_google_connect")){Text("Chọn / đổi tài khoản Google")}
            Row {
                Checkbox(state.enabled,onCheckedChange={sync.setEnabled(it)},enabled=available && state.accountEmail.isNotBlank())
                Text("Tự đồng bộ sau khi dịch và chỉnh sửa")
            }
            Row {
                Checkbox(state.wifiOnly,onCheckedChange={sync.setWifiOnly(it)},enabled=available)
                Text("Chỉ tải lên qua Wi-Fi / mạng không tính phí")
            }
            OutlinedButton(onClick={action{snapshots=sync.listCloud();notice="Có ${snapshots.size} bản lưu trên Drive."}},enabled=available && state.accountEmail.isNotBlank(),modifier=Modifier.testTag("video_cloud_library")){Text("Xem dự án trên tài khoản này")}
            snapshots.forEach{snapshot->
                OutlinedButton(onClick={action{onRestored(sync.restore(snapshot));notice="Đã tải bản lưu thành dự án riêng trong máy."}},enabled=available){
                    Text("Tải về · ${snapshot.title} · ${snapshot.sourceBytes/1024/1024} MiB")
                }
            }
            if(working || state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(notice.isNotBlank())Text(notice,color=MaterialTheme.colorScheme.secondary)
            if(state.accountEmail.isNotBlank())TextButton(onClick={sync.disconnect();snapshots=emptyList()},enabled=available){Text("Ngắt đồng bộ trên thiết bị này")}
            }
        }
    }
}
