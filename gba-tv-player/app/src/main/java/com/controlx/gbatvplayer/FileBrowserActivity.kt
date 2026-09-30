package com.controlx.gbatvplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.controlx.gbatvplayer.databinding.ActivityFileBrowserBinding
import com.controlx.gbatvplayer.rom.RomInfo
import com.controlx.gbatvplayer.rom.RomScanner
import java.io.File
import java.util.zip.ZipFile

class FileBrowserActivity : AppCompatActivity() {

    companion object {
        const val PREFS_SETTINGS = "gba_tv_settings"
        const val KEY_CUSTOM_DIR = "custom_rom_dir"
        const val EXTRA_SELECTED_FOLDER = "extra_selected_folder"
    }

    private lateinit var binding: ActivityFileBrowserBinding
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private val browserItems = mutableListOf<BrowserItem>()
    private lateinit var adapter: BrowserAdapter

    sealed class BrowserItem {
        data class DirectoryItem(val file: File, val childCount: Int) : BrowserItem()
        data class GameFileItem(val file: File, val sizeMb: Float, val isZip: Boolean, val zipRomTitle: String? = null) : BrowserItem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val startingPath = intent.getStringExtra(EXTRA_SELECTED_FOLDER)
        if (startingPath != null && File(startingPath).exists() && File(startingPath).isDirectory) {
            currentDir = File(startingPath)
        } else {
            val romDir = RomScanner.getDefaultRomDir(this)
            currentDir = if (romDir.exists()) romDir else Environment.getExternalStorageDirectory()
        }

        setupRecyclerView()
        setupButtons()
        loadDirectory(currentDir)
    }

    private fun setupRecyclerView() {
        adapter = BrowserAdapter()
        binding.recyclerBrowser.layoutManager = LinearLayoutManager(this)
        binding.recyclerBrowser.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnUpDir.setOnClickListener {
            goUpDirectory()
        }

        binding.btnUseCurrentFolder.setOnClickListener {
            selectCurrentFolder()
        }

        binding.btnQuickRoms.setOnClickListener {
            val dir = RomScanner.getDefaultRomDir(this)
            if (!dir.exists()) dir.mkdirs()
            loadDirectory(dir)
        }

        binding.btnQuickDownload.setOnClickListener {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir.exists()) loadDirectory(dir) else loadDirectory(File("/storage/emulated/0/Download"))
        }

        binding.btnQuickInternal.setOnClickListener {
            loadDirectory(Environment.getExternalStorageDirectory())
        }

