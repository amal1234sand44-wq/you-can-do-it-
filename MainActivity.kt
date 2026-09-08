package com.youcandoit.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

data class Task(val id:Long=System.currentTimeMillis(), val title:String, val type:String, val duration:Int, val due:String="", val urgent:Boolean=false, val done:Boolean=false)
data class UniversityClass(val day:String, val subject:String, val start:String, val end:String, val room:String="")
data class StudyFile(val name:String, val uri:String, val kind:String, val analysis:String="", val analyzed:Boolean=false, val transcript:String="")

class MainVM(private val app: Context): ViewModel() {
    val ai = OpenAIClient(app)
    val executor = Executors.newSingleThreadExecutor()
    var aiBusy by mutableStateOf(false); private set
    var aiError by mutableStateOf(""); private set
    private val prefs=app.getSharedPreferences("ycdi",Context.MODE_PRIVATE)
    var tasks by mutableStateOf(loadTasks()); private set
    var classes by mutableStateOf(loadClasses()); private set
    var files by mutableStateOf(loadFiles()); private set
    var plan by mutableStateOf(listOf<Task>()); private set
    var problem by mutableStateOf("")

    private fun save(){
        prefs.edit().putString("tasks",JSONArray(tasks.map{JSONObject().apply{put("id",it.id);put("title",it.title);put("type",it.type);put("duration",it.duration);put("due",it.due);put("urgent",it.urgent);put("done",it.done)}}.toString()).putString("classes",JSONArray(classes.map{JSONObject().apply{put("day",it.day);put("subject",it.subject);put("start",it.start);put("end",it.end);put("room",it.room)}}.toString()).putString("files",JSONArray(files.map{JSONObject().apply{put("name",it.name);put("uri",it.uri);put("kind",it.kind);put("analysis",it.analysis);put("analyzed",it.analyzed);put("transcript",it.transcript)}}.toString()).apply() }
    private fun loadTasks()=try{JSONArray(prefs.getString("tasks","[]")).let{a->List(a.length()){val o=a.getJSONObject(it);Task(o.getLong("id"),o.getString("title"),o.getString("type"),o.getInt("duration"),o.optString("due"),o.optBoolean("urgent"),o.optBoolean("done"))}}}catch(_:Exception){emptyList()}
    private fun loadClasses()=try{JSONArray(prefs.getString("classes","[]")).let{a->List(a.length()){val o=a.getJSONObject(it);UniversityClass(o.getString("day"),o.getString("subject"),o.getString("start"),o.getString("end"),o.optString("room"))}}}catch(_:Exception){emptyList()}
    private fun loadFiles()=try{JSONArray(prefs.getString("files","[]")).let{a->List(a.length()){val o=a.getJSONObject(it);StudyFile(o.getString("name"),o.getString("uri"),o.getString("kind"),o.optString("analysis"),o.optBoolean("analyzed"),o.optString("transcript"))}}}catch(_:Exception){emptyList()}
    fun addTask(t:Task){tasks=tasks+t;save(); replan()}
    fun toggle(t:Task){tasks=tasks.map{if(it.id==t.id)it.copy(done=!it.done) else it};save();replan()}
    fun remove(t:Task){tasks=tasks.filterNot{it.id==t.id};save();replan()}
    fun addClass(c:UniversityClass){classes=classes+c;save()}
    fun removeClass(c:UniversityClass){classes=classes.filterNot{it==c};save()}
    fun addFile(f:StudyFile){files=files+f;save()}
    fun analyze(f:StudyFile){
        if(ai.apiKey.isBlank()){aiError="أضيفي مفتاح OpenAI من زر إعدادات الذكاء الاصطناعي.";return}
        aiBusy=true; aiError=""
        executor.execute {
            try {
                val prompt="""أنت مساعد جامعي ذكي. حلّل هذه المحاضرة/المادة بالعربية. أخرج: 1) ملخصًا واضحًا، 2) أهم 7 نقاط، 3) مصطلحات وتعريفات، 4) أسئلة متوقعة للاختبار، 5) ما الذي يجب مراجعته، 6) تقدير مدة المراجعة بالدقائق، 7) أولوية من 1 إلى 5. لا تخترع معلومات غير موجودة."""
                var transcript=""
                val result = if(f.kind.contains("صوت",true)) {
                    val path=Uri.parse(f.uri).path ?: error("تعذر الوصول للتسجيل")
                    val file=File(path)
                    transcript=ai.transcribe(file)
                    ai.analyzeText(transcript,prompt+"\nهذه المادة ناتجة عن تفريغ تسجيل محاضرة.")
                } else {
                    ai.uploadAndAnalyze(Uri.parse(f.uri),f.name,prompt)
                }
                val finalTranscript=transcript
                runOnMain { files=files.map{if(it.uri==f.uri)it.copy(analysis=result,analyzed=true,transcript=finalTranscript)else it};save(); if(tasks.none{it.title.contains(f.name.substringBeforeLast('.'),true)})addTask(Task(title="مراجعة ذكية: ${f.name}",type="مراجعة AI",duration=45,urgent=false)); aiBusy=false }
            } catch(e:Exception){runOnMain{aiError=e.message ?: "فشل التحليل";aiBusy=false}}
        }
    }
    private fun runOnMain(block:()->Unit){(app as? Activity)?.runOnUiThread{block()}}
    fun replan(){
        val pending=tasks.filter{!it.done}.sortedWith(compareByDescending<Task>{it.urgent}.thenBy{it.due.isBlank()}.thenBy{it.due})
        plan=pending.take(8)
    }
    fun smartReplan(){
        val pending=tasks.filter{!it.done}; val today=SimpleDateFormat("EEEE",Locale.ENGLISH).format(Date())
        val dayMap=mapOf("Sunday" to "الأحد","Monday" to "الاثنين","Tuesday" to "الثلاثاء","Wednesday" to "الأربعاء","Thursday" to "الخميس","Friday" to "الجمعة","Saturday" to "السبت")
        val todayAr=dayMap[today] ?: ""
        val lectureCount=classes.count{it.day==todayAr}
        val priority=pending.sortedWith(compareByDescending<Task>{it.urgent}.thenBy{it.due.isBlank()}.thenByDescending{it.duration})
        plan=priority.take(if(lectureCount>3)5 else 8)
    }
}

class MainActivity: ComponentActivity(){
 private lateinit var vm:MainVM; private var recorder:MediaRecorder?=null; private var recording=false; private var currentFile:File?=null
 private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->uris.forEach{uri->persistRead(uri);vm.addFile(StudyFile(displayName(uri),uri.toString(),mimeLabel(uri)))}}
 private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it)startRecording()}
 override fun onCreate(b:Bundle?){super.onCreate(b);vm=MainVM(this);setContent{YouCanDoIt(vm,{picker.launch(arrayOf("application/pdf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","image/*","audio/*"))},{toggleRecord()})}}
 private fun persistRead(u:Uri){try{contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}}
 private fun displayName(u:Uri)=contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else u.lastPathSegment?:"ملف"}?:"ملف"
 private fun mimeLabel(u:Uri):String{val m=contentResolver.getType(u)?:(u.toString());return when{m.contains("pdf")->"PDF";m.contains("audio")->"صوت";m.contains("image")->"صورة";m.contains("word")||m.contains("document")->"Word";m.contains("presentation")||m.contains("powerpoint")->"PowerPoint";else->"ملف"}}
 private fun toggleRecord(){if(recording){try{recorder?.stop()}catch(_:Exception){};recorder?.release();recorder=null;recording=false;currentFile?.let{vm.addFile(StudyFile(it.name,it.toURI().toString(),"صوت"))}}else if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()else permission.launch(Manifest.permission.RECORD_AUDIO)}
 private fun startRecording(){val f=File(cacheDir,"lecture_${System.currentTimeMillis()}.m4a");currentFile=f;recorder=MediaRecorder().apply{setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setOutputFile(f.absolutePath);prepare();start()};recording=true}
}

