package com.jarvis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sin

const val MODEL = "gemini-2.5-flash"
const val CY = 0xFF19D3FF.toInt()
const val SYSTEM = """You are JARVIS, Iron Man's AI. Call user Sir. Speak in Hinglish, short, witty. You control the user's Android phone.
Reply ONLY with JSON: {"say":"short spoken reply","actions":[ ... ]}
Action objects (use only when the user asks): 
{"type":"torch","on":true|false}
{"type":"call","who":"name or number"}
{"type":"whatsapp","who":"name or number","text":"message"}
{"type":"open_app","name":"app name"}
{"type":"youtube","query":"..."}
{"type":"search","query":"..."}
{"type":"alarm","hour":7,"minute":30}
{"type":"timer","seconds":300}
{"type":"volume","dir":"up|down|mute"}
{"type":"wifi"} {"type":"bluetooth"}
{"type":"home"} {"type":"back"} {"type":"recents"} {"type":"notifications"} {"type":"screenshot"} {"type":"lock"}
{"type":"click","text":"visible text on screen"}
{"type":"scroll","dir":"down|up"}
{"type":"type","text":"text to type in focused field"}
Device info is given in brackets at the start of each user message; use it to answer time/battery questions. Actions run in order."""

class VizView(c: Context) : View(c) {
    var target = 0.1f
    private var amp = 0f
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = CY
        setShadowLayer(16f, 0f, 0f, CY)
    }
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(cv: Canvas) {
        t += 0.03f; amp += (target - amp) * 0.08f
        val w = width.toFloat(); val c = w / 2; val r = w * 0.3f
        p.strokeWidth = 2f; p.alpha = 110; cv.drawCircle(c, c, w * 0.47f, p)
        p.pathEffect = DashPathEffect(floatArrayOf(14f, 22f), t * 40f); p.alpha = 160
        cv.drawCircle(c, c, w * 0.42f, p); p.pathEffect = null
        p.alpha = 255; p.strokeWidth = 6f
        for (i in 0 until 96) {
            val a = i / 96f * Math.PI * 2 - Math.PI / 2
            val n = sin(t * 6 + i * 0.7) * 0.5 + sin(t * 3.3 + i * 1.9) * 0.35 + sin(t * 9 + i * 0.31) * 0.25
            val len = 6f + (abs(n) * amp * w * 0.1f).toFloat() + amp * w * 0.015f
            val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
            cv.drawLine(c + cs * r, c + sn * r, c + cs * (r + len), c + sn * (r + len), p)
        }
        p.strokeWidth = 3f; cv.drawCircle(c, c, r - 14f, p)
        postInvalidateOnAnimation()
    }
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var viz: VizView
    private lateinit var heard: TextView
    private lateinit var logV: TextView
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private var torchOn = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) handle(t)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        tts = TextToSpeech(this, this)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(36), dp(16), dp(16)) }
        val key = EditText(this).apply {
            hint = "GEMINI_API_KEY"; setHintTextColor(0xFF4F93AD.toInt()); setTextColor(CY); textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", "")); setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { prefs.edit().putString("key", e.toString().trim()).apply() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        val title = TextView(this).apply {
            text = "JARVIS"; textSize = 40f; setTextColor(0xFFE8FBFF.toInt()); typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.4f; gravity = Gravity.CENTER; setShadowLayer(40f, 0f, 0f, CY); setPadding(0, dp(12), 0, dp(4))
        }
        viz = VizView(this)
        val mic = Button(this).apply {
            text = "MIC"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF021019.toInt()); setStroke(dp(2), CY) }
            setOnClickListener { listen() }
        }
        val box = FrameLayout(this)
        box.addView(viz, FrameLayout.LayoutParams(-1, -1))
        box.addView(mic, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        root.addView(key, LinearLayout.LayoutParams(-1, -2))
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(box, LinearLayout.LayoutParams(dp(320), dp(320)))
        heard = TextView(this).apply { setTextColor(0xFFBFEFFF.toInt()); gravity = Gravity.CENTER; text = "Mic dabaiye, Sir."; textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(heard, LinearLayout.LayoutParams(-1, -2))
        val acc = Button(this).apply { text = "ENABLE PHONE CONTROL"; setTextColor(CY); setBackgroundColor(0xFF03161F.toInt()); setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        root.addView(acc, LinearLayout.LayoutParams(-1, -2))
        logV = TextView(this).apply { setTextColor(0xFF7FB6C9.toInt()); textSize = 13f; setPadding(0, dp(8), 0, 0) }
        val sv = ScrollView(this).apply { addView(logV) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshLog()
    }

    override fun onInit(st: Int) {
        tts.language = Locale("en", "IN"); tts.setPitch(0.75f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { runOnUiThread { viz.target = 1f } }
            override fun onDone(id: String?) { runOnUiThread { viz.target = 0.1f } }
            @Deprecated("x") override fun onError(id: String?) { runOnUiThread { viz.target = 0.1f } }
        })
    }

    private fun listen() {
        tts.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye, Sir...")
        try { viz.target = 0.45f; speech.launch(i) } catch (e: Exception) { heard.text = "Speech service nahi mila." }
    }

    private fun refreshLog() {
        val h = JSONArray(prefs.getString("hist", "[]")); val sb = StringBuilder()
        for (i in 0 until h.length()) { val o = h.getJSONObject(i); sb.append(if (o.getString("role") == "user") "YOU: " else "JARVIS: ").append(o.getJSONArray("parts").getJSONObject(0).getString("text")).append("\n\n") }
        logV.text = sb
    }

    private fun deviceInfo(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val time = SimpleDateFormat("EEE d MMM, hh:mm a", Locale.getDefault()).format(Date())
        return "[time: $time, battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%] "
    }

    private fun handle(text: String) {
        heard.text = "“$text”"; viz.target = 0.3f
        Thread {
            val out = try { gemini(text) } catch (e: Exception) { """{"say":"Sir, network ya API error: ${e.message?.replace("\"", "'")}","actions":[]}""" }
            runOnUiThread {
                try {
                    val j = JSONObject(out); val say = j.optString("say", "Done, Sir.")
                    val acts = j.optJSONArray("actions") ?: JSONArray()
                    for (i in 0 until acts.length()) run(acts.getJSONObject(i))
                    remember(text, say); heard.text = say; refreshLog()
                    tts.speak(say, TextToSpeech.QUEUE_FLUSH, null, "j")
                } catch (e: Exception) { heard.text = "Reply samajh nahi aaya."; viz.target = 0.1f }
            }
        }.start()
    }

    private fun remember(u: String, m: String) {
        val h = JSONArray(prefs.getString("hist", "[]")); val n = JSONArray()
        fun entry(r: String, t: String) = JSONObject().put("role", r).put("parts", JSONArray().put(JSONObject().put("text", t)))
        for (i in maxOf(0, h.length() - 10) until h.length()) n.put(h.getJSONObject(i))
        n.put(entry("user", u)); n.put(entry("model", m)); prefs.edit().putString("hist", n.toString()).apply()
    }

    private fun gemini(text: String): String {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) return """{"say":"Sir, pehle upar GEMINI_API_KEY daaliye.","actions":[]}"""
        val contents = JSONArray(prefs.getString("hist", "[]"))
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", deviceInfo() + text))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val resp = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) return JSONObject().put("say", "Gemini error ${c.responseCode}, key check kijiye Sir.").put("actions", JSONArray()).toString()
        return JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun number(q: String): String {
        if (q.any { it.isDigit() }) return q.filter { it.isDigit() || it == '+' }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val P = ContactsContract.CommonDataKinds.Phone
        contentResolver.query(P.CONTENT_URI, arrayOf(P.NUMBER), "${P.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null)?.use { if (it.moveToFirst()) return it.getString(0) }
        return ""
    }

    private fun go(i: Intent) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { heard.text = "Ye action phone par nahi chala." } }
    private fun global(a: Int) { val s = JarvisAccess.inst; if (s == null) { heard.text = "Pehle ENABLE PHONE CONTROL dabaiye, Sir."; return }; s.performGlobalAction(a) }

    private fun run(a: JSONObject) {
        when (a.optString("type")) {
            "torch" -> try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                torchOn = a.optBoolean("on", !torchOn); cm.setTorchMode(id, torchOn)
            } catch (e: Exception) { heard.text = "Torch nahi mila." }
            "call" -> { val n = number(a.optString("who")); val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                go(Intent(if (ok && n.isNotEmpty()) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }
            "whatsapp" -> { val n = number(a.optString("who")).replace("+", "")
                go(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(a.optString("text"))))) }
            "open_app" -> { val pm = packageManager; val q = a.optString("name").lowercase()
                val hit = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
                val li = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (li != null) go(li) else heard.text = "App nahi mila: $q" }
            "youtube" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")))))
            "search" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))))
            "alarm" -> go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "timer" -> go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "volume" -> { val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (a.optString("dir")) { "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    else -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI) } }
            "wifi" -> go(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            "bluetooth" -> go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "screenshot" -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "lock" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "click" -> if (JarvisAccess.inst?.clickText(a.optString("text")) != true) heard.text = "Screen par '${a.optString("text")}' nahi dikha."
            "scroll" -> JarvisAccess.inst?.scroll(a.optString("dir") != "up")
            "type" -> JarvisAccess.inst?.typeText(a.optString("text"))
        }
    }
}
