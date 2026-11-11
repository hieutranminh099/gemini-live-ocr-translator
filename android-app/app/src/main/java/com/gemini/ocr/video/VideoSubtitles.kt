@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

object VideoSubtitles {
    fun parse(name:String,data:ByteArray,durationMs:Long):List<VideoCue> {
        require(data.size<=8*1024*1024){"Tệp phụ đề vượt 8 MiB."}
        val mime=when(name.substringAfterLast('.').lowercase()){
            "srt"->MimeTypes.APPLICATION_SUBRIP;"vtt"->MimeTypes.TEXT_VTT
            "ass","ssa"->MimeTypes.TEXT_SSA;else->error("Chọn tệp SRT, ASS hoặc VTT UTF-8.")
        }
        val parser=DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mime).build())
        val rows=mutableListOf<VideoCue>()
        parser.parse(data,0,data.size,SubtitleParser.OutputOptions.allCues()) { item ->
            val text=item.cues.mapNotNull{it.text?.toString()}.filter{it.isNotBlank()}.joinToString("\n")
            if(text.isNotBlank()){
                require(rows.size<VideoProject.MAX_CUES)
                require(item.startTimeUs!=C.TIME_UNSET && item.durationUs!=C.TIME_UNSET){"Phụ đề thiếu timestamp."}
                rows.add(VideoCue("sub-${rows.size+1}",item.startTimeUs/1000,item.endTimeUs/1000,text))
            }
        }
        require(rows.isNotEmpty()){ "Tệp không có phụ đề văn bản hợp lệ." }
        return VideoTimeline.validate(rows,durationMs)
    }
    fun readSidecar(context:Context,project:VideoProject):List<VideoCue> {
        val bytes=checkNotNull(context.contentResolver.openInputStream(Uri.parse(project.subtitleUri))).use { input ->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=8*1024*1024){"Tệp phụ đề vượt 8 MiB."};out.write(buffer,0,n)}
            out.toByteArray()
        }
        return parse(project.subtitleName,bytes,project.durationMs)
    }

    /** Container extraction, no video decoding and no network source. Text samples are bounded. */
    suspend fun embedded(context:Context,uri:Uri,durationMs:Long,selectedTrack:Int=-1,probeOnly:Boolean=false,
        gate:suspend()->Unit={},progress:(Int)->Unit={},allowEmpty:Boolean=false):Pair<List<VideoTextTrack>,List<VideoCue>> {
        require(uri.scheme in setOf("content","file")){"Chỉ nhận video được chọn trong máy."}
        val dataSource=DefaultDataSource.Factory(context).createDataSource()
        val tracks=linkedMapOf<Int,VideoTextTrack>();val rows=linkedMapOf<Int,MutableList<VideoCue>>()
        val timelines=linkedMapOf<Int,VideoEmbeddedTimeline>()
        var extractor:Extractor?=null;var input:DefaultExtractorInput?=null;var totalLength=C.LENGTH_UNSET.toLong()
        var endedTracks=false
        fun open(position:Long):DefaultExtractorInput {
            dataSource.close()
            val remaining=dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            if(remaining!=C.LENGTH_UNSET.toLong())totalLength=position+remaining
            return DefaultExtractorInput(dataSource,position,totalLength)
        }
        try {
            input=open(0)
            extractor=DefaultExtractorsFactory().setTextTrackTranscodingEnabled(true).createExtractors()
                .firstOrNull { candidate -> try { candidate.sniff(input!!) } finally { input!!.resetPeekPosition() } }
                ?: error("Container video chưa được hỗ trợ. Chọn SRT/ASS/VTT đi kèm hoặc OCR vùng chữ.")
            extractor.init(object:ExtractorOutput{
                override fun endTracks(){endedTracks=true}
                override fun seekMap(map:SeekMap){}
                override fun track(id:Int,type:Int):TrackOutput {
                    if(type!=C.TRACK_TYPE_TEXT)return DummyTrackOutput()
                    return object:TrackOutput{
                        val pending=ByteArrayOutputStream();var mime=""
                        var replacement=Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
                        override fun format(format:Format){
                            mime=format.sampleMimeType?:""
                            replacement=format.cueReplacementBehavior
                            tracks[id]=VideoTextTrack(id,format.language?:"Không rõ",mime)
                        }
                        override fun sampleData(reader:androidx.media3.common.DataReader,length:Int,allowEndOfInput:Boolean,sampleDataPart:Int):Int {
                            val buffer=ByteArray(minOf(length,65536));val n=reader.read(buffer,0,buffer.size)
                            if(n<0){if(allowEndOfInput)return C.RESULT_END_OF_INPUT;throw java.io.EOFException()}
                            require(pending.size()+n<=2*1024*1024){"Sample phụ đề quá lớn."};pending.write(buffer,0,n);return n
                        }
                        override fun sampleData(data:ParsableByteArray,length:Int,sampleDataPart:Int){
                            require(pending.size()+length<=2*1024*1024){"Sample phụ đề quá lớn."}
                            val buffer=ByteArray(length);data.readBytes(buffer,0,length);pending.write(buffer)
                        }
                        override fun sampleMetadata(timeUs:Long,flags:Int,size:Int,offset:Int,cryptoData:TrackOutput.CryptoData?){
                            val all=pending.toByteArray();val start=all.size-offset-size;require(start>=0)
                            if(!probeOnly && (selectedTrack<0 || id==selectedTrack) && mime==MimeTypes.APPLICATION_MEDIA3_CUES){
                                val parsed=CueDecoder().decode(timeUs,all,start,size)
                                val text=parsed.cues.mapNotNull{it.text?.toString()}.filter{it.isNotBlank()}.joinToString("\n")
                                val timeline=timelines.getOrPut(id){VideoEmbeddedTimeline(id,durationMs,
                                    rows.getOrPut(id){mutableListOf()})}
                                timeline.sample(parsed.startTimeUs,parsed.durationUs,text,
                                    replacement==Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
                            }
                            pending.reset();if(offset>0)pending.write(all,all.size-offset,offset)
                        }
                    }
                }
            })
            val seek=PositionHolder();var reads=0
            while(true){currentCoroutineContext().ensureActive();if(reads++%64==0){gate();if(totalLength>0)progress((input!!.position*100/totalLength).toInt().coerceIn(0,99))}
                when(extractor.read(input!!,seek)){
                    Extractor.RESULT_END_OF_INPUT->break
                    Extractor.RESULT_SEEK->input=open(seek.position)
                }
                if(probeOnly && endedTracks)break
            }
            timelines.values.forEach{it.finish()}
            val chosen=if(selectedTrack>=0)selectedTrack else rows.entries.firstOrNull{it.value.isNotEmpty()}?.key
            val cues=chosen?.let{rows[it]}?:emptyList()
            if(!probeOnly && !allowEmpty)require(cues.isNotEmpty()){ "Không có phụ đề văn bản đọc được ở luồng này. Chọn tệp đi kèm, OCR hoặc nhận diện giọng nói." }
            return tracks.values.toList() to if(probeOnly)emptyList() else VideoTimeline.validate(cues,durationMs)
        } finally { extractor?.release();dataSource.close() }
    }
}

