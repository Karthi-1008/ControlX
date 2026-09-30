package com.controlx.gbatvplayer.rom

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

enum class ConsoleType {
    GBA,
    NES
}

data class RomInfo(
    val title: String,
    val gameCode: String,
    val filePath: String,
    val fileSizeMb: Float,
    val isZip: Boolean,
    val zipEntryName: String? = null,
    val lastModified: Long = 0L,
    val consoleType: ConsoleType = ConsoleType.GBA
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
                } else if (name.endsWith(".nes")) {
                    val romInfo = parseNesFile(f)
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
                val header = readGbaHeader(stream)
                title = header.first
                code = header.second
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read GBA header from ${file.name}", e)
        }

        if (title.isBlank()) {
            title = file.nameWithoutExtension.replace('_', ' ')
        }

        val sizeMb = file.length() / (1024f * 1024f)
        return RomInfo(
            title = title,
            gameCode = if (code.isNotBlank()) code else "GBA",
            filePath = file.absolutePath,
            fileSizeMb = sizeMb,
            isZip = false,
            lastModified = file.lastModified(),
            consoleType = ConsoleType.GBA
        )
    }

    private fun parseNesFile(file: File): RomInfo {
        var mapper = 0
        var isValidNes = false

        try {
            file.inputStream().use { stream ->
                val header = ByteArray(16)
                val read = stream.read(header)
                if (read >= 16 && header[0] == 'N'.code.toByte() && header[1] == 'E'.code.toByte() &&
                    header[2] == 'S'.code.toByte() && header[3] == 0x1A.toByte()) {
                    isValidNes = true
                    val flags6 = header[6].toInt() and 0xFF
                    val flags7 = header[7].toInt() and 0xFF
                    mapper = (flags7 and 0xF0) or (flags6 ushr 4)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read NES header from ${file.name}", e)
        }

        val title = file.nameWithoutExtension.replace('_', ' ')
        val sizeMb = file.length() / (1024f * 1024f)
        val code = if (isValidNes) "NES-M$mapper" else "NES"

        return RomInfo(
            title = title,
            gameCode = code,
            filePath = file.absolutePath,
            fileSizeMb = sizeMb,
            isZip = false,
            lastModified = file.lastModified(),
            consoleType = ConsoleType.NES
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
                                val header = readGbaHeader(stream)
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
                            gameCode = if (code.isNotBlank()) code else "GBA",
                            filePath = file.absolutePath,
                            fileSizeMb = sizeMb,
                            isZip = true,
                            zipEntryName = entry.name,
                            lastModified = file.lastModified(),
                            consoleType = ConsoleType.GBA
                        )
                    } else if (lower.endsWith(".nes")) {
                        var mapper = 0
                        var isValidNes = false
                        try {
                            zip.getInputStream(entry).use { stream ->
                                val header = ByteArray(16)
                                val read = stream.read(header)
                                if (read >= 16 && header[0] == 'N'.code.toByte() && header[1] == 'E'.code.toByte() &&
                                    header[2] == 'S'.code.toByte() && header[3] == 0x1A.toByte()) {
                                    isValidNes = true
                                    val flags6 = header[6].toInt() and 0xFF
                                    val flags7 = header[7].toInt() and 0xFF
                                    mapper = (flags7 and 0xF0) or (flags6 ushr 4)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read zip NES entry header", e)
                        }

                        val title = File(entry.name).nameWithoutExtension.replace('_', ' ')
                        val sizeMb = entry.size / (1024f * 1024f)
                        val code = if (isValidNes) "NES-M$mapper" else "NES"

                        return RomInfo(
                            title = title,
                            gameCode = code,
                            filePath = file.absolutePath,
                            fileSizeMb = sizeMb,
                            isZip = true,
                            zipEntryName = entry.name,
                            lastModified = file.lastModified(),
                            consoleType = ConsoleType.NES
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting zip ${file.name}", e)
        }
        return null
    }

    private fun readGbaHeader(stream: InputStream): Pair<String, String> {
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

    /**
     * Streams an uncompressed ROM from a ZIP file into a cache file using a small 64KB buffer.
     * Prevents allocating up to 32MB ByteArray on the low-memory Android TV Java heap.
     */
    fun extractZipRomToCache(context: Context, zipFilePath: String, entryName: String): File {
        val ext = if (entryName.lowercase().endsWith(".nes")) ".nes" else ".gba"
        val cacheFile = File(context.cacheDir, "current_rom$ext")
        ZipFile(File(zipFilePath)).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw IllegalArgumentException("Entry $entryName not found in zip")
            zip.getInputStream(entry).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        }
        return cacheFile
    }
}
