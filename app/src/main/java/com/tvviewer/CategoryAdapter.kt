package com.tvviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategoryAdapter(
    private var categories: List<String>,
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    private var selectedPosition = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val categoryName: TextView = view.findViewById(R.id.categoryName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.categoryName.text = category
        holder.categoryName.isSelected = position == selectedPosition

        // Make focusable for D-pad navigation
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = false

        holder.itemView.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = holder.adapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onCategoryClick(category)
        }

        // Handle D-pad center/enter press on focused item
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                holder.itemView.performClick()
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount() = categories.size

    fun updateCategories(newCategories: List<String>, selectedCategory: String? = null) {
        categories = newCategories
        selectedPosition = if (selectedCategory != null) {
            newCategories.indexOf(selectedCategory).coerceAtLeast(0)
        } else {
            0
        }
        notifyDataSetChanged()
    }
}
