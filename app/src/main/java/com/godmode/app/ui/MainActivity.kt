package com.godmode.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.daemon.RootManager
import com.godmode.app.databinding.ActivityMainBinding
import com.godmode.app.service.GodModeDaemonService
import com.godmode.app.ui.dashboard.DashboardViewModel
import com.godmode.app.ui.dashboard.DashboardViewModelFactory
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.ui.setup.SetupWizardActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GodMode_MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: DashboardViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permissions result: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if first-time setup is needed
        val prefs = getSharedPreferences("gomode_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("setup_complete", false)) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupViewModel()
        requestPermissions()
        checkRootAndInitialize()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun setupViewModel() {
        val app = application as GodModeApp
        val repository = GodModeRepository(
            this,
            app.database.appConfigDao(),
            app.database.accessLogDao(),
            app.rootManager
        )
        val factory = DashboardViewModelFactory(repository, app.rootManager)
        viewModel = ViewModelProvider(this, factory)[DashboardViewModel::class.java]
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ).forEach { perm ->
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(perm)
                }
            }
        }
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BODY_SENSORS
        ).forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(perm)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkRootAndInitialize() {
        lifecycleScope.launch {
            try {
                val rootManager = (application as GodModeApp).rootManager
                // Non-blocking: check root in background, never block UI
                val rootGranted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { rootManager.requestRoot() } catch (e: Throwable) { false }
                }
                if (rootGranted) {
                    // Silently try daemon init in background - don't block UI
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { rootManager.installDaemon() } catch (_: Throwable) {}
                        try { rootManager.startDaemon() } catch (_: Throwable) {}
                    }
                    try { startDaemonService() } catch (_: Throwable) {}
                }
                // Always refresh dashboard regardless of root result
                viewModel.refreshStatus()
            } catch (e: Throwable) {
                Log.e(TAG, "Background init failed: ${e.message}")
                viewModel.refreshStatus()
            }
        }
    }

    private fun startDaemonService() {
        val serviceIntent = Intent(this, GodModeDaemonService::class.java).apply {
            action = GodModeDaemonService.ACTION_START
        }
        startForegroundService(serviceIntent)
    }

    private fun showInstallingProgress(show: Boolean) {
        binding.progressOverlay.visibility = View.GONE // never block UI
    }

    private fun showNoRootDialog() {
        // Don't block the app - just show informational toast
        Toast.makeText(this, "Root not detected - limited functionality available", Toast.LENGTH_LONG).show()
    }

    private fun showRootDeniedDialog() {
        Toast.makeText(this, "Root access denied - grant in Magisk/KernelSU and restart", Toast.LENGTH_LONG).show()
    }
}
