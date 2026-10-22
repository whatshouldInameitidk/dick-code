package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.user.BUserInfo
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.apps.AppsFragment
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingFragment

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var homeFragment: HomeFragment
    private lateinit var appsFragment: AppsFragment
    private lateinit var usersFragment: UsersFragment
    private lateinit var settingFragment: SettingFragment

    private var activeFragment: Fragment? = null
    var currentUser: Int = 0
        private set
    private var currentTabIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initFragments(savedInstanceState)
        initBottomNav()
        initFab()
    }

    override fun onResume() {
        super.onResume()
        top.niunaijun.blackboxa.util.AutoCrashReporter.checkAndSendPending(this)
    }

    private fun initFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            homeFragment = HomeFragment.newInstance(currentUser)
            appsFragment = AppsFragment.newInstance(currentUser)
            usersFragment = UsersFragment().apply { currentUserId = currentUser }
            settingFragment = SettingFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, homeFragment, "TAG_HOME")
                .add(R.id.fragment_container, appsFragment, "TAG_APPS").hide(appsFragment)
                .add(R.id.fragment_container, usersFragment, "TAG_USERS").hide(usersFragment)
                .add(R.id.fragment_container, settingFragment, "TAG_SETTING").hide(settingFragment)
                .commit()

            activeFragment = homeFragment
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag("TAG_HOME") as? HomeFragment
                ?: HomeFragment.newInstance(currentUser)
            appsFragment = supportFragmentManager.findFragmentByTag("TAG_APPS") as? AppsFragment
                ?: AppsFragment.newInstance(currentUser)
            usersFragment = supportFragmentManager.findFragmentByTag("TAG_USERS") as? UsersFragment
                ?: UsersFragment().apply { currentUserId = currentUser }
            settingFragment = supportFragmentManager.findFragmentByTag("TAG_SETTING") as? SettingFragment
                ?: SettingFragment()

            activeFragment = when (currentTabIndex) {
                1 -> appsFragment
                2 -> usersFragment
                3 -> settingFragment
                else -> homeFragment
            }
        }
    }

    private fun initBottomNav() {
        viewBinding.tabHome.setOnClickListener { switchToTab(0) }
        viewBinding.tabApps.setOnClickListener { switchToTab(1) }
        viewBinding.tabUsers.setOnClickListener { switchToTab(2) }
        viewBinding.tabSetting.setOnClickListener { switchToTab(3) }
        updateTabStyles(0)
    }

    private fun initFab() {
        viewBinding.fabAddApp.setOnClickListener {
            val intent = Intent(this, ListActivity::class.java)
            intent.putExtra("userID", currentUser)
            apkPathResult.launch(intent)
        }
    }

    fun switchToTab(tabIndex: Int) {
        currentTabIndex = tabIndex
        val targetFragment: Fragment = when (tabIndex) {
            0 -> homeFragment
            1 -> appsFragment
            2 -> usersFragment
            3 -> settingFragment
            else -> homeFragment
        }

        if (activeFragment != targetFragment) {
            val transaction = supportFragmentManager.beginTransaction()
            if (activeFragment != null) {
                transaction.hide(activeFragment!!)
            }
            transaction.show(targetFragment).commit()
            activeFragment = targetFragment
        }

        updateTabStyles(tabIndex)

        // Show FAB only in Apps tab
        if (tabIndex == 1) {
            viewBinding.fabAddApp.visibility = View.VISIBLE
        } else {
            viewBinding.fabAddApp.visibility = View.GONE
        }

        if (tabIndex == 0) {
            homeFragment.updateUI()
        } else if (tabIndex == 2) {
            usersFragment.loadUsers()
        }
    }

    private fun updateTabStyles(selectedIndex: Int) {
        val tabs = listOf(
            Triple(viewBinding.tabHome, viewBinding.ivTabHome, viewBinding.tvTabHome),
            Triple(viewBinding.tabApps, viewBinding.ivTabApps, viewBinding.tvTabApps),
            Triple(viewBinding.tabUsers, viewBinding.ivTabUsers, viewBinding.tvTabUsers),
            Triple(viewBinding.tabSetting, viewBinding.ivTabSetting, viewBinding.tvTabSetting)
        )

        val activeColor = Color.parseColor("#2F80ED")
        val inactiveColor = Color.parseColor("#7E8B9B")

        tabs.forEachIndexed { index, (tabLayout, iconView, textView) ->
            if (index == selectedIndex) {
                tabLayout.setBackgroundResource(R.drawable.bg_nav_active)
                iconView.imageTintList = ColorStateList.valueOf(activeColor)
                textView.setTextColor(activeColor)
                textView.typeface = Typeface.DEFAULT_BOLD
            } else {
                tabLayout.background = null
                iconView.imageTintList = ColorStateList.valueOf(inactiveColor)
                textView.setTextColor(inactiveColor)
                textView.typeface = Typeface.DEFAULT
            }
        }
    }

    fun setCurrentUser(userId: Int) {
        currentUser = userId
        homeFragment.currentUserId = userId
        usersFragment.currentUserId = userId

        // Recreate appsFragment for new user ID
        supportFragmentManager.beginTransaction().remove(appsFragment).commitAllowingStateLoss()
        appsFragment = AppsFragment.newInstance(currentUser)
        supportFragmentManager.beginTransaction().add(R.id.fragment_container, appsFragment, "TAG_APPS").commitAllowingStateLoss()

        if (currentTabIndex != 1) {
            supportFragmentManager.beginTransaction().hide(appsFragment).commitAllowingStateLoss()
        }

        updateHeader()
    }

    fun updateHeader() {
        homeFragment.updateUI()
        usersFragment.loadUsers()
    }

    fun showFloatButton(show: Boolean) {
        if (currentTabIndex == 1) {
            viewBinding.fabAddApp.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    fun scanUser() {
        usersFragment.loadUsers()
    }

    fun containerUsers(): List<BUserInfo> {
        return try {
            BlackBoxCore.get().users ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private val apkPathResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                it.data?.let { data ->
                    val userId = data.getIntExtra("userID", currentUser)
                    val source = data.getStringExtra("source")
                    if (source != null) {
                        appsFragment.installApk(source)
                    }
                }
            }
        }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }
}
