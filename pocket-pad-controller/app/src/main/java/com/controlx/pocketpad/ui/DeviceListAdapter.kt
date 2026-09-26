package com.controlx.pocketpad.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.controlx.pocketpad.R

class DeviceListAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.device_name)
        val addressText: TextView = view.findViewById(R.id.device_address)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.nameText.text = device.name ?: "Unknown Device"
        holder.addressText.text = device.address
        holder.itemView.setOnClickListener {
            onDeviceSelected(device)
        }
    }

    override fun getItemCount(): Int = devices.size
}
