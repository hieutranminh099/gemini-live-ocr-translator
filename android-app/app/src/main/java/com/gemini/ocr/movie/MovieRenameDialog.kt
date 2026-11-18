package com.gemini.ocr.movie

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MovieRenameDialog(movie:MovieRecord,library:MovieLibrary,onDismiss:()->Unit) {
    var name by rememberSaveable(movie.id) { mutableStateOf(movie.title) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},title={Text("Đổi tên phim")},text={
        Column {
            OutlinedTextField(name,{name=it;error=""},label={Text("Tên phim")},singleLine=true,
                enabled=!saving,isError=error.isNotBlank())
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(enabled=!saving,onClick={
        val clean=try {movieTitle(name)}catch(e:IllegalArgumentException){error=e.message.orEmpty();return@TextButton}
        saving=true
        scope.launch {
            try {library.rename(movie.id,clean);onDismiss()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error="Chưa lưu được tên phim. " + (e.message ?: "Hãy thử lại.")}
            finally{saving=false}
        }
    }){Text(if(saving)"Đang lưu…" else "Lưu")}},dismissButton={
        TextButton(enabled=!saving,onClick=onDismiss){Text("Hủy")}
    })
}
