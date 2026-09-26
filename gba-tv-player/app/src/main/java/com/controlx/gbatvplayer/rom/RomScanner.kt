package com.controlx.gbatvplayer.rom

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class RomInfo(
    val title: String,
    val gameCode: String,
    val filePath: String,
    val fileSizeMb: Float,
    val isZip: Boolean,
    val zipEntryName: String? = null,
    val lastModified: Long = 0L
)

object RomScanner {
    private const val TAG = "RomScanner"

    fun getDefaultRomDir(context: Context): File {
        val externalStorage = Environment.getExternalStorageDirectory()
        val dir = File(externalStorage, "GBA_ROMS")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun scanDirectory(dir: File): List<RomInfo> {
        val list = mutableListOf<RomInfo>()
        if (!dir.exists() || !dir.isDirectory) return list

        val files = dir.listFiles() ?: return list
        for (f in files) {
            val name = f.name.lowercase()
            try {
                if (name.endsWith(".gba") || name.endsWith(".agb") || name.endsWith(".bin")) {
                    val romInfo = parseGbaFile(f)
                    list.add(romInfo)
                } else if (name.endsWith(".zip")) {
                    val romInfo = parseZipFile(f)
                    if (romInfo != null) {
                        list.add(romInfo)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing file: ${f.name}", e)
            }
        }
        return list.sortedBy { it.title.lowercase() }
    }

    private fun parseGbaFile(file: File): RomInfo {
        var title = ""
        var code = ""

        try {
            file.inputStream().use { stream ->
                val header = readHeader(stream)
                title = header.first
                code = header.second
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read header from ${file.name}", e)
        }

        if (title.isBlank()) {
            title = file.nameWithoutExtension.replace('_', ' ')
        }

        val sizeMb = file.length() / (1024f * 1024f)
        return RomInfo(
            title = title,
            gameCode = code,
            filePath = file.absolutePath,
            fileSizeMb = sizeMb,
            isZip = false,
            lastModified = file.lastModified()
        )
    }

    private fun parseZipFile(file: File): RomInfo? {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val lower = entry.name.lowercase()
                    if (lower.endsWith(".gba") || lower.endsWith(".agb") || lower.endsWith(".bin")) {
                        var title = ""
                        var code = ""
                        try {
                            zip.getInputStream(entry).use { stream ->
                                val header = readHeader(stream)
                                title = header.first
                                code = header.second
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read zip entry header", e)
                        }

                        if (title.isBlank()) {
                            title = File(entry.name).nameWithoutExtension.replace('_', ' ')
                        }

                        val sizeMb = entry.size / (1024f * 1024f)
                        return RomInfo(
                            title = title,
                            gameCode = code,
                            filePath = file.absolutePath,
                            fileSizeMb = sizeMb,
                            isZip = true,
                            zipEntryName = entry.name,
                            lastModified = file.lastModified()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting zip ${file.name}", e)
        }
        return null
    }

    private fun readHeader(stream: InputStream): Pair<String, String> {
        // GBA header: Title at 0xA0 (12 bytes), Game code at 0xAC (4 bytes)
        val skipped = stream.skip(0xA0)
        if (skipped < 0xA0) return Pair("", "")

        val titleBytes = ByteArray(12)
        stream.read(titleBytes)

        val codeBytes = ByteArray(4)
        stream.read(codeBytes)

        val title = cleanAscii(titleBytes)
        val code = cleanAscii(codeBytes)
        return Pair(title, code)
    }

    private fun cleanAscii(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c in ' '..'~' && b != 0.toByte()) {
                sb.append(c)
            } else if (b == 0.toByte()) {
                break
            }
        }
        return sb.toString().trim()
    }

    fun loadRomBytes(rom: RomInfo): ByteArray {
        val file = File(rom.filePath)
        if (!rom.isZip || rom.zipEntryName == null) {
            return file.readBytes()
        }

        ZipFile(file).use { zip ->
            val entry = zip.getEntry(rom.zipEntryName) ?: throw IllegalArgumentException("Entry not found in zip")
            zip.getInputStream(entry).use { stream ->
                return stream.readBytes()
            }
        }
    }
}
