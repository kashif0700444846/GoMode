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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkRootAndInitialize() {
        lifecycleScope.launch {
            val rootManager = (application as GodModeApp).rootManager
            val rootStatus = rootManager.getRootStatus()

            if (!rootStatus.isRooted) {
                showNoRootDialog()
                return@launch
            }

            // Request root permission
            val rootGranted = rootManager.requestRoot()
            if (!rootGranted) {
                showRootDeniedDialog()
                return@launch
            }

            // Install daemon if not already installed
            if (!rootStatus.isDaemonRunning) {
                showInstallingProgress(true)
                val installResult = rootManager.installDaemon()
                if (installResult.success) {
                    val started = rootManager.startDaemon()
                    if (started) {
                        startDaemonService()
                        Toast.makeText(this@MainActivity,
                            "GodMode daemon started successfully", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity,
                        "Failed to install daemon: ${installResult.message}",
                        Toast.LENGTH_LONG).show()
                }
                showInstallingProgress(false)
            } else {
                startDaemonService()
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
        binding.progressOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showNoRootDialog() {
        AlertDialog.Builder(this)
            .setTitle("Root Required")
            .setMessage("GodMode requires root access to function. Your device does not appear to be rooted.\n\nPlease root your device using Magisk or KernelSU and try again.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showRootDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Root Access Denied")
            .setMessage("GodMode needs root (su) access to monitor and protect your privacy. Please grant root access when prompted.")
            .setPositiveButton("Try Again") { _, _ -> checkRootAndInitialize() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
