package com.godmode.app.ui.verification

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.databinding.FragmentKernelVerificationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KernelVerificationFragment : Fragment() {

    private var _binding: FragmentKernelVerificationBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKernelVerificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefresh.setOnClickListener {
            loadKernelLog()
        }

        binding.btnClear.setOnClickListener {
            clearKernelLog()
        }

        loadKernelLog()
    }

    private fun loadKernelLog() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvKernelLog.text = "Loading kernel verification logs..."

        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) {
                readKernelLog()
            }

            binding.progressBar.visibility = View.GONE

            if (log.isNotEmpty()) {
                binding.tvKernelLog.text = log
                binding.scrollView.post {
                    binding.scrollView.fullScroll(View.FOCUS_DOWN)
                }

                // Parse and show stats
                val lines = log.lines()
                val hookInstalls = lines.count { it.contains("HOOK_INSTALL: SUCCESS") }
                val intercepts = lines.count { it.contains("INTERCEPT:") }
                val activeChecks = lines.count { it.contains("ACTIVE:") }

                binding.tvStats.text = buildString {
                    append("✓ Hook Installations: $hookInstalls\n")
                    append("✓ Active Interceptions: $activeChecks\n")
                    append("✓ Access Events: $intercepts\n")
                    append("✓ Total Log Lines: ${lines.size}")
                }

                binding.cardStats.visibility = View.VISIBLE
            } else {
                binding.tvKernelLog.text = "No kernel logs found.\n\n" +
                        "This means either:\n" +
                        "1. Hooks haven't been triggered yet (no apps accessed data)\n" +
                        "2. Root access is not available\n" +
                        "3. Daemon is not running\n\n" +
                        "Try opening some apps and check again."
                binding.cardStats.visibility = View.GONE
            }
        }
    }

    private suspend fun readKernelLog(): String {
        val rootManager = (requireActivity().application as GodModeApp).rootManager
        return try {
            rootManager.execRootCommand("cat /data/local/tmp/gomode_kernel.log 2>/dev/null || echo ''")
        } catch (e: Exception) {
            ""
        }
    }

    private fun clearKernelLog() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val rootManager = (requireActivity().application as GodModeApp).rootManager
                try {
                    rootManager.execRootCommand("rm -f /data/local/tmp/gomode_kernel.log")
                } catch (e: Exception) {
                    // Ignore
                }
            }

            Toast.makeText(requireContext(), "Kernel log cleared", Toast.LENGTH_SHORT).show()
            binding.tvKernelLog.text = "Kernel log cleared. New events will be logged here."
            binding.cardStats.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