@Composable fun YouCanDoIt(vm:MainVM,pick:()->Unit,record:()->Unit){MaterialTheme{var tab by remember{mutableStateOf(0)};Scaffold(topBar={TopAppBar(title={Text("⚡ YOU CAN DO IT!")})},bottomBar={NavigationBar{listOf("اليوم","الجامعة","إضافة","ملفاتي","ساعدني").forEachIndexed{i,s->NavigationBarItem(tab==i,{tab=i},{Text(listOf("🏠","🎓","➕","📎","🧠")[i])},{Text(s)})}}}){p->Box(Modifier.padding(p).fillMaxSize()){when(tab){0->Home(vm);1->University(vm);2->AddTask(vm);3->Files(vm,pick,record);4->Help(vm)}}}}}

@Composable fun Home(vm:MainVM){LaunchedEffect(Unit){vm.smartReplan()};LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("أنت تستطيع! 💪",style=MaterialTheme.typography.headlineMedium);Text("خطة ذكية تجمع الجامعة والدراسة والحياة اليومية وتعيد ترتيب نفسها عند التغيير.")}}};item{Button(onClick={vm.smartReplan()},Modifier.fillMaxWidth()){Text("✨ إعادة بناء خطة اليوم")}};item{Text("خطة اليوم",style=MaterialTheme.typography.titleLarge)};items(vm.plan){t->TaskCard(t,{vm.toggle(t)},{vm.remove(t)})};if(vm.plan.isEmpty())item{Text("لا توجد مهام معلقة. أضيفي مهامك أو محاضراتك للبدء.")};item{Text("كل المهام",style=MaterialTheme.typography.titleLarge)};items(vm.tasks){t->TaskCard(t,{vm.toggle(t)},{vm.remove(t)})}}}
@Composable fun TaskCard(t:Task,toggle:()->Unit,remove:()->Unit){Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(t.done,{toggle()});Column(Modifier.weight(1f)){Text(t.title);Text("${t.type} • ${t.duration} دقيقة${if(t.due.isNotBlank())" • الموعد: ${t.due}" else ""}")};if(t.urgent)Text("عاجل");TextButton(onClick=remove){Text("حذف")}}}}

