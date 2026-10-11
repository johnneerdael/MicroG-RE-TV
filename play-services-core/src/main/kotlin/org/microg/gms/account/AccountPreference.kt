package org.microg.gms.account

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.gms.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.listitem.SwipeableListItem

class AccountPreference(context: Context) : Preference(context) {

    var accountAvatar: Drawable? = null
        set(value) {
            field = value; notifyChanged()
        }

    var position: Int = 0
    var itemCount: Int = 0
    var onRemoveListener: (() -> Unit)? = null

    init {
        layoutResource = R.layout.account_item_list
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val layout = holder.itemView as? ListItemLayout ?: return
        val card = holder.findViewById(R.id.account_list_card) as? ListItemCardView
        val avatarView = holder.findViewById(R.id.account_avatar) as? ShapeableImageView
        val nameView = holder.findViewById(R.id.account_name) as? TextView
        val emailView = holder.findViewById(R.id.account_email) as? TextView
        val actionRemove = holder.findViewById(R.id.account_remove) as? MaterialButton

        layout.updateAppearance(position, itemCount)

        nameView?.text = title
        emailView?.text = summary
        avatarView?.setImageDrawable(accountAvatar)

        // The remove action is only reachable while revealed, so D-pad focus never lands on a
        // hidden button. Without a touchscreen (Android TV) there is no swipe: OK on the card
        // reveals the action and moves focus onto it, moving focus away hides it again.
        actionRemove?.isFocusable = layout.swipeState != SwipeableListItem.STATE_CLOSED

        card?.setOnClickListener {
            val open = layout.swipeState == SwipeableListItem.STATE_CLOSED
            layout.swipeState = if (open) SwipeableListItem.STATE_OPEN else SwipeableListItem.STATE_CLOSED
            actionRemove?.isFocusable = open
            if (open && !it.isInTouchMode) actionRemove?.requestFocus()
        }

        actionRemove?.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus && !view.isInTouchMode) {
                layout.swipeState = SwipeableListItem.STATE_CLOSED
                view.isFocusable = false
            }
        }

        actionRemove?.setOnClickListener {
            onRemoveListener?.invoke()
            layout.swipeState = SwipeableListItem.STATE_CLOSED
            // Hand focus back to the card first, or Android moves it to the toolbar's back button
            if (it.hasFocus()) card?.requestFocus()
            it.isFocusable = false
        }
    }
}
