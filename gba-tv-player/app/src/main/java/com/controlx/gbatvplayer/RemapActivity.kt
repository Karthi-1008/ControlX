package com.controlx.gbatvplayer

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.controlx.gbatvplayer.databinding.ActivityRemapBinding
import com.controlx.gbatvplayer.input.InputManager

class RemapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemapBinding
    private lateinit var inputManager: InputManager

    data class ActionItem(val prefKey: String, val label: String)

    private val actions = listOf(
        ActionItem(InputManager.ACTION_GBA_A, "GBA Button A"),
        ActionItem(InputManager.ACTION_GBA_B, "GBA Button B"),
        ActionItem(InputManager.ACTION_GBA_L, "GBA Button L (Shoulder)"),
        ActionItem(InputManager.ACTION_GBA_R, "GBA Button R (Shoulder)"),
        ActionItem(InputManager.ACTION_GBA_START, "GBA Button Start"),
        ActionItem(InputManager.ACTION_GBA_SELECT, "GBA Button Select"),
        ActionItem(InputManager.ACTION_GBA_UP, "D-Pad Up"),
        ActionItem(InputManager.ACTION_GBA_DOWN, "D-Pad Down"),
        ActionItem(InputManager.ACTION_GBA_LEFT, "D-Pad Left"),
        ActionItem(InputManager.ACTION_GBA_RIGHT, "D-Pad Right")
    )

    private var awaitingAction: ActionItem? = null
    private var listeningDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inputManager = InputManager(this)

        binding.recyclerRemap.layoutManager = LinearLayoutManager(this)
        binding.recyclerRemap.adapter = RemapAdapter()

        binding.btnResetDefaults.setOnClickListener {
            inputManager.resetMappings()
            binding.recyclerRemap.adapter?.notifyDataSetChanged()
            Toast.makeText(this, "Mappings reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListeningForButton(action: ActionItem) {
        awaitingAction = action
        listeningDialog = AlertDialog.Builder(this)
            .setTitle("Mapping: ${action.label}")
            .setMessage("Press any button on your gamepad or TV remote to assign...")
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                awaitingAction = null
            }
            .setOnDismissListener {
                awaitingAction = null
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val action = awaitingAction
        if (action != null && listeningDialog?.isShowing == true) {
            // Ignore system navigation buttons during remap dialog unless intended
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                inputManager.saveMapping(action.prefKey, keyCode)
                listeningDialog?.dismiss()
                awaitingAction = null
                binding.recyclerRemap.adapter?.notifyDataSetChanged()
                Toast.makeText(this, "${action.label} mapped to ${KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class RemapAdapter : RecyclerView.Adapter<RemapAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val actionName: TextView = view.findViewById(R.id.tv_action_name)
            val keyName: TextView = view.findViewById(R.id.tv_key_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remap_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = actions[position]
            holder.actionName.text = item.label

            val prefs = getSharedPreferences("gba_input_mapping", Context.MODE_PRIVATE)
            val defaultKey = when (item.prefKey) {
                InputManager.ACTION_GBA_A -> KeyEvent.KEYCODE_BUTTON_A
                InputManager.ACTION_GBA_B -> KeyEvent.KEYCODE_BUTTON_B
                InputManager.ACTION_GBA_L -> KeyEvent.KEYCODE_BUTTON_L1
                InputManager.ACTION_GBA_R -> KeyEvent.KEYCODE_BUTTON_R1
                InputManager.ACTION_GBA_START -> KeyEvent.KEYCODE_BUTTON_START
                InputManager.ACTION_GBA_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
                InputManager.ACTION_GBA_UP -> KeyEvent.KEYCODE_DPAD_UP
                InputManager.ACTION_GBA_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                InputManager.ACTION_GBA_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                InputManager.ACTION_GBA_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> 0
            }

            val currentCode = prefs.getInt(item.prefKey, defaultKey)
            holder.keyName.text = KeyEvent.keyCodeToString(currentCode).removePrefix("KEYCODE_")

            holder.itemView.setOnClickListener {
                startListeningForButton(item)
            }
        }

        override fun getItemCount(): Int = actions.size
    }
}
