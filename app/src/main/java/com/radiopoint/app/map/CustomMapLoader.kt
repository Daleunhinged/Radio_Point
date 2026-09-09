package com.radiopoint.app.map

import android.content.Context
import android.net.Uri
import android.util.Log
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.MapView
import java.io.File
import java.io.FileOutputStream

data class CustomMapPackage(
    val fileName: String,
    val file: File,
    val sizeBytes: Long
)

class CustomMapLoader(private val context: Context) {

    private val tag = "CustomMapLoader"
    private val mapsDir = File(context.filesDir, "custom_maps").apply { mkdirs() }

    fun getImportedMaps(): List<CustomMapPackage> {
        val files = mapsDir.listFiles { _, name ->
            name.endsWith(".mbtiles", ignoreCase = true) ||
            name.endsWith(".sqlite", ignoreCase = true) ||
            name.endsWith(".zip", ignoreCase = true)
        } ?: emptyArray()

        return files.map {
            CustomMapPackage(
                fileName = it.name,
                file = it,
                sizeBytes = it.length()
            )
        }
    }

    /**
     * Imports an offline map archive (.mbtiles, .sqlite, .zip) from an Android SAF content URI.
     */
    fun importMapArchiveFromUri(uri: Uri, fileName: String): CustomMapPackage? {
        val safeName = File(fileName).name.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        if (!listOf(".mbtiles", ".sqlite", ".zip").any { safeName.endsWith(it, true) }) return null
        val destinationFile = File(mapsDir, safeName)
        val tempFile = File.createTempFile("import-", "." + safeName.substringAfterLast('.'), mapsDir)
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source -> tempFile.outputStream().use { out ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 1_000_000_000L) { "Map archive exceeds 1 GB" }
                    out.write(buffer, 0, n)
                }
            } }
            require(tempFile.length() > 0)
            val archive = ArchiveFileFactory.getArchiveFile(tempFile) ?: error("Unsupported archive")
            archive.close()
            check(tempFile.renameTo(destinationFile))
            CustomMapPackage(safeName, destinationFile, destinationFile.length())
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(tag, "Map import failed", e)
            null
        }
    }

    /**
     * Applies the custom map archive to the OSMDroid MapView.
     */
    fun applyMapArchiveToMapView(mapView: MapView, mapPackage: CustomMapPackage?): Boolean {
        if (mapPackage == null || !mapPackage.file.exists()) {
            // Revert to default online/cached TileSource
            val oldProvider = mapView.tileProvider
            mapView.tileProvider = MapTileProviderBasic(context)
            oldProvider.detach()
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.setUseDataConnection(true)
            mapView.invalidate()
            return true
        }

        try {
            val archiveFile = ArchiveFileFactory.getArchiveFile(mapPackage.file)
            if (archiveFile == null) {
                Log.e(tag, "Failed to parse archive format for ${mapPackage.fileName}")
                return false
            }

            val tileSourceNames = archiveFile.tileSources
            val tileSourceName = if (tileSourceNames.isNotEmpty()) {
                tileSourceNames.iterator().next()
            } else {
                mapPackage.fileName.substringBeforeLast(".")
            }

            archiveFile.close()
            val customTileSource = FileBasedTileSource.getSource(tileSourceName)
            val registerReceiver = SimpleRegisterReceiver(context)
            val offlineTileProvider = OfflineTileProvider(registerReceiver, arrayOf(mapPackage.file))

            val oldProvider = mapView.tileProvider
            mapView.tileProvider = offlineTileProvider
            oldProvider.detach()
            mapView.setTileSource(customTileSource)
            mapView.setUseDataConnection(false)
            mapView.invalidate()

            Log.i(tag, "Successfully loaded custom offline map layer: ${mapPackage.fileName}")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error applying custom map archive: ${e.message}", e)
            return false
        }
    }

    fun deleteMap(mapPackage: CustomMapPackage): Boolean {
        return try {
            mapPackage.file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
