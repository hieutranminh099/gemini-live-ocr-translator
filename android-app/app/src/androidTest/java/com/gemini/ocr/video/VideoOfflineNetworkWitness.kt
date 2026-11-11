package com.gemini.ocr.video

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Test only. BlueStacks may leave its synthetic Wi-Fi capabilities set while its host drops traffic. */
internal object VideoOfflineNetworkWitness {
    private var lastVerifiedAt=0L
    suspend fun verify(context:Context,force:Boolean=false) {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val available=manager.allNetworks.count{manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true}
        val args=InstrumentationRegistry.getArguments()
        val permissionDeniedExpected=args.getString("networkPermissionDeniedExpected")=="true"
        val permissionGranted=context.checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED
        if(permissionDeniedExpected){
            check(BuildConfig.VERIFICATION_OFFLINE_NETWORK && context.packageName=="com.gemini.ocr.codexbench" && !permissionGranted){"Expected the isolated verification APK with its INTERNET permission removed."}
        } else if(available==0)return
        check(permissionDeniedExpected || args.getString("hostAirplaneConfirmed")=="true"){
            "Android still reports an INTERNET network. The controller must confirm BlueStacks host airplane mode before testing its real connectivity."
        }
        if(!force && lastVerifiedAt>0 && SystemClock.elapsedRealtime()-lastVerifiedAt<60000)return
        val result=withContext(Dispatchers.IO){
            val probes=JSONArray();var reachable=false
            for(address in listOf("1.1.1.1","8.8.8.8")){
                val started=SystemClock.elapsedRealtime();val row=JSONObject().put("kind","TCP").put("public_target",address+":443")
                try{Socket().use{it.connect(InetSocketAddress(address,443),2000)};row.put("reachable",true);reachable=true}
                catch(e:Exception){row.put("reachable",false).put("error_type",e.javaClass.simpleName)}
                probes.put(row.put("elapsed_ms",SystemClock.elapsedRealtime()-started))
            }
            val row=JSONObject().put("kind","HTTPS").put("public_target","https://www.google.com/generate_204")
            var connection:HttpURLConnection?=null;val started=SystemClock.elapsedRealtime()
            try{connection=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                connection.connectTimeout=2500;connection.readTimeout=2500;connection.instanceFollowRedirects=false
                val status=connection.responseCode;row.put("status",status).put("reachable",true);reachable=true
            }catch(e:Exception){row.put("reachable",false).put("error_type",e.javaClass.simpleName)}finally{connection?.disconnect()}
            probes.put(row.put("elapsed_ms",SystemClock.elapsedRealtime()-started))
            JSONObject().put("at_ms",SystemClock.elapsedRealtime()).put("android_internet_capability_count",available)
                .put("host_airplane_confirmed_by_controller",args.getString("hostAirplaneConfirmed")=="true")
                .put("verification_mode",if(permissionDeniedExpected)"APP_INTERNET_PERMISSION_DENIED" else "DEVICE_NETWORK_DISCONNECTED")
                .put("app_internet_permission_granted",permissionGranted).put("all_public_probes_blocked",!reachable).put("probes",probes)
        }
        val file=File(context.getExternalFilesDir(null),"verification/offline-network-witness.jsonl")
        file.appendText(result.toString()+"\n",Charsets.UTF_8)
        check(result.getBoolean("all_public_probes_blocked")){"Internet is still reachable from this Android instance. See offline-network-witness.jsonl; offline acceptance has not passed."}
        lastVerifiedAt=SystemClock.elapsedRealtime()
    }
}
