package com.rudy.chambre

import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Une page ouverte par-dessus la chambre : un jeu, une vitrine, l'atelier.
 *
 * Ces ecrans ne bougent qu'a la demande, ils n'ont donc rien a gagner a etre
 * redessines soixante fois par seconde. Ils restent des pages, et gardent le
 * pont vers le telephone : choisir un dossier de ROMs, lancer un jeu.
 */
/**
 * Le rangement pret a poser dans une page : les memes gestes que celui des
 * artefacts, mais servi par le telephone.
 */
private const val RANGEMENT = """
(function(){
  if (typeof Android === "undefined" || !Android.memoireEcrire) return;
  if (window.storage && window.storage.__pose) return;
  window.storage = {
    __pose: true,
    get: function(cle){
      return new Promise(function(ok, non){
        try{
          var v = Android.memoireLire(cle);
          if (v === null || v === "") { non(new Error("rien sous " + cle)); return; }
          ok({ key: cle, value: v, shared: false });
        }catch(e){ non(e); }
      });
    },
    set: function(cle, valeur){
      return new Promise(function(ok, non){
        try{
          var fait = Android.memoireEcrire(cle, String(valeur));
          if (!fait) { non(new Error("ecriture refusee")); return; }
          ok({ key: cle, value: valeur, shared: false });
        }catch(e){ non(e); }
      });
    },
    delete: function(cle){
      return new Promise(function(ok){
        try{ ok({ key: cle, deleted: Android.memoireEffacer(cle), shared: false }); }
        catch(e){ ok({ key: cle, deleted: false, shared: false }); }
      });
    },
    list: function(){ return Promise.resolve({ keys: [], shared: false }); }
  };

  /* ---- le journal : tout ce que la vitrine dit ou tente y tombe ---- */
  if (Android.noterJournal) {
    Android.noterJournal("=== VITRINE ouverte : " + document.title);

    /* les messages affiches par la vitrine */
    if (typeof ouvrirMenu === "function") {
      var ancienMenu = ouvrirMenu;
      window.ouvrirMenu = function(m){
        try{ if (m) Android.noterJournal("message : " + m); }catch(e){}
        return ancienMenu.apply(this, arguments);
      };
      try{ ouvrirMenu = window.ouvrirMenu; }catch(e){}
    }

    /* l'appui sur « Lancer le jeu », avec ce que la vitrine sait du boitier */
    var bouton = document.getElementById("jouer");
    if (bouton) {
      bouton.addEventListener("click", function(){
        try{
          var j = (typeof jeux !== "undefined" && typeof index !== "undefined") ? jeux[index] : null;
          Android.noterJournal("appui sur Lancer — boitier : " +
            (j ? (j.titre || "sans titre") : "aucun") +
            " · ROM : " + (j && j.uri ? "oui" : "NON") +
            " · pont : " + ((typeof Android !== "undefined" && Android.lancer) ? "oui" : "NON"));
        }catch(e){ try{ Android.noterJournal("appui sur Lancer — " + e.message); }catch(_){} }
      }, true);
    }

    /* toute erreur de la page */
    window.addEventListener("error", function(ev){
      try{ Android.noterJournal("ERREUR page : " + ev.message + " (" + ev.lineno + ")"); }catch(e){}
    });
  }
})();
"""

class PageActivity : ComponentActivity() {

    private lateinit var vue: WebView
    private var consoleEnAttente: String? = null
    /** Ce que la page attend quand elle demande un fichier. */
    private var attenteFichier: ValueCallback<Array<Uri>>? = null

