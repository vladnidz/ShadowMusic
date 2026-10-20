package com.shadow.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackItem(
    val file: File,
    var isSelected: Boolean = false
) {
    val title: String get() = file.nameWithoutExtension
    val sizeFormatted: String get() {
        val bytes = file.length()
        val mb = bytes.toDouble() / (1024 * 1024)
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val dateStr = sdf.format(Date(file.lastModified()))
        return String.format(Locale.US, "%.1f МБ • %s", mb, dateStr)
    }
}

class TrackAdapter(
    private val tracks: MutableList<TrackItem>,
    private val onSelectionChanged: () -> Unit,
    private val onSingleUpload: (TrackItem) -> Unit,
    private val onSingleDelete: (TrackItem) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    class TrackViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val tvTitle: TextView = view.findViewById(R.id.tvTrackTitle)
        val tvDetails: TextView = view.findViewById(R.id.tvTrackDetails)
        val btnSingleUpload: ImageButton = view.findViewById(R.id.btnSingleUpload)
        val btnSingleDelete: ImageButton = view.findViewById(R.id.btnSingleDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = tracks[position]

        holder.tvTitle.text = item.title
        holder.tvDetails.text = item.sizeFormatted

        // Отключаем listener перед установкой состояния, чтобы избежать ложных срабатываний при переиспользовании view
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = item.isSelected

        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onSelectionChanged()
        }

        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.cbSelect.isChecked = item.isSelected
            onSelectionChanged()
        }

        holder.btnSingleUpload.setOnClickListener {
            onSingleUpload(item)
        }

        holder.btnSingleDelete.setOnClickListener {
            onSingleDelete(item)
        }
    }

    override fun getItemCount(): Int = tracks.size

    fun getSelectedTracks(): List<TrackItem> = tracks.filter { it.isSelected }

    fun selectAll(select: Boolean) {
        tracks.forEach { it.isSelected = select }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun isAllSelected(): Boolean = tracks.isNotEmpty() && tracks.all { it.isSelected }
}
