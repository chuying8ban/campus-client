package top.ccbase.campus.school

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Passive observer. Coordinates and the WebView layout never change during a drag. */
internal class BrowserToolbarGesture(
 view: View, private val blocked:()->Boolean={false},
 private val travel:Float=48f*view.resources.displayMetrics.density,
 private val progress:(Float)->Unit
): View.OnTouchListener {
 private val slop=ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
 private var x=0f; private var y=0f; private var sumX=0f; private var sumY=0f
 private var cancelled=true; private var vertical=false; private var direction=0; private var reverse=0f
 private var fraction=1f
 private var animator:ValueAnimator?=null
 private fun publish(f:Float){fraction=f.coerceIn(0f,1f);progress(fraction)}
 fun restore(){animator?.cancel();cancelled=true;publish(1f)}
 private fun settle(){
  val target=if(direction>0)1f else if(direction<0)0f else if(fraction>=.5f)1f else 0f
  animator?.cancel()
  animator=ValueAnimator.ofFloat(fraction,target).apply{duration=160;addUpdateListener{publish(it.animatedValue as Float)};start()}
 }
 override fun onTouch(v:View,e:MotionEvent):Boolean {
  when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{animator?.cancel();x=e.rawX;y=e.rawY;sumX=0f;sumY=0f;vertical=false;direction=0;reverse=0f;cancelled=blocked()}
   MotionEvent.ACTION_POINTER_DOWN->{cancelled=true;settle()}
   MotionEvent.ACTION_CANCEL->{if(!cancelled)settle();cancelled=true}
   MotionEvent.ACTION_UP->{if(!cancelled && vertical)settle();cancelled=true}
   MotionEvent.ACTION_MOVE->{
    if(e.pointerCount!=1 || blocked()){cancelled=true;return false}
    if(!cancelled){
     val sx=e.rawX-x;val sy=e.rawY-y;x=e.rawX;y=e.rawY;sumX+=sx;sumY+=sy
     if(!vertical){
      if(abs(sumX)>slop && abs(sumX)>abs(sumY)){cancelled=true;return false}
      if(abs(sumY)<=slop || abs(sumY)<=abs(sumX)*1.5f)return false
      vertical=true;direction=if(sumY>0)1 else -1
      publish(fraction+(sumY-direction*slop)/travel)
     }else{
      val sign=if(sy>0)1 else if(sy<0)-1 else 0
      if(sign==direction){
       val net=reverse+sy
       if(net*direction>0f){reverse=0f;publish(fraction+net/travel)}else reverse=net
      }
      else if(sign!=0){reverse+=sy;if(abs(reverse)>slop*2){direction=sign;publish(fraction+(reverse-sign*slop*2)/travel);reverse=0f}}
     }
    }
   }
  }
  return false
 }
}
