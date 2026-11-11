package com.gemini.ocr.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoPreviewViewTest {
    private fun project(rows:List<VideoCue>)=VideoProject("preview-fixture","Synthetic preview","file:///unused.mp4",
        60_000,1280,720,cues=rows)
    private val rows=listOf(VideoCue("a",0,1000,"甲","Một câu"),VideoCue("b",1000,2000,"甲","Một câu"),
        VideoCue("later",3000,60_000,"乙","Câu sau"))
    private fun draw(view:SavedTimelineSubtitleView):Bitmap=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        .also{view.draw(Canvas(it))}
    private fun pixels(image:Bitmap)=IntArray(image.width*image.height).also{image.getPixels(it,0,image.width,0,0,image.width,image.height)}

    @Test fun unchangedSegmentAndAdjacentEqualTextNeverInvalidateOrClearTheCaption(){
        val context=ApplicationProvider.getApplicationContext<Context>();val view=SavedTimelineSubtitleView(context)
        view.layout(0,0,1280,720)
        val old=VideoPreviewDiagnostics.onFrameForTest;val frames=mutableListOf<VideoPreviewFrame>()
        VideoPreviewDiagnostics.onFrameForTest={frames+=it}
        val p=project(rows)
        fun update(ms:Long)=view.update(p,ms,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
        try {
            update(0);val firstInvalidations=frames.last().invalidationCount
            val image=draw(view);try{assertTrue(pixels(image).any{it!=0})}finally{image.recycle()}
            for(time in 1L..999L step 17)update(time)
            assertEquals(firstInvalidations,frames.last().invalidationCount)
            update(1000);assertEquals("b",frames.last().cueId)
            assertEquals("Một câu",frames.last().text);assertEquals(firstInvalidations,frames.last().invalidationCount)
            update(2000);assertEquals("",frames.last().text)
            assertEquals(firstInvalidations+1,frames.last().invalidationCount)
            val empty=draw(view);try{assertTrue(pixels(empty).all{it==0})}finally{empty.recycle()}
            update(3000);assertEquals("Câu sau",frames.last().text)
            assertEquals(firstInvalidations+2,frames.last().invalidationCount)
        }finally{view.release();VideoPreviewDiagnostics.onFrameForTest=old}
    }

    @Test fun clonedMetadataAndFutureTextEditDoNotRepaintTheUnchangedCurrentCue(){
        val view=SavedTimelineSubtitleView(ApplicationProvider.getApplicationContext<Context>()).apply{layout(0,0,1280,720)}
        val old=VideoPreviewDiagnostics.onFrameForTest;val frames=mutableListOf<VideoPreviewFrame>()
        VideoPreviewDiagnostics.onFrameForTest={frames+=it}
        try {
            val p=project(rows)
            view.update(p,500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            val invalidations=frames.last().invalidationCount
            val initial=draw(view)
            val clone=p.copy(cues=p.cues.map{it.copy()},updatedAt=p.updatedAt+1)
            view.update(clone,500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            assertEquals(invalidations,frames.last().invalidationCount)
            val edited=clone.copy(cues=clone.cues.map{if(it.id=="later")it.copy(translation="Nội dung mới")else it})
            view.update(edited,500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            assertEquals(invalidations,frames.last().invalidationCount)
            val still=draw(view)
            try{assertArrayEquals(pixels(initial),pixels(still))}finally{initial.recycle();still.recycle()}
            view.update(edited,3500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            assertEquals("Nội dung mới",frames.last().text)
            assertEquals(invalidations+1,frames.last().invalidationCount)
        }finally{view.release();VideoPreviewDiagnostics.onFrameForTest=old}
    }

    @Test fun eofClearsTheLastCueAndReplayUsesTheCurrentPositionWithoutOldText(){
        val view=SavedTimelineSubtitleView(ApplicationProvider.getApplicationContext<Context>()).apply{layout(0,0,1280,720)}
        val old=VideoPreviewDiagnostics.onFrameForTest;val frames=mutableListOf<VideoPreviewFrame>()
        VideoPreviewDiagnostics.onFrameForTest={frames+=it}
        val p=project(listOf(VideoCue("first",0,1000,"甲","Mở đầu"),VideoCue("last",59_000,60_000,"乙","Kết thúc")))
        try {
            view.update(p,59_999,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            assertEquals("Kết thúc",frames.last().text)
            val before=frames.last().invalidationCount
            view.update(p,60_000,false,IntSize(1280,720),true){if(it.isNotBlank())fail(it)}
            assertNull(frames.last().cueId);assertEquals("",frames.last().text);assertTrue(frames.last().ended)
            val last=draw(view);try{assertTrue(pixels(last).all{it==0})}finally{last.recycle()}
            repeat(20){view.update(p,60_000,false,IntSize(1280,720),true){if(it.isNotBlank())fail(it)}}
            assertEquals(before+1,frames.last().invalidationCount)
            view.update(p,0,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            assertEquals("first",frames.last().cueId);assertEquals("Mở đầu",frames.last().text)
            assertFalse(frames.last().ended)
        }finally{view.release();VideoPreviewDiagnostics.onFrameForTest=old}
    }

    @Test fun previewSortsAProjectionAndRejectsAmbiguousOverlapWithoutMutatingSavedRows(){
        val view=SavedTimelineSubtitleView(ApplicationProvider.getApplicationContext<Context>()).apply{layout(0,0,1280,720)}
        val old=VideoPreviewDiagnostics.onFrameForTest;val frames=mutableListOf<VideoPreviewFrame>()
        VideoPreviewDiagnostics.onFrameForTest={frames+=it}
        try {
            val late=VideoCue("late",5000,6000,"乙","Sau");val early=VideoCue("early",0,1000,"甲","Trước")
            val p=project(listOf(late,early))
            view.update(p,500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            assertEquals("early",frames.last().cueId);assertEquals(listOf(late,early),p.cues)
            val overlap=p.copy(cues=listOf(early.copy(endMs=5500),late))
            var error=""
            view.update(overlap,5200,false,IntSize(1280,720),false){error=it}
            assertTrue(error.isNotBlank());assertEquals("",frames.last().text)
            assertEquals(5500L,overlap.cues.first().endMs);assertEquals(2,overlap.cues.size)
            view.update(p,500,false,IntSize(1280,720),false){error=it}
            assertEquals("",error);assertEquals("Trước",frames.last().text)
        }finally{view.release();VideoPreviewDiagnostics.onFrameForTest=old}
    }

    @Test fun aReleasedCaptionViewCannotPublishFromAnOldOwner(){
        val view=SavedTimelineSubtitleView(ApplicationProvider.getApplicationContext<Context>()).apply{layout(0,0,1280,720)}
        val old=VideoPreviewDiagnostics.onFrameForTest;var observations=0
        VideoPreviewDiagnostics.onFrameForTest={observations++}
        try {
            val p=project(rows);view.update(p,500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            view.release();val before=observations
            view.update(p,3500,false,IntSize(1280,720),false){if(it.isNotBlank())fail(it)}
            draw(view).recycle();assertEquals(before,observations)
        }finally{view.release();VideoPreviewDiagnostics.onFrameForTest=old}
    }
}
