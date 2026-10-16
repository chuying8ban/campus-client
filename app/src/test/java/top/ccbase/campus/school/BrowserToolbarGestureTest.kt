package top.ccbase.campus.school

import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class BrowserToolbarGestureTest {
 private class Trace {
  val web=WebView(ApplicationProvider.getApplicationContext())
  val frames=mutableListOf<Float>()
  var blocked=false
  val gesture=BrowserToolbarGesture(web,{blocked},100f){frames.add(it)}
  fun send(a:Int,y:Float,x:Float=100f){val e=MotionEvent.obtain(0,16,a,x,y,0);assertFalse(gesture.onTouch(web,e));e.recycle()}
 }
 @Test fun continuousFramesOnUnscrollablePageAndReverseHysteresis(){
  val t=Trace();assertFalse(t.web.canScrollVertically(1))
  t.send(0,300f);t.send(2,270f);t.send(2,250f);t.send(2,230f)
  println("DRAG frames=${t.frames}")
  assertEquals(3,t.frames.size);assertTrue(t.frames.all{it>0f && it<1f})
  assertTrue(t.frames.zipWithNext().all{(a,b)->b<a})
  val last=t.frames.last();repeat(5){t.send(2,234f);t.send(2,230f)}
  assertEquals(last,t.frames.last(),.001f)
  t.send(2,290f);assertTrue(t.frames.last()>last)
  t.send(2,420f);assertEquals(1f,t.frames.last(),0f)
  t.send(1,420f);t.web.destroy()
 }
 @Test fun horizontalMultitouchAndClickDoNotMoveChrome(){
  val t=Trace()
  t.send(0,300f);t.send(1,300f);assertTrue(t.frames.isEmpty())
  t.send(0,300f);t.send(2,290f,200f);t.send(2,100f,200f);assertTrue(t.frames.isEmpty())
  t.send(0,300f);t.send(MotionEvent.ACTION_POINTER_DOWN,300f);t.frames.clear();t.send(2,100f);assertTrue(t.frames.isEmpty())
  t.web.destroy()
 }
 @Test fun releaseAnimatesThroughIntermediateFramesToHidden(){
  val t=Trace();t.send(0,300f);t.send(2,270f)
  val before=t.frames.last();t.send(1,270f)
  val animator=org.robolectric.util.ReflectionHelpers.getField<android.animation.ValueAnimator>(t.gesture,"animator")
  animator.currentPlayTime=80L
  assertTrue(t.frames.last()<before);assertTrue(t.frames.last()>0f)
  animator.currentPlayTime=160L
  assertEquals(0f,t.frames.last(),.001f);println("SNAP frames=${t.frames}");t.web.destroy()
 }
 @Test fun menuCancelsWholeStreamAndBackRestores(){
  val t=Trace();t.blocked=true;t.send(0,300f);t.send(2,100f);assertTrue(t.frames.isEmpty())
  t.blocked=false;t.send(2,60f);assertTrue(t.frames.isEmpty())
  t.send(0,300f);t.send(2,100f);assertEquals(0f,t.frames.last(),0f)
  t.gesture.restore();assertEquals(1f,t.frames.last(),0f)
  t.send(2,50f);assertEquals(1f,t.frames.last(),0f);t.web.destroy()
 }
}
