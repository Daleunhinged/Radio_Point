package com.radiopoint.app.map

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radiopoint.app.R
import java.util.Locale

class CustomMapsAdapter(
    private val maps: MutableList<CustomMapPackage>,
    private var activeMapPackage: CustomMapPackage?,
    private val onMapSelected: (CustomMapPackage) -> Unit,
    private val onMapDeleted: (CustomMapPackage) -> Unit
) : RecyclerView.Adapter<CustomMapsAdapter.MapViewHolder>() {

    class MapViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtFileName: TextView = view.findViewById(R.id.txtMapFileName)
        val txtFileSize: TextView = view.findViewById(R.id.txtMapFileSize)
        val txtActiveBadge: TextView = view.findViewById(R.id.txtMapActiveBadge)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteMap)
        val card: View = view.findViewById(R.id.cardMapPackage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MapViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_custom_map, parent, false)
        return MapViewHolder(view)
    }

    override fun onBindViewHolder(holder: MapViewHolder, position: Int) {
        val pkg = maps[position]
        holder.txtFileName.text = pkg.fileName

        val sizeMb = pkg.sizeBytes / (1024.0 * 1024.0)
        holder.txtFileSize.text = String.format(Locale.US, "%.1f MB • Offline Raster Package", sizeMb)

        val isActive = activeMapPackage?.fileName == pkg.fileName
        if (isActive) {
            holder.txtActiveBadge.text = "ACTIVE"
            holder.txtActiveBadge.setTextColor(0xFF10B981.toInt())
        } else {
            holder.txtActiveBadge.text = "USE"
            holder.txtActiveBadge.setTextColor(0xFF38BDF8.toInt())
        }

        holder.card.setOnClickListener {
            activeMapPackage = pkg
            notifyDataSetChanged()
            onMapSelected(pkg)
        }

        holder.btnDelete.setOnClickListener {
            val removedIndex = holder.adapterPosition
            if (removedIndex != RecyclerView.NO_POSITION && removedIndex < maps.size) {
                val removed = maps.removeAt(removedIndex)
                notifyItemRemoved(removedIndex)
                if (activeMapPackage?.fileName == removed.fileName) {
                    activeMapPackage = null
                }
                onMapDeleted(removed)
            }
        }
    }

    override fun getItemCount(): Int = maps.size
}
