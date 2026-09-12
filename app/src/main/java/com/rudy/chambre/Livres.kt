package com.rudy.chambre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * La bibliotheque : les PDF du dossier choisi, et leurs pages dessinees en
 * images par Android lui-meme. Aucune connexion, aucun lecteur a telecharger.
 */
object Livres {

    private fun dossier(ctx: Context): DocumentFile? {
        val brut = ctx.getSharedPreferences("chambre_rudy", Context.MODE_PRIVATE)
            .getString("dossier_livres", null) ?: return null
        return try { DocumentFile.fromTreeUri(ctx, Uri.parse(brut)) } catch (_: Throwable) { null }
    }

    /** Un identifiant stable, tire du nom : il sert de nom de dossier aux pages. */
    private fun identifiant(nom: String) =
        nom.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60)

    fun lister(ctx: Context): JSONArray {
        val sortie = JSONArray()
        // d'abord les livres ajoutes un par un
        for ((id, titre, uri) in ajoutes(ctx))
            sortie.put(JSONObject().put("titre", titre).put("id", id).put("uri", uri))
        val racine = dossier(ctx) ?: return sortie
        fun parcourir(d: DocumentFile, profondeur: Int) {
            if (profondeur > 2) return
            for (f in try { d.listFiles() } catch (_: Throwable) { return }) {
                if (f.isDirectory) parcourir(f, profondeur + 1)
                else {
                    val n = f.name ?: continue
                    if (!n.lowercase().endsWith(".pdf")) continue
                    val titre = n.dropLast(4).replace('_', ' ')
                    sortie.put(JSONObject()
                        .put("titre", titre)
                        .put("id", identifiant(n))
                        .put("uri", f.uri.toString()))
                }
            }
        }
        parcourir(racine, 0)
        return sortie
    }

    /**
     * Dessine les pages d'un PDF choisi un par un, et le retient dans la
     * bibliotheque. C'est le second bouton : ajouter un seul livre.
     */
    fun ajouter(ctx: Context, uri: Uri, nom: String): JSONObject {
        val titre = nom.removeSuffix(".pdf").removeSuffix(".PDF").replace('_', ' ')
        val id = identifiant(nom)
        val liste = ctx.getSharedPreferences("chambre_rudy", Context.MODE_PRIVATE)
        val ajoutes = liste.getStringSet("livres_ajoutes", HashSet())!!.toMutableSet()
        ajoutes.add(id + "|" + titre + "|" + uri)
        liste.edit().putStringSet("livres_ajoutes", ajoutes).apply()
        dessiner(ctx, id, uri)
        return JSONObject().put("titre", titre).put("id", id)
    }

    /** Les livres ajoutes un par un, en plus de ceux du dossier. */
    private fun ajoutes(ctx: Context): List<Triple<String, String, String>> =
        ctx.getSharedPreferences("chambre_rudy", Context.MODE_PRIVATE)
            .getStringSet("livres_ajoutes", HashSet())!!
            .mapNotNull {
                val p = it.split("|", limit = 3)
                if (p.size == 3) Triple(p[0], p[1], p[2]) else null
            }

    /**
     * Dessine les pages du livre en images, et renvoie leurs adresses.
     * Un livre deja prepare n'est pas refait.
     */
    fun pages(ctx: Context, id: String): JSONArray {
        val sortie = JSONArray()
        val cible = File(File(ctx.filesDir, "livres"), id)
        if (cible.isDirectory) {
            val dejaLa = cible.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }
            if (!dejaLa.isNullOrEmpty()) {
                dejaLa.forEach { sortie.put("https://appassets.androidplatform.net/livres/$id/${it.name}") }
                return sortie
            }
        }
        val livre = lister(ctx).let { l ->
            (0 until l.length()).map { l.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        } ?: return sortie

        dessiner(ctx, id, Uri.parse(livre.getString("uri")))
        cible.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach {
            sortie.put("https://appassets.androidplatform.net/livres/$id/${it.name}")
        }
        return sortie
    }

    /**
     * Dessine les pages du PDF en images.
     *
     * Les premieres d'abord, pour qu'on puisse commencer a lire tout de suite ;
     * le reste continue tout seul pendant la lecture. Un livre de deux cents
     * pages s'ouvre ainsi en une seconde au lieu d'une minute.
     */
    private fun dessiner(ctx: Context, id: String, uri: Uri, premieres: Int = 6) {
        val cible = File(File(ctx.filesDir, "livres"), id)
        if (File(cible, "complet").exists()) return
        cible.mkdirs()
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
            PdfRenderer(pfd).use { lecteur ->
                val largeur = 950                        // assez fin pour lire, assez leger pour tenir
                val jusqua = if (premieres > 0) minOf(premieres, lecteur.pageCount) else lecteur.pageCount
                for (i in 0 until jusqua) {
                    val nomAttendu = File(cible, String.format("p%04d.jpg", i))
                    if (nomAttendu.exists()) continue
                    val p = lecteur.openPage(i)
                    val hauteur = (largeur.toFloat() * p.height / p.width).toInt()
                    val image = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
                    image.eraseColor(Color.WHITE)
                    p.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close()
                    val nom = String.format("p%04d.jpg", i)
                    FileOutputStream(File(cible, nom)).use {
                        image.compress(Bitmap.CompressFormat.JPEG, 78, it)
                    }
                    image.recycle()
                }
                if (jusqua >= lecteur.pageCount) File(cible, "complet").writeText("ok")
            }
            pfd.close()
        } catch (_: Throwable) {}

        // la suite se prepare en arriere-plan, pendant qu'on lit les premieres
        if (premieres > 0 && !File(cible, "complet").exists()) {
            Thread { dessiner(ctx, id, uri, 0) }
                .apply { priority = Thread.MIN_PRIORITY }.start()
        }
    }

    /** Les pages deja pretes d'un livre, pour rafraichir pendant la lecture. */
    fun pagesPretes(ctx: Context, id: String): JSONArray {
        val sortie = JSONArray()
        val cible = File(File(ctx.filesDir, "livres"), id)
        cible.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach {
            sortie.put("https://appassets.androidplatform.net/livres/$id/${it.name}")
        }
        return sortie
    }
}