    private val choisirDossierLivres =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
                getSharedPreferences("chambre_rudy", MODE_PRIVATE).edit()
                    .putString("dossier_livres", uri.toString()).apply()
            }
            runOnUiThread { vue.evaluateJavascript("window.livresChanges && window.livresChanges()", null) }
        }

    /** Un seul livre, choisi a la main. */
    private val choisirUnLivre =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try { contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            val nom = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(this, uri)?.name ?: "livre.pdf"
            Thread {
                Livres.ajouter(this, uri, nom)
                runOnUiThread {
                    vue.evaluateJavascript("window.livresChanges && window.livresChanges()", null)
                }
            }.start()
        }

    private val choisirFichiers =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { liste ->
            attenteFichier?.onReceiveValue(liste.toTypedArray())
            attenteFichier = null
        }

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val console = consoleEnAttente ?: return@registerForActivityResult
            consoleEnAttente = null
            if (uri != null) Dossiers.retenir(this, console, uri)
            repondre(console)
        }

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        val page = intent.getStringExtra("page") ?: "jeux/atelier.html"
        val titre = intent.getStringExtra("titre") ?: ""
        if (intent.getBooleanExtra("paysage", false))
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val serveur = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            // les pages de livres, fabriquees a la demande dans l'espace de l'application
            .addPathHandler("/livres/", WebViewAssetLoader.InternalStoragePathHandler(
                this, java.io.File(filesDir, "livres")))
            .build()

        vue = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    serveur.shouldInterceptRequest(r.url)

                /**
                 * Des que la page est la, on lui donne son rangement.
                 *
                 * Les vitrines rangent leurs affaires dans « window.storage ».
                 * Ce rangement n'existe pas dans un navigateur embarque : on
                 * le fabrique ici, pose sur les fichiers du telephone, et tout
                 * se retrouve d'une ouverture a l'autre.
                 */
                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    v?.evaluateJavascript(RANGEMENT, null)
                }
            }
            /*
             * Une page qui demande un fichier — « Ajouter un livre », une
             * jaquette — doit ouvrir le selecteur du telephone. Sans ce relais,
             * le bouton ne fait rien du tout.
             */
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    v: WebView?,
                    retour: ValueCallback<Array<Uri>>?,
                    parametres: FileChooserParams?
                ): Boolean {
                    attenteFichier?.onReceiveValue(null)
                    attenteFichier = retour
                    val types = parametres?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?: arrayOf("*/*")
                    return try {
                        choisirFichiers.launch(if (types.isEmpty()) arrayOf("*/*") else types)
                        true
                    } catch (_: Throwable) {
                        attenteFichier = null
                        false
                    }
                }
            }
            addJavascriptInterface(Pont(), "Android")
            loadUrl("https://appassets.androidplatform.net/assets/web/$page")
        }

        val retour = Button(this).apply {
            text = "← " + (if (titre.isNotEmpty()) titre else "Chambre")
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1A1226.toInt())
            textSize = 13f
            setOnClickListener { finish() }
        }

        val racine = FrameLayout(this)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(retour, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = (28 * resources.displayMetrics.density).toInt()
            leftMargin = (12 * resources.displayMetrics.density).toInt()
        })
        setContentView(racine)
    }

    /** Renvoie la liste a la page, dans la forme qu'elle attend. */
    private fun repondre(console: String) {
        val jeux = JSONArray()
        Dossiers.lister(this, console).forEach {
            jeux.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
        }
        val donnees = JSONObject().put("type", "roms").put("console", console).put("jeux", jeux)
        runOnUiThread {
            vue.evaluateJavascript("window.recevoirRoms && window.recevoirRoms($jeux)", null)
            vue.evaluateJavascript(
                "window.dispatchEvent(new MessageEvent('message',{data:$donnees}))", null)
        }
    }

    override fun onDestroy() { vue.destroy(); super.onDestroy() }

    /** Ce que la page peut demander au telephone. */
    inner class Pont {

        @JavascriptInterface
        fun choisirDossier(console: String) {
            consoleEnAttente = console
            runOnUiThread { choisirDossier.launch(null) }
        }

        @JavascriptInterface
        fun listerRoms(console: String): String {
            val a = JSONArray()
            Dossiers.lister(this@PageActivity, console).forEach {
                a.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
            }
            return a.toString()
        }

        /**
         * La memoire des vitrines.
         *
         * Elles enregistrent dans « window.storage », qui n'existe pas dans
         * un navigateur embarque : rien n'etait donc conserve, et il fallait
         * tout refaire a chaque ouverture. On leur donne ici un vrai rangement,
         * un fichier par vitrine — sans limite de taille, contrairement a la
         * memoire du navigateur, ce qui compte avec des jaquettes.
         */
        /** Une ligne dans le journal, ecrite par la page elle-meme. */
        @JavascriptInterface
        fun noterJournal(texte: String) {
            try {
                val d = java.io.File(filesDir, "systeme").apply { mkdirs() }
                val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE)
                    .format(java.util.Date())
                java.io.File(d, "journal_appli.txt").appendText(heure + "  " + texte + "\n")
            } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun memoireLire(cle: String): String {
            return try {
                val f = java.io.File(dossierMemoire(), nomDeFichier(cle))
                if (f.exists()) f.readText() else ""
            } catch (_: Throwable) { "" }
        }

        @JavascriptInterface
        fun memoireEcrire(cle: String, valeur: String): Boolean {
            return try {
                java.io.File(dossierMemoire(), nomDeFichier(cle)).writeText(valeur)
                true
            } catch (_: Throwable) { false }
        }

        @JavascriptInterface
        fun memoireEffacer(cle: String): Boolean {
            return try {
                java.io.File(dossierMemoire(), nomDeFichier(cle)).delete()
            } catch (_: Throwable) { false }
        }

        private fun dossierMemoire() =
            java.io.File(filesDir, "vitrines").apply { mkdirs() }

        private fun nomDeFichier(cle: String) =
            cle.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json"

        @JavascriptInterface
        fun lancer(console: String, uriRom: String) {
            runOnUiThread {
                try {
                    val fiche = Consoles.parId(console)
                    if (fiche == null) {
                        noterJournal("AUCUNE FICHE pour la console « " + console + " »")
                        return@runOnUiThread
                    }
                    marquerLeJournal(fiche.nom, uriRom)
                    noterJournal("ecran demande : " + fiche.activite)
                    // la chambre ne doit pas relancer sa musique en repassant
                    // devant : la console a la sienne
                    SonPartage.consoleLanceeA = System.currentTimeMillis()
                    /*
                     * La Nintendo 64 ouvre son catalogue.
                     *
                     * J'avais tente d'entrer directement dans la partie, en
                     * calculant l'empreinte et l'en-tete de la cartouche : le
                     * moteur refusait de la charger. Son ecran de jeu attend
                     * plus que ces renseignements — tout ce que son catalogue
                     * prepare avant lui. On revient donc a ce qui marchait :
                     * son catalogue s'ouvre, et le jeu se lance de la.
                     */

                    val i = Intent(this@PageActivity, Class.forName(fiche.activite))
                    i.putExtra("rom", uriRom)
                    // la GameCube et la Wii partagent leur ecran : il doit
                    // savoir laquelle des deux a ete posee sur la table
                    i.putExtra("console", console)

                    /*
                     * Dolphin ne regarde pas le supplement « rom ».
                     *
                     * Son ecran de jeu attend la liste des chemins sous
                     * « SelectedGames » et le titre sous « SelectedTitle ».
                     * Sans cette traduction il s'ouvrirait sans jeu.
                     */
                    if (fiche.activite.startsWith("org.dolphinemu")) {
                        i.putExtra("SelectedGames", arrayOf(uriRom))
                        i.putExtra("SelectedTitle", fiche.nom)
                        i.putExtra("riivolution", false)
                        i.putExtra("systemMenu", false)
                        // la plateforme : 0 pour la GameCube, 1 pour la Wii.
                        // Dolphin la deduit du fichier, mais l'indiquer evite
                        // qu'il hesite sur les formats communs aux deux.
                        i.putExtra("platform", if (console == "wii") 1 else 0)
                    }

                    startActivity(i)
                    noterJournal("ecran lance sans erreur : " + fiche.activite)
                    finish()                       // la vitrine s'efface derriere le jeu
                } catch (e: Throwable) {
                    noterJournal("ECHEC du lancement : " + e.toString().take(140))
                    expliquerEchec(console, e)
                }
            }
        }

        /**
         * Ecrire dans le journal quelle console vient de partir.
         *
         * L'emulateur, lui, peut mourir dans son coeur natif sans rien
         * laisser. Cette marque-ci est ecrite AVANT le depart : elle est donc
         * toujours la, et la chambre sait ensuite a quelle console rattacher
         * ce qui suit.
         */
        private fun marquerLeJournal(nomConsole: String, rom: String) {
            try {
                val d = java.io.File(filesDir, "systeme").apply { mkdirs() }
                val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE)
                    .format(java.util.Date())
                java.io.File(d, "journal_appli.txt").appendText(
                    "\n=== CONSOLE " + nomConsole + " lancée à " + heure + "\n" +
                    "jeu : " + rom.takeLast(70) + "\n")
            } catch (_: Throwable) {}
        }

        /**
         * Dire franchement pourquoi une console n'a pas demarre : son ecran
         * manque, son moteur manque, ou elle a plante — et dans ce dernier cas,
         * a quelle ligne.
         */
        private fun expliquerEchec(console: String, e: Throwable) {
            val fiche = Consoles.parId(console)
            val nom = fiche?.nom ?: console
            val cause = when {
                e is ClassNotFoundException ->
                    "son ecran n'est pas dans cet APK"
                e is UnsatisfiedLinkError ->
                    "son moteur (.so) manque ou ne se charge pas"
                else -> e.toString().take(160)
            }
            val moteur = fiche?.let { f ->
                try {
                    val dossier = applicationInfo.nativeLibraryDir
                    val fichiers = java.io.File(dossier).list()?.joinToString(" ") ?: ""
                    val c = f.coeur
                    if (c != null && !fichiers.contains(c)) "\nmoteur absent : " + c else ""
                } catch (_: Throwable) { "" }
            } ?: ""
            android.app.AlertDialog.Builder(this@PageActivity,
                    android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(nom + " ne demarre pas")
                .setMessage(cause + moteur)
                .setPositiveButton("Fermer", null)
                .show()
        }

        /** Ajoute un seul livre, choisi a la main. */
        @JavascriptInterface
        fun ajouterUnLivre() {
            runOnUiThread { choisirUnLivre.launch(arrayOf("application/pdf")) }
        }

        /** Ouvre le selecteur pour choisir le dossier des livres. */
        @JavascriptInterface
        fun choisirDossierLivres() {
            runOnUiThread { choisirDossierLivres.launch(null) }
        }

        /** La liste des livres du dossier : titre et identifiant. */
        @JavascriptInterface
        fun listerLivres(): String = Livres.lister(this@PageActivity).toString()

        /**
         * Fabrique les pages d'un livre et renvoie leurs adresses.
         *
         * Android sait ouvrir un PDF : on dessine chaque page en image, une
         * fois pour toutes, et la page web n'a plus qu'a les afficher.
         */
        @JavascriptInterface
        fun pagesDuLivre(id: String): String = Livres.pages(this@PageActivity, id).toString()

        /** La couverture d'un livre, pour l'etagere. */
        @JavascriptInterface
        fun couverture(id: String): String = Livres.couverture(this@PageActivity, id)

        /** Les pages preparees depuis : le livre s'allonge pendant la lecture. */
        @JavascriptInterface
        fun pagesPretes(id: String): String = Livres.pagesPretes(this@PageActivity, id).toString()

        /** Le jeu adoucit la musique de la chambre, sans la couper. */
        @JavascriptInterface
        fun volumeRadio(v: Float) {
            runOnUiThread { SonPartage.volume(v.coerceIn(0f, 1f)) }
        }

        /** Le jeu demande a revenir au bureau. */
        @JavascriptInterface
        fun retourChambre() { runOnUiThread { finish() } }

        @JavascriptInterface
        fun ouvrirConsole(console: String) {
            runOnUiThread {
                try {
                    val fiche = Consoles.parId(console) ?: return@runOnUiThread
                    startActivity(Intent(this@PageActivity, Class.forName(fiche.activite)))
                } catch (_: Throwable) {}
            }
        }
    }
}
