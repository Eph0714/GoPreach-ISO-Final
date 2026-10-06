package com.emfitsolutions.gopreach.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import java.io.File

/**
 * "Can the line barrier also show in Google Maps?" — Google Maps' own
 * directions/share deep links have no parameter for an arbitrary polygon;
 * the only real path for a boundary to appear in an actual Google product is
 * Google My Maps' KML import, which is a manual, opt-in step the Publisher
 * takes after receiving this file (there is no public API to push a shape
 * into My Maps automatically). This produces that KML file and hands it to
 * the system share sheet, same FileProvider pattern [BibleTextExporter.share]
 * already uses — cacheDir/exports/, already covered by file_paths.xml.
 */
object BoundaryKmlExporter {

    /** [geometryJson] is a bare GeoJSON Polygon geometry (as
     * [com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository]
     * returns it) — one outer ring plus any further rings as holes, each a
     * list of [lng, lat] pairs per the GeoJSON spec. KML coordinates are
     * "lng,lat,altitude" — same lng-then-lat order, just with an added
     * altitude field, so this is a direct re-join rather than a swap. */
    private fun toKml(geometryJson: String, barangayName: String, municipality: String): String {
        val geometry = JsonParser.parseString(geometryJson).asJsonObject
        val rings = geometry.getAsJsonArray("coordinates")
        fun ringCoordinates(ringIndex: Int): String =
            rings[ringIndex].asJsonArray.joinToString(" ") { point ->
                val p = point.asJsonArray
                "${p[0].asDouble},${p[1].asDouble},0"
            }
        val outerRing = ringCoordinates(0)
        val holes = (1 until rings.size()).joinToString("\n") { i ->
            "<innerBoundaryIs><LinearRing><coordinates>${ringCoordinates(i)}</coordinates></LinearRing></innerBoundaryIs>"
        }
        val safeName = barangayName.replace("&", "&amp;").replace("<", "&lt;")
        val safeMunicipality = municipality.replace("&", "&amp;").replace("<", "&lt;")
        // KML colors are aabbggrr (alpha, blue, green, red) — the reverse of
        // the app's usual #RRGGBB — matching the same red (#D32F2F) the
        // in-app boundary polygon uses, fill at the same low opacity.
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>$safeName, $safeMunicipality</name>
                <Placemark>
                  <name>$safeName</name>
                  <Style>
                    <LineStyle><color>ff2f2fd3</color><width>3</width></LineStyle>
                    <PolyStyle><color>332f2fd3</color></PolyStyle>
                  </Style>
                  <Polygon>
                    <outerBoundaryIs><LinearRing><coordinates>$outerRing</coordinates></LinearRing></outerBoundaryIs>
                    $holes
                  </Polygon>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()
    }

    /** Writes the KML under cacheDir/exports/ and launches the system share
     * sheet — the receiving app (Google My Maps, Drive, Gmail, ...) is
     * whatever the Publisher picks; My Maps is the one that actually renders
     * the polygon, per this feature's "possible but manual" ceiling (see
     * this object's own doc comment). */
    fun share(context: Context, geometryJson: String, barangayName: String, municipality: String) {
        val kml = toKml(geometryJson, barangayName, municipality)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeFileName = barangayName.replace(Regex("[^A-Za-z0-9]+"), "_")
        val file = File(dir, "$safeFileName-boundary.kml")
        file.writeText(kml, Charsets.UTF_8)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.google-earth.kml+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share barangay boundary"))
    }
}
