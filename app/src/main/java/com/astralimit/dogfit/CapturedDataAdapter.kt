package com.astralimit.dogfit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CapturedDataAdapter(
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<CapturedDataAdapter.CapturedViewHolder>() {

    private val files = mutableListOf<File>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun submit(items: List<File>) {
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

        fun bind(file: File, sdf: SimpleDateFormat, onClick: (File) -> Unit) {
            title.text = file.name
            subtitle.text = "${file.length()} bytes • ${sdf.format(Date(file.lastModified()))}"
            itemView.setOnClickListener { onClick(file) }
        }
    }
}
