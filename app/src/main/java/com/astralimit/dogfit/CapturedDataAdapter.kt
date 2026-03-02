package com.astralimit.dogfit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CapturedDataAdapter(
    private val onClick: (CapturedSession) -> Unit
) : RecyclerView.Adapter<CapturedDataAdapter.CapturedViewHolder>() {

    private val files = mutableListOf<CapturedSession>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun submit(items: List<CapturedSession>) {
        files.clear()
        files.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CapturedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return CapturedViewHolder(view)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: CapturedViewHolder, position: Int) {
        holder.bind(files[position], sdf, onClick)
    }

    class CapturedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(android.R.id.text1)
        private val subtitle: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(session: CapturedSession, sdf: SimpleDateFormat, onClick: (CapturedSession) -> Unit) {
            title.text = session.file.name
            subtitle.text = "${session.fullLabel} • ${session.file.length()} bytes • ${sdf.format(Date(session.file.lastModified()))}"
            itemView.setOnClickListener { onClick(session) }
        }
    }
}
