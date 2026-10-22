package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.user.BUserInfo
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.auth.SecureSessionStore
import top.niunaijun.blackboxa.databinding.FragmentUsersBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class UsersFragment : Fragment() {

    private val viewBinding: FragmentUsersBinding by inflate()
    private val userList = mutableListOf<BUserInfo>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val licenseTicker = object : Runnable {
        override fun run() {
            updateLicenseCountdown()
            mainHandler.postDelayed(this, LICENSE_REFRESH_MS)
        }
    }
    private lateinit var userAdapter: UserAdapter

    var currentUserId: Int = 0
        set(value) {
            field = value
            if (isAdded && this::userAdapter.isInitialized) {
                userAdapter.notifyDataSetChanged()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userAdapter = UserAdapter()
        viewBinding.rvUsers.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.rvUsers.adapter = userAdapter

        viewBinding.btnCreateUser.setOnClickListener {
            createNewUser()
        }
        viewBinding.btnSupport.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+EkOLO9E8Oc45YTdl")))
            } catch (_: Exception) {
                toast("Unable to open support")
            }
        }

        loadUsers()
    }

    override fun onResume() {
        super.onResume()
        loadUsers()
        mainHandler.removeCallbacks(licenseTicker)
        licenseTicker.run()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(licenseTicker)
        super.onPause()
    }

    fun loadUsers() {
        val users = try {
            BlackBoxCore.get().users ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        userList.clear()
        userList.addAll(users)
        if (this::userAdapter.isInitialized) {
            userAdapter.notifyDataSetChanged()
        }
    }

    private fun updateLicenseCountdown() {
        val expiryMillis = SecureSessionStore.licenseExpiryMillis(requireContext())
        if (expiryMillis == null) {
            viewBinding.tvLicenseStatus.text = "●  Active"
            setLicenseMetrics("--", "--", "--", "--")
            viewBinding.tvLicenseExpiry.text = "Expires on\nReconnect while online to refresh license details."
            return
        }

        val remaining = expiryMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            viewBinding.tvLicenseStatus.text = "●  Expired"
            setLicenseMetrics("00", "00", "00", "00")
            viewBinding.tvLicenseExpiry.text = "Expired on\n${formatExpiry(expiryMillis)}"
            return
        }

        val days = TimeUnit.MILLISECONDS.toDays(remaining)
        val hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        viewBinding.tvLicenseStatus.text = "●  Active"
        setLicenseMetrics(days.toString(), "%02d".format(hours), "%02d".format(minutes), "%02d".format(seconds))
        viewBinding.tvLicenseExpiry.text = "Expires on\n${formatExpiry(expiryMillis)}"
    }

    private fun setLicenseMetrics(days: String, hours: String, minutes: String, seconds: String) {
        viewBinding.tvLicenseDays.text = days
        viewBinding.tvLicenseHours.text = hours
        viewBinding.tvLicenseMinutes.text = minutes
        viewBinding.tvLicenseSeconds.text = seconds
    }

    private fun formatExpiry(expiryMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expiryMillis))

    private fun createNewUser() {
        try {
            val nextUserId = userList.size
            val info = BlackBoxCore.get().createUser(nextUserId)
            if (info != null) {
                toast("Created User ${info.id}")
                loadUsers()
                (activity as? MainActivity)?.setCurrentUser(info.id)
            } else {
                toast("Failed to create user")
            }
        } catch (t: Throwable) {
            toast("Error: ${t.message}")
        }
    }

    inner class UserAdapter : RecyclerView.Adapter<UserAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(R.id.tv_user_title)
            val tvSubtitle: TextView = itemView.findViewById(R.id.tv_user_subtitle)
            val avatar: ImageView = itemView.findViewById(R.id.iv_user_avatar)
            val statusDot: View = itemView.findViewById(R.id.v_user_status)
            val btnSwitch: ImageView = itemView.findViewById(R.id.btn_switch_user)
            val btnRename: ImageView = itemView.findViewById(R.id.btn_rename_user)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_user_card, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = userList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = userList[position]
            var remark = AppManager.mRemarkSharedPreferences.getString("Remark${user.id}", "User ${user.id}")
            if (remark.isNullOrEmpty()) {
                remark = "User ${user.id}"
            }

            holder.tvTitle.text = remark

            val isSelected = (user.id == currentUserId)
            holder.itemView.setBackgroundResource(
                if (isSelected) R.drawable.bg_user_card_active else R.drawable.bg_user_card_inactive
            )
            holder.avatar.imageTintList = ColorStateList.valueOf(
                Color.parseColor(if (isSelected) "#55B7FF" else "#8EA2C6")
            )
            holder.statusDot.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (isSelected) "#46DEA2" else "#8092B4")
            )
            if (isSelected) {
                holder.tvSubtitle.text = "Active"
                holder.tvSubtitle.setTextColor(Color.parseColor("#00E676"))
            } else {
                holder.tvSubtitle.text = "Inactive"
                holder.tvSubtitle.setTextColor(Color.parseColor("#8EA2B9"))
            }

            holder.itemView.setOnClickListener {
                switchUser(user)
            }
            holder.btnSwitch.setOnClickListener { switchUser(user) }

            holder.btnRename.setOnClickListener {
                showUserOptions(holder.btnRename, user, remark)
            }
        }
    }

    private fun switchUser(user: BUserInfo) {
        (activity as? MainActivity)?.setCurrentUser(user.id)
        userAdapter.notifyDataSetChanged()
        toast("Switched to User ${user.id}")
    }

    private fun showUserOptions(anchor: View, user: BUserInfo, remark: String) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Rename")
            if (user.id > 0) menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Rename" -> renameUser(user, remark)
                    "Delete" -> confirmDelete(user)
                }
                true
            }
            show()
        }
    }

    private fun renameUser(user: BUserInfo, remark: String) {
        MaterialDialog(requireContext()).show {
            title(text = "Rename User")
            input(hint = "Enter user remark name", prefill = remark) { _, input ->
                AppManager.mRemarkSharedPreferences.edit { putString("Remark${user.id}", input.toString()) }
                userAdapter.notifyDataSetChanged()
                (activity as? MainActivity)?.updateHeader()
            }
            positiveButton(R.string.done)
            negativeButton(R.string.cancel)
        }
    }

    private fun confirmDelete(user: BUserInfo) {
        MaterialDialog(requireContext()).show {
            title(text = "Delete User")
            message(text = "Are you sure you want to delete User ${user.id} and all its data?")
            positiveButton(R.string.done) {
                try {
                    BlackBoxCore.get().deleteUser(user.id)
                    loadUsers()
                    if (currentUserId == user.id) (activity as? MainActivity)?.setCurrentUser(0)
                    toast("User ${user.id} deleted")
                } catch (_: Throwable) {
                    toast("Failed to delete user")
                }
            }
            negativeButton(R.string.cancel)
        }
    }

    private companion object {
        const val LICENSE_REFRESH_MS = 1_000L
    }
}
