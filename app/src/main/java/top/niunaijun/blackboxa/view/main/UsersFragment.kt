package top.niunaijun.blackboxa.view.main

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import top.niunaijun.blackboxa.databinding.FragmentUsersBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast

class UsersFragment : Fragment() {

    private val viewBinding: FragmentUsersBinding by inflate()
    private val userList = mutableListOf<BUserInfo>()
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

        loadUsers()
    }

    override fun onResume() {
        super.onResume()
        loadUsers()
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
            val btnRename: ImageView = itemView.findViewById(R.id.btn_rename_user)
            val btnDelete: ImageView = itemView.findViewById(R.id.btn_delete_user)
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
            if (isSelected) {
                holder.tvSubtitle.text = "User ID: ${user.id} · Active"
                holder.tvSubtitle.setTextColor(Color.parseColor("#00E676"))
            } else {
                holder.tvSubtitle.text = "User ID: ${user.id} · Tap to switch"
                holder.tvSubtitle.setTextColor(Color.parseColor("#8EA2B9"))
            }

            holder.itemView.setOnClickListener {
                (activity as? MainActivity)?.setCurrentUser(user.id)
                notifyDataSetChanged()
                toast("Switched to User ${user.id}")
            }

            holder.btnRename.setOnClickListener {
                MaterialDialog(requireContext()).show {
                    title(text = "Rename User")
                    input(hint = "Enter user remark name", prefill = remark) { _, input ->
                        AppManager.mRemarkSharedPreferences.edit {
                            putString("Remark${user.id}", input.toString())
                        }
                        notifyItemChanged(position)
                        (activity as? MainActivity)?.updateHeader()
                    }
                    positiveButton(R.string.done)
                    negativeButton(R.string.cancel)
                }
            }

            if (user.id > 0) {
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnDelete.setOnClickListener {
                    MaterialDialog(requireContext()).show {
                        title(text = "Delete User")
                        message(text = "Are you sure you want to delete User ${user.id} and all its data?")
                        positiveButton(R.string.done) {
                            try {
                                BlackBoxCore.get().deleteUser(user.id)
                                loadUsers()
                                if (currentUserId == user.id) {
                                    (activity as? MainActivity)?.setCurrentUser(0)
                                }
                                toast("User ${user.id} deleted")
                            } catch (t: Throwable) {
                                toast("Failed to delete user")
                            }
                        }
                        negativeButton(R.string.cancel)
                    }
                }
            } else {
                holder.btnDelete.visibility = View.GONE
            }
        }
    }
}
