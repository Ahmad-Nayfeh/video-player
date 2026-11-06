package com.ahmadnayfeh.reel

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply {
            title = getString(R.string.settings)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        val box = FrameLayout(this).apply { id = R.id.settings_box }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom); ins
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.settings_box, Prefs()).commit()
    }

    class Prefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            findPreference<androidx.preference.Preference>("theme")?.setOnPreferenceChangeListener { _, _ ->
                activity?.recreate(); true
            }
            findPreference<androidx.preference.Preference>("lock_controls")?.setOnPreferenceChangeListener { _, _ ->
                view?.post { context?.let { PlaybackService.applyWidgetCommands(it) } }; true
            }
        }
    }
}