        binding.btnQuickUsb.setOnClickListener {
            val storageRoot = File("/storage")
            if (storageRoot.exists()) {
                loadDirectory(storageRoot)
            } else {
                Toast.makeText(this, "External storage directory not found", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnQuickApp.setOnClickListener {
            val appDir = getExternalFilesDir(null) ?: filesDir
            loadDirectory(appDir)
        }
    }

    private fun loadDirectory(dir: File) {
        currentDir = dir
        binding.tvBrowserPath.text = dir.absolutePath

        val files = dir.listFiles()
        browserItems.clear()

        if (files != null) {
            // First collect directories
            val dirs = files.filter { it.isDirectory && !it.isHidden && it.canRead() }
                .sortedBy { it.name.lowercase() }
                .map { folder ->
                    val count = folder.listFiles()?.size ?: 0
                    BrowserItem.DirectoryItem(folder, count)
                }

            // Then collect game files (.gba, .nes, .zip, .agb, .bin)
            val gameFiles = files.filter { it.isFile && !it.isHidden && isGameFile(it) }
                .sortedBy { it.name.lowercase() }
                .map { file ->
                    val sizeMb = file.length() / (1024f * 1024f)
                    val isZip = file.name.lowercase().endsWith(".zip")
                    val zipTitle = if (isZip) inspectZip(file) else null
                    BrowserItem.GameFileItem(file, sizeMb, isZip, zipTitle)
                }

            browserItems.addAll(dirs)
            browserItems.addAll(gameFiles)
        }

        adapter.notifyDataSetChanged()

        if (browserItems.isEmpty()) {
            binding.tvEmptyFolder.visibility = View.VISIBLE
        } else {
            binding.tvEmptyFolder.visibility = View.GONE
            binding.recyclerBrowser.post {
                binding.recyclerBrowser.scrollToPosition(0)
            }
        }
    }

    private fun isGameFile(file: File): Boolean {
        val lower = file.name.lowercase()
        return lower.endsWith(".gba") || lower.endsWith(".agb") || lower.endsWith(".bin") ||
               lower.endsWith(".nes") || lower.endsWith(".zip")
    }

    private fun inspectZip(file: File): String? {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val lower = entry.name.lowercase()
                    if (lower.endsWith(".gba") || lower.endsWith(".agb") || lower.endsWith(".bin") || lower.endsWith(".nes")) {
                        return entry.name
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun goUpDirectory() {
        val parent = currentDir.parentFile
        if (parent != null && parent.canRead()) {
            loadDirectory(parent)
        } else {
            Toast.makeText(this, "Root storage directory reached", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectCurrentFolder() {
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_DIR, currentDir.absolutePath)
            .apply()

        val data = Intent().apply {
            putExtra(EXTRA_SELECTED_FOLDER, currentDir.absolutePath)
        }
        setResult(Activity.RESULT_OK, data)
        Toast.makeText(this, "Folder saved: ${currentDir.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun launchGameDirectly(item: BrowserItem.GameFileItem) {
        val file = item.file
        val title = file.nameWithoutExtension.replace('_', ' ')
        val isNes = file.name.lowercase().endsWith(".nes") ||
                    (item.zipRomTitle != null && item.zipRomTitle.lowercase().endsWith(".nes"))
        val intent = Intent(this, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_ROM_PATH, file.absolutePath)
            putExtra(EmulatorActivity.EXTRA_ROM_TITLE, title)
            putExtra(EmulatorActivity.EXTRA_ROM_IS_ZIP, item.isZip)
            putExtra(EmulatorActivity.EXTRA_ROM_ZIP_ENTRY, item.zipRomTitle)
            putExtra(EmulatorActivity.EXTRA_CONSOLE_TYPE, if (isNes) "NES" else "GBA")
        }
        startActivity(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && currentDir.absolutePath != "/storage/emulated/0" && currentDir.absolutePath != "/") {
                goUpDirectory()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class BrowserAdapter : RecyclerView.Adapter<BrowserAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.tv_item_icon)
            val name: TextView = view.findViewById(R.id.tv_item_name)
            val sub: TextView = view.findViewById(R.id.tv_item_sub)
            val hint: TextView = view.findViewById(R.id.tv_item_action_hint)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_browser, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (val item = browserItems[position]) {
                is BrowserItem.DirectoryItem -> {
                    holder.icon.text = "📁"
                    holder.name.text = item.file.name
                    holder.sub.text = "${item.childCount} items"
                    holder.hint.text = "OPEN ▶"
                    holder.hint.setTextColor(ContextCompat.getColor(this@FileBrowserActivity, R.color.accent_blue))

                    holder.itemView.setOnClickListener {
                        loadDirectory(item.file)
                    }
                }
                is BrowserItem.GameFileItem -> {
                    val isNes = item.file.name.lowercase().endsWith(".nes") ||
                                (item.zipRomTitle != null && item.zipRomTitle.lowercase().endsWith(".nes"))
                    holder.icon.text = if (isNes) "🕹️" else "🎮"
                    holder.name.text = item.file.name
                    val console = if (isNes) "NES" else "GBA"
                    val type = if (item.isZip) "ZIP ($console)" else "$console ROM"
                    holder.sub.text = "$type • ${String.format("%.1f MB", item.sizeMb)}"
                    holder.hint.text = "PLAY ▶"
                    holder.hint.setTextColor(ContextCompat.getColor(this@FileBrowserActivity, R.color.accent_green))

                    holder.itemView.setOnClickListener {
                        launchGameDirectly(item)
                    }
                }
            }

            // TV focus scaling
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(100).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }
        }

        override fun getItemCount(): Int = browserItems.size
    }
}
