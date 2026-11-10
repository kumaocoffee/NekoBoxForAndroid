package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

class MTUPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = androidx.preference.R.attr.preferenceStyle
) : ListPreference(context, attrs, defStyle, 0) {

    init {
        setSummaryProvider {
            val v = value
            if (v.isNullOrEmpty()) "1500" else v
        }
    }

    override fun onClick() {
        showSelectionDialog()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            showCustomInputDialog()
            true
        }
    }

    private fun showSelectionDialog() {
        val presetValues = arrayOf("1280", "1500")
        val customLabel = context.getString(R.string.custom)
        val displayItems = arrayOf(
            "1280",
            "1500",
            "$customLabel..."
        )

        val currentVal = value ?: "1500"
        val selectedIndex = when (currentVal) {
            "1280" -> 0
            "1500" -> 1
            else -> 2
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title ?: "MTU")
            .setSingleChoiceItems(displayItems, selectedIndex) { dialog, which ->
                dialog.dismiss()
                if (which in 0..1) {
                    val newVal = presetValues[which]
                    if (callChangeListener(newVal)) {
                        value = newVal
                    }
                } else {
                    showCustomInputDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomInputDialog() {
        val currentVal = value ?: "1500"
        val container = FrameLayout(context)
        val paddingHorizontal = (24 * context.resources.displayMetrics.density).toInt()
        val paddingTop = (8 * context.resources.displayMetrics.density).toInt()
        container.setPadding(paddingHorizontal, paddingTop, paddingHorizontal, 0)

        val editText = EditText(context).apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            setText(currentVal)
            setSelection(text.length)
            hint = "1000 - 10000"
        }
        container.addView(editText)

        MaterialAlertDialogBuilder(context)
            .setTitle(title ?: "MTU")
            .setMessage(R.string.mtu_help)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val input = editText.text.toString().trim()
                val mtu = input.toIntOrNull()
                if (mtu != null && mtu in 1000..10000) {
                    val newVal = mtu.toString()
                    if (callChangeListener(newVal)) {
                        value = newVal
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
