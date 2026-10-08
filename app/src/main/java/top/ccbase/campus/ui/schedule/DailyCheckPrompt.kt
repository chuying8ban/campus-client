package top.ccbase.campus.ui.schedule

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import top.ccbase.campus.alarm.DailyTimetableCheck

@Composable
fun DailyCheckPrompt(ctx:Context,onUpdate:()->Unit) {
 var visible by remember { mutableStateOf(DailyTimetableCheck.due(ctx)) }
 val owner=LocalLifecycleOwner.current
 DisposableEffect(owner) {
  val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) visible=DailyTimetableCheck.due(ctx) }
  owner.lifecycle.addObserver(observer)
  onDispose {owner.lifecycle.removeObserver(observer)}
 }
 if(visible) AlertDialog(onDismissRequest={visible=false},title={Text("核对今天的课表")},text={Text("每日08:00后进入App时提醒核对学校官方课表。App尚未检查是否调课，可更新课表或选择今日不再提醒。")},confirmButton={TextButton(onClick={visible=false;onUpdate()}){Text("更新课表")}},dismissButton={TextButton(onClick={DailyTimetableCheck.suppressToday(ctx);visible=false}){Text("今日不再提醒")}})
}
