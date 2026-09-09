package com.radiopoint.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radiopoint.app.R
import com.radiopoint.app.gis.RoadDefinition
import java.util.Locale

class RoadSelectionAdapter(
    private val roads: List<RoadDefinition>,
    private val onRoadSelected: (RoadDefinition) -> Unit
) : RecyclerView.Adapter<RoadSelectionAdapter.RoadViewHolder>() {

    class RoadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtRoadName: TextView = view.findViewById(R.id.txtRoadName)
        val txtRoadChannel: TextView = view.findViewById(R.id.txtRoadChannel)
        val txtRoadDetails: TextView = view.findViewById(R.id.txtRoadDetails)
        val card: View = view.findViewById(R.id.cardRoadItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoadViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_road, parent, false)
        return RoadViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoadViewHolder, position: Int) {
        val road = roads[position]
        holder.txtRoadName.text = road.name
        holder.txtRoadChannel.text = "${road.defaultChannel} (${road.frequencyMhz})"
        holder.txtRoadDetails.text = String.format(
            Locale.US,
            "Length: %.1f km • Code: 0x%02X • Turnouts: %d",
            road.totalLengthKm,
            road.roadCode,
            road.initialPullouts.size
        )

        holder.card.setOnClickListener {
            onRoadSelected(road)
        }
    }

    override fun getItemCount(): Int = roads.size
}
