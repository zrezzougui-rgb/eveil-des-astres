package com.zrezzougui.orbite.launcher

import android.app.Activity
import android.os.Bundle

/**
 * Ouvert par un dossier épinglé sur l'écran d'accueil du téléphone :
 * affiche le dossier d'Orbite par-dessus, puis se referme.
 */
class FolderActivity : Activity() {

    private var apps: List<AppEntry> = emptyList()
    private lateinit var actions: AppActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_FOLDER)
        if (name.isNullOrBlank()) { finish(); return }
        val repo = AppRepository(this)
        actions = AppActions(this, repo, { apps }, onChanged = { finish() })
        Thread {
            val list = runCatching { repo.load() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                apps = list
                actions.openFolder(name) { finish() }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        // Une appli a été ouverte depuis le dossier : on ne garde pas Orbite derrière.
        finish()
    }

    companion object {
        const val EXTRA_FOLDER = "dossier"
    }
}