/** Replace-style tracks (including MP4 tx3g) can end a cue with the next empty sample. */
internal class VideoEmbeddedTimeline(private val track:Int,private val durationMs:Long,
    private val cues:MutableList<VideoCue>) {
    private data class Pending(val startUs:Long,val endUs:Long?,val text:String)
    private var pending:Pending?=null
    private var lastStartUs=-1L

    fun sample(startUs:Long,durationUs:Long,text:String,replace:Boolean) {
        require(startUs>=0 && startUs>=lastStartUs){"Thứ tự timestamp phụ đề nhúng không hợp lệ."}
        require(durationUs==C.TIME_UNSET || durationUs>=0){"Thời lượng phụ đề nhúng không hợp lệ."}
        closeAt(startUs)
        lastStartUs=startUs
        if(text.isBlank())return
        val end=if(durationUs==C.TIME_UNSET)null else Math.addExact(startUs,durationUs)
        if(replace)pending=Pending(startUs,end,text)
        else {
            require(end!=null){"Luồng phụ đề thiếu thời gian kết thúc; dùng tệp SRT/ASS/VTT."}
            append(Pending(startUs,end,text),end)
        }
    }

    fun finish(){closeAt(durationMs*1000)}

    private fun closeAt(nextUs:Long) {
        val old=pending?:return
        pending=null
        append(old,minOf(old.endUs?:nextUs,nextUs))
    }

    private fun append(sample:Pending,endUs:Long) {
        val startMs=sample.startUs/1000
        val endMs=minOf(endUs/1000,durationMs)
        if(endMs<=startMs)return
        require(cues.size<VideoProject.MAX_CUES)
        cues+=VideoCue("track-$track-${cues.size+1}",startMs,endMs,sample.text,origin="EMBEDDED")
    }
}