@Composable fun University(vm:MainVM){var subject by remember{mutableStateOf("")};var day by remember{mutableStateOf("الأحد")};var start by remember{mutableStateOf("08:00")};var end by remember{mutableStateOf("10:00")};var room by remember{mutableStateOf("")};Column(Modifier.padding(16.dp)){Text("🎓 الجدول الجامعي",style=MaterialTheme.typography.headlineSmall);Text("أضيفي كل محاضرات الأسبوع، وسيستخدمها التطبيق عند بناء الخطة.");Spacer(Modifier.height(8.dp));OutlinedTextField(subject,{subject=it},label={Text("المادة")},Modifier.fillMaxWidth());OutlinedTextField(day,{day=it},label={Text("اليوم")},Modifier.fillMaxWidth());OutlinedTextField(start,{start=it},label={Text("من")},Modifier.fillMaxWidth());OutlinedTextField(end,{end=it},label={Text("إلى")},Modifier.fillMaxWidth());OutlinedTextField(room,{room=it},label={Text("القاعة")},Modifier.fillMaxWidth());Button(onClick={if(subject.isNotBlank()){vm.addClass(UniversityClass(day,subject,start,end,room));subject=""}},Modifier.fillMaxWidth()){Text("إضافة للمحاضرات")};Spacer(Modifier.height(10.dp));LazyColumn{items(vm.classes){c->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(10.dp),Modifier.fillMaxWidth(),Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(c.subject);Text("${c.day} • ${c.start} - ${c.end}${if(c.room.isNotBlank())" • ${c.room}" else ""}")};TextButton({vm.removeClass(c)}){Text("حذف")}}}}}}}

