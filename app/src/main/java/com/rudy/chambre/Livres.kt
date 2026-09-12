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

        cible.mkdirs()
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(livre.getString("uri")), "r")
                ?: return sortie
            PdfRenderer(pfd).use { lecteur ->
                val largeur = 1100                       // assez fin pour lire, assez leger pour tenir
                for (i in 0 until lecteur.pageCount) {
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
                    sortie.put("https://appassets.androidplatform.net/livres/$id/$nom")
                }
            }
            pfd.close()
        } catch (_: Throwable) {}
        return sortie
    }
}
