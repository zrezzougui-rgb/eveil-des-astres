package com.zrezzougui.orbite.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Fonts
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.Radius
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.dpf
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.hud.ArcReactorView
import com.zrezzougui.orbite.jarvis.JarvisService
import com.zrezzougui.orbite.jarvis.Persona
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.rounded
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap

/**
 * Jarvis : conversation à la voix ou au clavier, réponses toujours lues à voix haute.
 * La reconnaissance de la demande utilise le moteur vocal du téléphone (sur l'appareil quand il existe) ;
 * les réponses viennent de l'assistant à règles, sans IA ni jeton.
 */
class AssistantActivity : Activity() {

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var reactor: ArcReactorView
    private lateinit var live: TextView
    private lateinit var assistant: Assistant
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var muted = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pendingQuery: String? = null
    private var pendingListen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        assistant = Assistant(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { Persona.applyVoice(this, it) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { runOnUiThread { reactor.state = ArcReactorView.State.SPEAKING } }
                    override fun onDone(id: String?) { runOnUiThread { afterSpeaking() } }
                    @Deprecated("API") override fun onError(id: String?) { runOnUiThread { afterSpeaking() } }
                })
                ttsReady = true
            }
        }

        val root = vertical().apply { setBackgroundColor(Palette.BG) }
        root.padForSystemBars()

        // En-tête HUD
        val header = horizontal().apply { setPadding(dp(16), dp(8), dp(16), dp(0)) }
        header.addView(label("‹", 28f, Palette.MUTED).apply { setPadding(0, 0, dp(12), 0); setOnClickListener { finish() } })
        header.addView(label("J.A.R.V.I.S", 18f, Palette.PRIMARY).apply {
            typeface = Fonts.hudBold(context); letterSpacing = 0.2f
        }, weighted())
        header.addView(label("🔊", 20f).apply {
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener {
                muted = !muted; text = if (muted) "🔇" else "🔊"
                if (muted) tts?.stop()
            }
        })
        root.addView(header, matchWrap())

        reactor = ArcReactorView(this).apply {
            subText = "Touchez pour parler"
            setOnClickListener { if (listening) stopListening() else listen() }
            contentDescription = "Parler à Jarvis"
        }
        root.addView(reactor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        live = label("", 15f, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), dp(4)) }
        root.addView(live, matchWrap())

        scroll = ScrollView(this).apply { isFillViewport = true }
        chat = vertical().apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Suggestions rapides
        val chips = horizontal().apply { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        listOf("Briefing", "Météo demain", "Mes rendez-vous", "Prendre un RDV", "Mes applis", "Aide").forEach { s ->
            chips.addView(pill(s) {
                when (s) {
                    "Prendre un RDV" -> { input.setText("Prends rendez-vous "); input.setSelection(input.text.length); input.requestFocus() }
                    else -> send(s)
                }
            }, wrapWrap().margins(r = dp(8)))
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, matchWrap())

        val bar = horizontal().apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        input = EditText(this).apply {
            hint = "Écrivez, ou dites « Jarvis »…"
            setHintTextColor(Palette.MUTED); setTextColor(Palette.TEXT); textSize = 16f
            background = rounded(Palette.SURFACE, dpf(Radius.PILL), dp(1), Palette.LINE_STRONG)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(text.toString()); true } else false
            }
        }
        bar.addView(input, weighted())
        bar.addView(roundButton("➤") { send(input.text.toString()) }, wrapWrap().margins(l = dp(8)))
        bar.addView(roundButton("🎤") { listen() }, wrapWrap().margins(l = dp(8)))
        root.addView(bar, matchWrap())

        setContentView(root)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        stopListening()
        // Jarvis reprend son écoute en arrière-plan.
        JarvisService.send(this, JarvisService.ACTION_RESUME)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val q = i?.getStringExtra(EXTRA_QUERY)
        when {
            !q.isNullOrBlank() -> send(q)
            i?.getBooleanExtra(EXTRA_VOICE, false) == true -> {
                if (i.getBooleanExtra(EXTRA_FROM_WAKE, false)) live.text = "Oui${Persona.suffix(this)} ?"
                listen()
            }
            chat.childCount == 0 -> bubble("${Persona.greeting(this)}. Que puis-je faire pour vous ? " +
                "Touchez le réacteur ou dites « Jarvis ». Je fonctionne sans IA : rien n'est envoyé à un modèle de langage.", mine = false)
        }
    }

    private fun roundButton(text: String, onClick: (View) -> Unit): TextView =
        label(text, 18f, Palette.ON_PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.PRIMARY, dpf(Radius.PILL))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            minWidth = dp(48); minHeight = dp(48)
            setOnClickListener(onClick)
        }

    // ------------------------------------------------------------------ conversation

    private fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        input.setText("")
        live.text = ""
        bubble(q, mine = true)
        val typing = bubble("…", mine = false)
        Thread {
            val reply = assistant.answer(q)
            runOnUiThread {
                chat.removeView(typing)
                show(reply, q)
            }
        }.start()
    }

    private fun show(reply: Reply, query: String) {
        val autoStart = reply.actions.firstOrNull()?.intent?.getBooleanExtra(Assistant.AUTO_START, false) == true
        val spoken = (if (autoStart) Persona.ack(this) + " " else "") + reply.text
        bubble(spoken, mine = false)
        speak(spoken)
        if (reply.actions.isNotEmpty()) {
            val row = horizontal()
            reply.actions.forEachIndexed { i, a ->
                row.addView(pill(a.label, filled = i == 0) { launch(a.intent) }, wrapWrap().margins(r = dp(8)))
            }
            chat.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) },
                matchWrap().margins(t = dp(2), b = dp(10)))
            // On laisse Jarvis commencer sa phrase avant d'ouvrir l'appli.
            if (autoStart) chat.postDelayed({ launch(reply.actions.first().intent) }, 900)
        }
        reply.needPermission?.let { perm ->
            pendingQuery = query
            requestPermissions(arrayOf(perm), REQ_PERMISSION)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun speak(text: String) {
        if (muted || !ttsReady) { afterSpeaking(); return }
        val clean = text.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F•]"), "").replace("°", " degrés").replace(" — ", ", ")
        JarvisService.send(this, JarvisService.ACTION_PAUSE)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun afterSpeaking() {
        if (!listening) reactor.state = ArcReactorView.State.IDLE
    }

    private fun launch(i: Intent) {
        try {
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune appli ne peut ouvrir cette action.", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Action refusée par Android.", Toast.LENGTH_LONG).show()
        }
    }

    private fun bubble(text: String, mine: Boolean): View {
        val t = label(text, 16f, if (mine) Palette.ON_PRIMARY else Palette.TEXT).apply {
            setTextIsSelectable(!mine)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (mine) rounded(Palette.PRIMARY, dpf(Radius.LG))
            else rounded(Palette.SURFACE, dpf(Radius.LG), dp(1), Palette.LINE_STRONG)
            setLineSpacing(0f, 1.15f)
        }
        val wrap = LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(if (mine) dp(48) else 0, dp(4), if (mine) 0 else dp(48), dp(4))
            addView(t, wrapWrap())
        }
        chat.addView(wrap, matchWrap())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return wrap
    }

    // ------------------------------------------------------------------ écoute de la demande

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { listenWithSystemDialog(); return }
        tts?.stop()
        JarvisService.send(this, JarvisService.ACTION_PAUSE)
        stopListening()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                reactor.state = ArcReactorView.State.LISTENING
                if (live.text.isNullOrEmpty()) live.text = "Je vous écoute…"
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { reactor.level = (rmsdB + 2f) / 12f }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { reactor.state = ArcReactorView.State.IDLE }
            override fun onError(error: Int) {
                listening = false
                reactor.state = ArcReactorView.State.IDLE
                live.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu. Touchez le réacteur pour réessayer."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisez le micro pour parler à Jarvis."
                    else -> "Écoute interrompue. Touchez le réacteur pour réessayer."
                }
                destroyRecognizer()
            }
            override fun onResults(results: Bundle?) {
                listening = false
                reactor.state = ArcReactorView.State.IDLE
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                destroyRecognizer()
                if (!text.isNullOrBlank()) send(text) else live.text = "Je n'ai rien entendu."
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) live.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        runCatching { r.startListening(i) }.onFailure { destroyRecognizer(); listenWithSystemDialog() }
    }

    private fun stopListening() {
        if (listening) runCatching { recognizer?.stopListening() }
        listening = false
        destroyRecognizer()
        if (::reactor.isInitialized) reactor.state = ArcReactorView.State.IDLE
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    /** Repli : fenêtre de dictée du système. */
    private fun listenWithSystemDialog() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Je vous écoute…")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune reconnaissance vocale installée sur ce téléphone.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("API Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) send(text)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_MIC) {
            if (granted && pendingListen) listen() else if (!granted) listenWithSystemDialog()
            pendingListen = false
            return
        }
        if (requestCode != REQ_PERMISSION) return
        val q = pendingQuery ?: return
        pendingQuery = null
        if (granted) {
            Thread { val r = assistant.answer(q); runOnUiThread { show(r, q) } }.start()
        } else {
            bubble("Autorisation refusée. Vous pouvez préciser une ville (« météo à Lyon ») ou l'activer dans les réglages Android.", mine = false)
        }
    }

    override fun onDestroy() {
        destroyRecognizer()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "query"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_FROM_WAKE = "from_wake"
        private const val REQ_SPEECH = 41
        private const val REQ_PERMISSION = 42
        private const val REQ_MIC = 43

        /** Vrai quand l'écran de Jarvis est affiché (le service d'écoute s'en sert). */
        @Volatile var visible = false
            private set

        fun open(ctx: Context, query: String? = null, voice: Boolean = false) {
            ctx.startActivity(Intent(ctx, AssistantActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .putExtra(EXTRA_VOICE, voice))
        }
    }
}
