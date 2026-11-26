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
import com.zrezzougui.orbite.hud.HudIcon
import com.zrezzougui.orbite.hud.hudBackground
import com.zrezzougui.orbite.hud.hudLabel
import com.zrezzougui.orbite.hud.hudPanel
import com.zrezzougui.orbite.hud.icon
import com.zrezzougui.orbite.hud.iconButton
import com.zrezzougui.orbite.hud.micButton
import com.zrezzougui.orbite.hud.roundHudButton
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
    private lateinit var stateLabel: TextView
    private lateinit var sendButton: View
    private lateinit var assistant: Assistant
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var muted = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pendingQuery: String? = null
    private var pendingListen = false
    /** Moteur standard d'abord (le moteur hors ligne de certains téléphones n'a pas le français : erreur 7). */
    private var useOnDevice = false
    private var triedOnDevice = false
    private var retriedSilence = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        com.zrezzougui.orbite.jarvis.Diag.install(this)
        assistant = Assistant(this)
        tts = Persona.newTts(this) { t, ok ->
            if (!ok) return@newTts
            // La liste des voix peut être longue à obtenir (certains moteurs chargent leurs modèles) : hors du fil principal.
            Thread { runCatching { Persona.applyVoice(this, t) }; ttsReady = true }.start()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { runOnUiThread { reactor.state = ArcReactorView.State.SPEAKING } }
                override fun onDone(id: String?) { runOnUiThread { afterSpeaking() } }
                @Deprecated("API") override fun onError(id: String?) { runOnUiThread { afterSpeaking() } }
            })
        }

        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF010307.toInt()) }
        val root = vertical()
        root.padForSystemBars()
        frame.addView(root)

        // Barre du haut
        val header = horizontal().apply { setPadding(dp(6), dp(4), dp(6), 0) }
        header.addView(iconButton(HudIcon.Kind.BACK, "Retour") { finish() })
        header.addView(hudLabel("J.A.R.V.I.S", Palette.PRIMARY, 12f).apply {
            typeface = Fonts.hudBold(context); letterSpacing = 0.3f; gravity = Gravity.CENTER
        }, weighted())
        val speaker = iconButton(HudIcon.Kind.SPEAKER, "Couper la voix") {}
        speaker.setOnClickListener {
            muted = !muted
            speaker.setImageDrawable(icon(if (muted) HudIcon.Kind.MUTE else HudIcon.Kind.SPEAKER, Palette.MUTED))
            speaker.contentDescription = if (muted) "Réactiver la voix" else "Couper la voix"
            if (muted) tts?.stop()
        }
        header.addView(speaker)
        root.addView(header, matchWrap())

        reactor = ArcReactorView(this).apply {
            setOnClickListener { if (listening) stopListening() else listen() }
            contentDescription = "Parler à Jarvis"
        }
        root.addView(reactor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
        stateLabel = label("veille...", 13f, 0xFF2E6A82.toInt()).apply {
            typeface = Fonts.mono(context); gravity = Gravity.CENTER; letterSpacing = 0.3f
        }
        root.addView(stateLabel, matchWrap())
        live = label("", 16f, Palette.TEXT).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(6), dp(24), dp(4)) }
        root.addView(live, matchWrap())

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        chat = vertical().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Suggestions rapides
        val chips = horizontal().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        listOf("Briefing", "Météo demain", "Mes rendez-vous", "Prendre un RDV", "Mes applis", "Aide").forEach { s ->
            chips.addView(pill(s) {
                when (s) {
                    "Prendre un RDV" -> { input.setText("Prends rendez-vous "); input.setSelection(input.text.length); input.requestFocus() }
                    else -> send(s)
                }
            }, wrapWrap().margins(r = dp(8)))
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, matchWrap())

        val bar = horizontal().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        input = EditText(this).apply {
            hint = "Écrivez une commande…"
            setHintTextColor(Palette.MUTED); setTextColor(Palette.TEXT); textSize = 15f
            typeface = Fonts.body(context)
            background = hudPanel()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(text.toString()); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val typing = !s.isNullOrBlank()
                    sendButton.visibility = if (typing) View.VISIBLE else View.GONE
                }
            })
        }
        bar.addView(input, weighted())
        sendButton = iconButton(HudIcon.Kind.SEND, "Envoyer", Palette.PRIMARY) { send(input.text.toString()) }.apply { visibility = View.GONE }
        bar.addView(sendButton)
        bar.addView(roundHudButton(HudIcon.Kind.STOP, "Arrêter") {
            stopListening(); tts?.stop(); afterSpeaking()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).margins(l = dp(8)))
        bar.addView(roundHudButton(HudIcon.Kind.MIC, "Parler à Jarvis", big = true) {
            if (listening) stopListening() else listen()
        }, LinearLayout.LayoutParams(dp(54), dp(54)).margins(l = dp(8)))
        root.addView(bar, matchWrap())

        setContentView(frame)
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
                retriedSilence = false; triedSystemDialog = false
                if (i.getBooleanExtra(EXTRA_FROM_WAKE, false)) live.text = "Oui${Persona.suffix(this)} ?"
                listen()
            }
            chat.childCount == 0 -> bubble("${Persona.greeting(this)}. Que puis-je faire pour vous ? " +
                "Touchez le réacteur ou dites « Hey Jarvis ». Je fonctionne sans IA : rien n'est envoyé à un modèle de langage.", mine = false)
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
        val typing = bubble("Analyse…", mine = false)
        setState("Traitement")
        Thread {
            val reply = assistant.answer(q)
            runOnUiThread {
                chat.removeView(typing)
                show(reply, q)
            }
        }.start()
    }

    private fun show(reply: Reply, query: String) {
        com.zrezzougui.orbite.jarvis.Diag.log(this, "Réponse : " + reply.text.replace("\n", " ").take(90))
        val autoStart = reply.actions.firstOrNull()?.intent?.getBooleanExtra(Assistant.AUTO_START, false) == true
        val spoken = (if (autoStart) Persona.ack(this) + " " else "") + reply.text
        bubble(spoken, mine = false)
        speak(spoken)
        if (reply.actions.isNotEmpty()) {
            val row = horizontal()
            reply.actions.forEachIndexed { i, a ->
                val clean = a.label.replace(Regex("^[\\p{So}\\p{Cs}\\uFE0F]+\\s*"), "")
                row.addView(pill(clean, filled = i == 0) { launch(a.intent) }, wrapWrap().margins(r = dp(8)))
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
        val clean = Persona.spoken(text)
        setState("Réponse")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun afterSpeaking() {
        if (!listening) { reactor.state = ArcReactorView.State.IDLE; setState("En veille") }
    }

    private fun setState(s: String) {
        if (!::stateLabel.isInitialized) return
        stateLabel.text = s.lowercase() + "..."
        stateLabel.setTextColor(if (s == "En veille") 0xFF2E6A82.toInt() else Palette.PRIMARY)
    }

    /** Fin d'une écoute (résultat, silence ou erreur) : « Hey Jarvis » redevient disponible tout de suite. */
    private fun endListening() {
        listening = false
        listeningNow = false
        reactor.state = ArcReactorView.State.IDLE
        destroyRecognizer()
        JarvisService.send(this, JarvisService.ACTION_RESUME)
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

    /** Entrée du journal : « VOUS · 18:41 » à droite en gris, « JARVIS · 18:41 » à gauche avec un filet cyan. */
    private fun bubble(text: String, mine: Boolean): View {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(java.util.Date())
        val entry = vertical().apply { gravity = if (mine) Gravity.END else Gravity.START }
        entry.addView(hudLabel((if (mine) "Vous" else "Jarvis") + " · " + time, if (mine) Palette.MUTED else Palette.PRIMARY, 9f).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val t = label(text, 15f, if (mine) Palette.MUTED else Palette.TEXT).apply {
            setTextIsSelectable(!mine)
            setLineSpacing(0f, 1.2f)
            gravity = if (mine) Gravity.END else Gravity.START
        }
        if (mine) {
            entry.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = dp(48)))
        } else {
            val row = horizontal().apply { gravity = Gravity.TOP }
            row.addView(View(this).apply { setBackgroundColor(Palette.PRIMARY) }, LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT))
            row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).margins(l = dp(10)))
            entry.addView(row, matchWrap())
        }
        chat.addView(entry, matchWrap().margins(t = dp(8), b = dp(8)))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return entry
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
        val onDeviceNow = useOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val r = if (onDeviceNow) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        com.zrezzougui.orbite.jarvis.Diag.log(this, "Écoute de la demande : moteur " + if (onDeviceNow) "hors ligne" else "standard")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                listeningNow = true
                setState("En écoute")
                reactor.state = ArcReactorView.State.LISTENING
                if (live.text.isNullOrEmpty()) live.text = "Je vous écoute…"
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { reactor.level = (rmsdB + 2f) / 12f }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { reactor.state = ArcReactorView.State.IDLE; setState("Traitement") }
            override fun onError(error: Int) {
                endListening()
                com.zrezzougui.orbite.jarvis.Diag.log(this@AssistantActivity,
                    "Reconnaissance vocale : erreur $error (${errorName(error)}), moteur " + if (onDeviceNow) "hors ligne" else "standard")
                // Moteur en panne ou indisponible : on essaie l'autre moteur une fois.
                val engineProblem = error !in intArrayOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                if (engineProblem && !onDeviceNow && !triedOnDevice && Build.VERSION.SDK_INT >= 31 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this@AssistantActivity)) {
                    triedOnDevice = true; useOnDevice = true; listen(); return
                }
                if (onDeviceNow) {
                    useOnDevice = false
                    if (engineProblem || error == SpeechRecognizer.ERROR_NO_MATCH) { listen(); return }
                }
                // Rien entendu (on a peut-être parlé trop tôt après le bip) : une seconde chance.
                if (!retriedSilence && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    retriedSilence = true
                    live.text = "Je n'ai pas compris. Répétez ?"
                    listen(); return
                }
                if (engineProblem && !triedSystemDialog) { triedSystemDialog = true; listenWithSystemDialog(); return }
                setState("En veille")
                live.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu. Touchez le réacteur pour réessayer."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisez le micro pour parler à Jarvis."
                    else -> "Écoute interrompue. Touchez le réacteur pour réessayer."
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                endListening()
                retriedSilence = false
                com.zrezzougui.orbite.jarvis.Diag.log(this@AssistantActivity, "Demande comprise : « ${text.orEmpty()} »")
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
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        runCatching { r.startListening(i) }.onFailure {
            com.zrezzougui.orbite.jarvis.Diag.log(this, "Reconnaissance vocale : démarrage impossible ${it.message}")
            destroyRecognizer(); listenWithSystemDialog()
        }
    }

    private var triedSystemDialog = false

    private fun errorName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "délai réseau"
        SpeechRecognizer.ERROR_NETWORK -> "réseau"
        SpeechRecognizer.ERROR_AUDIO -> "audio / micro occupé"
        SpeechRecognizer.ERROR_SERVER -> "serveur"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "aucune parole"
        SpeechRecognizer.ERROR_NO_MATCH -> "rien compris"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "moteur occupé"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "autorisation"
        10 -> "trop de requêtes"
        11 -> "serveur déconnecté"
        12 -> "langue non prise en charge"
        13 -> "langue indisponible"
        14 -> "impossible de vérifier"
        else -> "inconnue"
    }

    private fun stopListening() {
        val was = listening
        if (listening) runCatching { recognizer?.stopListening() }
        listening = false
        listeningNow = false
        destroyRecognizer()
        if (::reactor.isInitialized) { reactor.state = ArcReactorView.State.IDLE; setState("En veille") }
        if (was) JarvisService.send(this, JarvisService.ACTION_RESUME)
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

        /** Vrai pendant que l'assistant écoute la demande (le micro lui est réservé). */
        @Volatile var listeningNow = false
            private set

        fun open(ctx: Context, query: String? = null, voice: Boolean = false) {
            ctx.startActivity(Intent(ctx, AssistantActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .putExtra(EXTRA_VOICE, voice))
        }
    }
}
