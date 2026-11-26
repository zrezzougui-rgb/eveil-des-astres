package com.zrezzougui.orbite.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.dpf
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.heading
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.rounded
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap
import java.util.Locale

/** Écran de conversation : saisie au clavier ou à la voix, réponses lues à voix haute. */
class AssistantActivity : Activity() {

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var assistant: Assistant
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastWasVoice = false
    private var pendingQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        assistant = Assistant(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                ttsReady = true
            }
        }

        val root = vertical().apply { setBackgroundColor(Palette.BG) }
        root.padForSystemBars()

        val header = horizontal().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        header.addView(label("‹", 28f, Palette.MUTED).apply {
            setPadding(0, 0, dp(16), 0); setOnClickListener { finish() }
        })
        header.addView(vertical().apply {
            addView(heading("Assistant", 22f))
            addView(label("Sans IA · sans jeton · météo et recherche via internet", 12f, Palette.MUTED))
        }, weighted())
        root.addView(header, matchWrap())

        scroll = ScrollView(this).apply { isFillViewport = true }
        chat = vertical().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Suggestions rapides
        val chips = horizontal().apply { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        listOf("Météo", "Météo demain", "Mes rendez-vous", "Prendre un RDV", "Minuteur 5 min", "Aide").forEach { s ->
            chips.addView(pill(s) {
                when (s) {
                    "Prendre un RDV" -> { input.setText("Prends rendez-vous "); input.setSelection(input.text.length); input.requestFocus() }
                    else -> send(s, voice = false)
                }
            }, wrapWrap().margins(r = dp(8)))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, matchWrap())

        val bar = horizontal().apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        input = EditText(this).apply {
            hint = "Écrivez ou touchez le micro…"
            setHintTextColor(Palette.MUTED)
            setTextColor(Palette.TEXT)
            textSize = 16f
            background = rounded(Palette.SURFACE, dpf(24f))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    send(text.toString(), voice = false); true
                } else false
            }
        }
        bar.addView(input, weighted())
        bar.addView(roundButton("➤") { send(input.text.toString(), voice = false) }, wrapWrap().margins(l = dp(8)))
        bar.addView(roundButton("🎤") { listen() }, wrapWrap().margins(l = dp(8)))
        root.addView(bar, matchWrap())

        setContentView(root)

        bubble("Bonjour ! Demandez-moi la météo, de prendre un rendez-vous, une définition… " +
            "Je fonctionne par règles, sans IA : rien n'est envoyé à un modèle de langage.", mine = false)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val q = i?.getStringExtra(EXTRA_QUERY)
        when {
            !q.isNullOrBlank() -> send(q, voice = false)
            i?.getBooleanExtra(EXTRA_VOICE, false) == true -> listen()
        }
    }

    private fun roundButton(text: String, onClick: (View) -> Unit): TextView =
        label(text, 18f, Palette.ON_PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.PRIMARY, dpf(24f))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            minWidth = dp(48); minHeight = dp(48)
            setOnClickListener(onClick)
        }

    private fun send(text: String, voice: Boolean) {
        val q = text.trim()
        if (q.isEmpty()) return
        lastWasVoice = voice
        input.setText("")
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
        val b = bubble(reply.text, mine = false)
        if (lastWasVoice && ttsReady) {
            tts?.speak(reply.text.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F•]"), ""), TextToSpeech.QUEUE_FLUSH, null, "orbite")
        }
        if (reply.actions.isNotEmpty()) {
            val row = horizontal()
            reply.actions.forEachIndexed { i, a ->
                row.addView(pill(a.label, filled = i == 0) { launch(a.intent) }, wrapWrap().margins(r = dp(8)))
            }
            chat.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; addView(row)
            }, matchWrap().margins(t = dp(2), b = dp(10)))
            val first = reply.actions.first().intent
            if (first.getBooleanExtra(Assistant.AUTO_START, false)) launch(first)
        }
        reply.needPermission?.let { perm ->
            pendingQuery = query
            requestPermissions(arrayOf(perm), REQ_PERMISSION)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        b.contentDescription = reply.text
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
            background = rounded(if (mine) Palette.PRIMARY else Palette.SURFACE, dpf(20f))
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

    private fun listen() {
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
            if (!text.isNullOrBlank()) send(text, voice = true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        val q = pendingQuery ?: return
        pendingQuery = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Thread { val r = assistant.answer(q); runOnUiThread { show(r, q) } }.start()
        } else {
            bubble("Autorisation refusée. Vous pouvez préciser une ville (« météo à Lyon ») ou l'activer dans les réglages Android.", mine = false)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "query"
        const val EXTRA_VOICE = "voice"
        private const val REQ_SPEECH = 41
        private const val REQ_PERMISSION = 42

        fun open(ctx: Context, query: String? = null, voice: Boolean = false) {
            ctx.startActivity(Intent(ctx, AssistantActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .putExtra(EXTRA_VOICE, voice))
        }
    }
}
