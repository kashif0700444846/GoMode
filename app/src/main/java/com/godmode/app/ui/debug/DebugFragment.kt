package com.godmode.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.databinding.FragmentDebugBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DebugFragment : Fragment() {

    private var _binding: FragmentDebugBinding? = null
    private val binding get() = _binding!!
    private var debugOutput = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRunDiagnostics.setOnClickListener {
            runDiagnostics()
        }

        binding.btnCopyOutput.setOnClickListener {
            copyOutputToClipboard()
        }

        binding.btnClearOutput.setOnClickListener {
            clearOutput()
        }
        
        binding.btnFixSelinux.setOnClickListener {
            fixSelinux()
        }

        // Auto-run on load
        runDiagnostics()
    }

    private fun runDiagnostics() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnRunDiagnostics.isEnabled = false
        debugOutput.clear()

        lifecycleScope.launch {
            val diagnostics = withContext(Dispatchers.IO) {
                runAllDiagnostics()
            }

            binding.tvDebugOutput.text = diagnostics
            binding.progressBar.visibility = View.GONE
            binding.btnRunDiagnostics.isEnabled = true
        }
    }

    private suspend fun runAllDiagnostics(): String {
        val rootManager = (requireActivity().application as GodModeApp).rootManager
        val database = (requireActivity().application as GodModeApp).database

        debugOutput.append("═══════════════════════════════════════\n")
        debugOutput.append("     GOMODE DEBUG DIAGNOSTICS\n")
        debugOutput.append("═══════════════════════════════════════\n\n")

        // 1. Root Status
        debugOutput.append("1️⃣ ROOT STATUS:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val rootStatus = rootManager.getRootStatus()
            debugOutput.append("✓ Root Available: ${rootStatus.isRooted}\n")
            debugOutput.append("✓ Magisk: ${rootStatus.hasMagisk}\n")
            debugOutput.append("✓ KernelSU: ${rootStatus.hasKernelSU}\n")
            debugOutput.append("✓ Daemon Running: ${rootStatus.isDaemonRunning}\n")
        } catch (e: Exception) {
            debugOutput.append("✗ Error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 2. Daemon Process
        debugOutput.append("2️⃣ DAEMON PROCESS:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val psOutput = rootManager.execRootCommand("ps -A | grep godmode")
            if (psOutput.isNotEmpty()) {
                debugOutput.append("✓ Daemon Process Found:\n")
                debugOutput.append(psOutput)
                debugOutput.append("\n")
            } else {
                debugOutput.append("✗ Daemon NOT running\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Error checking daemon: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 3. Files & Directories
        debugOutput.append("3️⃣ FILES & DIRECTORIES:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val tmpFiles = rootManager.execRootCommand("ls -la /data/local/tmp/ 2>&1 | grep godmode")
            debugOutput.append("Temp files:\n")
            if (tmpFiles.isNotEmpty()) {
                debugOutput.append(tmpFiles)
            } else {
                debugOutput.append("✗ No GoMode files in /data/local/tmp/\n")
            }
            debugOutput.append("\n")
        } catch (e: Exception) {
            debugOutput.append("✗ Error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 4. Kernel Log
        debugOutput.append("4️⃣ KERNEL LOG:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val kernelLog = rootManager.execRootCommand("cat /data/local/tmp/gomode_kernel.log 2>&1")
            if (kernelLog.contains("No such file") || kernelLog.isEmpty()) {
                debugOutput.append("✗ Kernel log file does NOT exist\n")
                debugOutput.append("   This means hooks are NOT installed!\n")
            } else {
                val lines = kernelLog.lines()
                debugOutput.append("✓ Kernel log exists (${lines.size} lines)\n")
                debugOutput.append("Last 10 lines:\n")
                debugOutput.append(lines.takeLast(10).joinToString("\n"))
                debugOutput.append("\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Error reading kernel log: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 5. Native Libraries
        debugOutput.append("5️⃣ NATIVE LIBRARIES:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val libPath = requireContext().applicationInfo.nativeLibraryDir
            debugOutput.append("Native lib path: $libPath\n")
            val libDir = File(libPath)
            if (libDir.exists()) {
                val libFiles = libDir.listFiles()
                if (libFiles != null && libFiles.isNotEmpty()) {
                    debugOutput.append("✓ Native directory exists\n")
                    val godmodeLibs = libFiles.filter { it.name.contains("godmode") }
                    if (godmodeLibs.isNotEmpty()) {
                        debugOutput.append("✓ GoMode libraries found:\n")
                        godmodeLibs.forEach {
                            debugOutput.append("  - ${it.name} (${it.length()} bytes)\n")
                        }
                    } else {
                        debugOutput.append("✗ No GoMode .so files found!\n")
                        debugOutput.append("   All files:\n")
                        libFiles.take(5).forEach {
                            debugOutput.append("  - ${it.name}\n")
                        }
                    }
                } else {
                    debugOutput.append("✗ Native directory empty!\n")
                }
            } else {
                debugOutput.append("✗ Native library directory doesn't exist!\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 6. Database Status
        debugOutput.append("6️⃣ DATABASE STATUS:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val accessLogDao = database.accessLogDao()
            val totalLogs = accessLogDao.getAllLogs().size
            debugOutput.append("✓ Database accessible\n")
            debugOutput.append("✓ Total access logs: $totalLogs\n")
            
            if (totalLogs == 0) {
                debugOutput.append("⚠️  No logs in database!\n")
                debugOutput.append("   Possible reasons:\n")
                debugOutput.append("   - Hooks not working\n")
                debugOutput.append("   - No apps accessed data yet\n")
                debugOutput.append("   - Daemon not sending logs\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Database error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 7. SELinux Status
        debugOutput.append("7️⃣ SELINUX STATUS:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val selinux = rootManager.execRootCommand("getenforce 2>&1")
            debugOutput.append("SELinux mode: $selinux\n")
            if (selinux.contains("Enforcing")) {
                debugOutput.append("⚠️  Enforcing mode might block hooks\n")
                debugOutput.append("   Consider: su -c 'setenforce 0'\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 8. Socket Status
        debugOutput.append("8️⃣ DAEMON SOCKET:\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val socket = rootManager.execRootCommand("ls -la /data/local/tmp/godmode.sock 2>&1")
            if (socket.contains("No such file")) {
                debugOutput.append("✗ Socket file does NOT exist\n")
                debugOutput.append("   Daemon might not be running properly\n")
            } else {
                debugOutput.append("✓ Socket exists:\n")
                debugOutput.append(socket)
                debugOutput.append("\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 9. Logcat Errors
        debugOutput.append("9️⃣ RECENT ERRORS (Logcat):\n")
        debugOutput.append("─────────────────────────────────────\n")
        try {
            val logcat = rootManager.execRootCommand("logcat -d -s GoMode:E GodMode:E godmode:E *:F | tail -20")
            if (logcat.isNotEmpty()) {
                debugOutput.append(logcat)
                debugOutput.append("\n")
            } else {
                debugOutput.append("✓ No critical errors in logcat\n")
            }
        } catch (e: Exception) {
            debugOutput.append("✗ Error: ${e.message}\n")
        }
        debugOutput.append("\n")

        // 10. Summary
        debugOutput.append("🔍 DIAGNOSIS SUMMARY:\n")
        debugOutput.append("═══════════════════════════════════════\n")
        
        val hasRoot = try { rootManager.getRootStatus().isRooted } catch (e: Exception) { false }
        val hasDaemon = try { 
            rootManager.execRootCommand("ps -A | grep godmoded").isNotEmpty() 
        } catch (e: Exception) { false }
        val hasKernelLog = try { 
            !rootManager.execRootCommand("cat /data/local/tmp/gomode_kernel.log 2>&1").contains("No such file")
        } catch (e: Exception) { false }
        val hasLogs = try { database.accessLogDao().getAllLogs().isNotEmpty() } catch (e: Exception) { false }
        
        if (hasRoot && hasDaemon && hasKernelLog && hasLogs) {
            debugOutput.append("✅ EVERYTHING WORKING!\n")
        } else {
            debugOutput.append("❌ ISSUES DETECTED:\n")
            if (!hasRoot) debugOutput.append("  - Root access not available\n")
            if (!hasDaemon) debugOutput.append("  - Daemon not running\n")
            if (!hasKernelLog) debugOutput.append("  - Kernel hooks NOT installed\n")
            if (!hasLogs) debugOutput.append("  - No data being tracked\n")
        }
        
        debugOutput.append("\n")
        debugOutput.append("═══════════════════════════════════════\n")
        debugOutput.append("Copy this output and share for support\n")
        debugOutput.append("═══════════════════════════════════════\n")

        return debugOutput.toString()
    }

    private fun copyOutputToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("GoMode Debug Output", binding.tvDebugOutput.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Debug output copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun clearOutput() {
        binding.tvDebugOutput.text = "Output cleared. Tap 'Run Diagnostics' to scan again."
        debugOutput.clear()
    }
    
    private fun fixSelinux() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                val rootManager = (requireActivity().application as GodModeApp).rootManager
                try {
                    rootManager.execRootCommand("setenforce 0")
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            binding.progressBar.visibility = View.GONE
            Toast.makeText(requireContext(), 
                if (result.isEmpty() || !result.contains("Error")) 
                    "SELinux set to Permissive. Reboot and try again!" 
                else 
                    "Failed: $result", 
                Toast.LENGTH_LONG).show()
            
            // Re-run diagnostics
            runDiagnostics()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
