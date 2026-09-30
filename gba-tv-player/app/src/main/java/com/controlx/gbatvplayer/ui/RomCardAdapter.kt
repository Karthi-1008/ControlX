package com.controlx.gbatvplayer.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.controlx.gbatvplayer.R
import com.controlx.gbatvplayer.rom.ConsoleType
import com.controlx.gbatvplayer.rom.RomInfo

class RomCardAdapter(
    private var romList: List<RomInfo>,
    private val onRomSelected: (RomInfo) -> Unit
) : RecyclerView.Adapter<RomCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.card_title)
        val details: TextView = view.findViewById(R.id.card_details)
        val code: TextView = view.findViewById(R.id.card_game_code)
        val icon: TextView = view.findViewById(R.id.card_icon_symbol)
    }

    fun updateList(newList: List<RomInfo>) {
        romList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rom_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rom = romList[position]
        holder.title.text = rom.title

        val isNes = (rom.consoleType == ConsoleType.NES)
        val consoleTag = if (isNes) "NES" else "GBA"
        val type = if (rom.isZip) "ZIP ($consoleTag)" else consoleTag
        val sizeFormatted = String.format("%.1f MB", rom.fileSizeMb)

        holder.details.text = "$type • $sizeFormatted"
        holder.code.text = rom.gameCode

        if (isNes) {
            holder.code.setTextColor(Color.parseColor("#E53935")) // NES Red accent
            holder.icon.text = "🕹️"
        } else {
            holder.code.setTextColor(Color.parseColor("#818CF8")) // GBA Indigo accent
            holder.icon.text = "🎮"
        }

        holder.itemView.setOnClickListener {
            onRomSelected(rom)
        }

        // TV focus scale animation
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
    }

    override fun getItemCount(): Int = romList.size
}