@Composable fun AddTask(vm:MainVM){var title by remember{mutableStateOf("")};var type by remember{mutableStateOf("دراسة")};var duration by remember{mutableStateOf("60")};var due by remember{mutableStateOf("")};var urgent by remember{mutableStateOf(false)};Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("➕ إضافة مهمة",style=MaterialTheme.typography.headlineSmall);OutlinedTextField(title,{title=it},label={Text("المهمة")},Modifier.fillMaxWidth());OutlinedTextField(type,{type=it},label={Text("النوع: دراسة / واجب / اختبار / شخصي")},Modifier.fillMaxWidth());OutlinedTextField(duration,{duration=it},label={Text("المدة بالدقائق")},Modifier.fillMaxWidth());OutlinedTextField(due,{due=it},label={Text("الموعد أو تاريخ التسليم (اختياري)")},Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){Checkbox(urgent,{urgent=it});Text("عاجل")};Button(onClick={if(title.isNotBlank()){vm.addTask(Task(title=title,type=type,duration=duration.toIntOrNull()?:60,due=due,urgent=urgent));title=""}},Modifier.fillMaxWidth()){Text("إضافة وإعادة التخطيط")}}}

@Composable fun Files(vm:MainVM,pick:()->Unit,record:()->Unit){
    var key by remember{mutableStateOf(vm.ai.apiKey)}
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("📎 ملفاتي",style=MaterialTheme.typography.headlineSmall)
        Text("التحليل الآن متصل بـ OpenAI: تفريغ الصوت + تحليل الملفات + استخراج نقاط المراجعة.")
        OutlinedTextField(key,{key=it;vm.ai.apiKey=it},label={Text("مفتاح OpenAI API")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("لا تضعي المفتاح داخل مشروع منشور. أدخليه على جهازك فقط.",style=MaterialTheme.typography.bodySmall)
        Button(pick,Modifier.fillMaxWidth()){Text("📄 اختيار ملفات")}
        Button(record,Modifier.fillMaxWidth()){Text("🎙️ بدء/إيقاف تسجيل المحاضرة")}
        if(vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(vm.aiError.isNotBlank()) Text(vm.aiError,color=MaterialTheme.colorScheme.error)
        LazyColumn{items(vm.files){f->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(f.name);Text(f.kind);if(f.transcript.isNotBlank()){Text("التفريغ:");Text(f.transcript.take(1200))};if(f.analyzed)Text("تحليل AI: ${f.analysis}");Button({vm.analyze(f)}){Text(if(f.analyzed)"🔄 إعادة التحليل" else "🧠 تحليل وربطه بالخطة")}}}}}
    }
}

@Composable fun Help(vm:MainVM){var p by remember{mutableStateOf("")};var answer by remember{mutableStateOf("")};Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("🧠 ساعدني",style=MaterialTheme.typography.headlineSmall);OutlinedTextField(p,{p=it},label={Text("ما المشكلة؟")},Modifier.fillMaxWidth().height(160.dp));Button(onClick={answer=when{p.contains("متراكم")||p.contains("امتحان")||p.contains("اختبار")->"أعطِ الأولوية لأقرب موعد، ثم قسّم المادة إلى جلسات 25–45 دقيقة. أضيفي المهمة وسأعيد ترتيب الخطة.";p.contains("وقت")||p.contains("مشغول")->"قلّلي جلسة الدراسة إلى وحدات قصيرة وانقلي غير العاجل لوقت آخر. استخدمي إعادة بناء خطة اليوم.";else->"قسّمي المشكلة إلى خطوة صغيرة قابلة للتنفيذ، أضيفيها كـمهمة، ثم اضغطي إعادة بناء خطة اليوم."}},Modifier.fillMaxWidth()){Text("حلل المشكلة")};if(answer.isNotBlank())Card{Text(answer,Modifier.padding(16.dp))}}}
