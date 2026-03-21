package com.godmode.app.ui.modules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.daemon.RootManager
import com.godmode.app.databinding.FragmentModulesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModulesFragment : Fragment() {

    private var _binding: FragmentModulesBinding? = null
    private val binding get() = _binding!!
    private var currentTab = TAB_KERNELSU

    companion object {
        const val TAB_KERNELSU = 0
        const val TAB_LSPOSED = 1
        const val TAB_ZYGISK = 2
        const val TAB_XPLEX = 3
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabBar()
        loadFrameworkStatus()
        selectTab(TAB_KERNELSU)
    }

    private fun setupTabBar() {
        binding.tabKernelsu.setOnClickListener { selectTab(TAB_KERNELSU) }
        binding.tabLsposed.setOnClickListener { selectTab(TAB_LSPOSED) }
        binding.tabZygisk.setOnClickListener { selectTab(TAB_ZYGISK) }
        binding.tabXplex.setOnClickListener { selectTab(TAB_XPLEX) }
        binding.btnRefreshModules.setOnClickListener { loadCurrentTab() }

        binding.btnBootstrapRuntime.setOnClickListener {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            viewLifecycleOwner.lifecycleScope.launch {
                showLoading(true)
                val result = withContext(Dispatchers.IO) { rootManager.bootstrapGoModeRuntime() }
                showLoading(false)
                Toast.makeText(
                    requireContext(),
                    if (result.success) result.message else "Runtime setup failed: ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
                loadLSPosedTab()
            }
        }

        binding.btnRestartRuntime.setOnClickListener {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { rootManager.restartGoModeRuntime() }
                Toast.makeText(
                    requireContext(),
                    if (ok) "GoMode daemon restarted" else "Failed to restart daemon",
                    Toast.LENGTH_SHORT
                ).show()
                loadLSPosedTab()
            }
        }
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        // Update tab button states
        val tabs = listOf(binding.tabKernelsu, binding.tabLsposed, binding.tabZygisk, binding.tabXplex)
        tabs.forEachIndexed { index, btn ->
            btn.isSelected = index == tab
            btn.setTextColor(
                if (index == tab) requireContext().getColor(R.color.accent)
                else requireContext().getColor(R.color.text_hint)
            )
        }
        loadCurrentTab()
    }

    private fun loadCurrentTab() {
        when (currentTab) {
            TAB_KERNELSU -> loadKernelSUTab()
            TAB_LSPOSED -> loadLSPosedTab()
            TAB_ZYGISK -> loadZygiskTab()
            TAB_XPLEX -> loadXPLEXTab()
        }
    }

    private fun loadFrameworkStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            val status = rootManager.getRootStatus()

            binding.tvKsuVersion.text = if (status.hasKernelSU) {
                val ver = withContext(Dispatchers.IO) { rootManager.getKSUVersion() }
                "KernelSU: ${ver.ifEmpty { "Installed" }}"
            } else "KernelSU: Not found"

            binding.tvMagiskVersionHeader.text = if (status.hasMagisk) {
                val ver = withContext(Dispatchers.IO) { rootManager.getMagiskVersion() }
                "Magisk: ${ver.ifEmpty { "Installed" }}"
            } else "Magisk: Not found"

            binding.tvLsposedVersion.text = if (status.hasLSPosed) {
                val ver = withContext(Dispatchers.IO) { rootManager.getLSPosedVersion() }
                "LSPosed: ${ver.ifEmpty { "Installed" }}"
            } else "LSPosed: Not found"

            val zygiskEnabled = withContext(Dispatchers.IO) { rootManager.isZygiskEnabled() }
            binding.tvZygiskStatus.text = if (zygiskEnabled) "Zygisk: Enabled" else "Zygisk: Disabled"
        }
    }

    private fun loadKernelSUTab() {
        binding.contentKernelsu.visibility = View.VISIBLE
        binding.contentLsposed.visibility = View.GONE
        binding.contentZygisk.visibility = View.GONE
        binding.contentXplex.visibility = View.GONE

        binding.tvTabTitle.text = "Module Manager"
        binding.tvTabSubtitle.text = "Magisk / KernelSU modules installed on this device"

        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            val modules = withContext(Dispatchers.IO) { rootManager.getInstalledModules() }
            showLoading(false)

            if (modules.isEmpty()) {
                binding.tvEmptyKernelsu.visibility = View.VISIBLE
                binding.rvModules.visibility = View.GONE
            } else {
                binding.tvEmptyKernelsu.visibility = View.GONE
                binding.rvModules.visibility = View.VISIBLE
                binding.rvModules.layoutManager = LinearLayoutManager(requireContext())
                binding.rvModules.adapter = ModuleAdapter(modules) { module, action ->
                    handleModuleAction(module, action, rootManager)
                }
            }
        }
    }

    private fun loadLSPosedTab() {
        binding.contentKernelsu.visibility = View.GONE
        binding.contentLsposed.visibility = View.VISIBLE
        binding.contentZygisk.visibility = View.GONE
        binding.contentXplex.visibility = View.GONE

        binding.tvTabTitle.text = "Hook Engine"
        binding.tvTabSubtitle.text = "GoMode runtime first, LSPosed compatibility optional"

        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            val status = rootManager.getRootStatus()
            val runtime = withContext(Dispatchers.IO) { rootManager.getGoModeRuntimeStatus() }
            showLoading(false)

            binding.tvRuntimeStatus.text = buildString {
                appendLine("Runtime status: ${runtime.message}")
                appendLine("Module: ${if (runtime.modulePresent) "Installed" else "Not installed"}")
                appendLine("Enabled: ${if (runtime.moduleEnabled) "Yes" else "No"}")
                appendLine("Daemon: ${if (runtime.daemonRunning) "Running" else "Stopped"}")
                appendLine("Runtime dirs: ${if (runtime.runtimeDirReady) "Ready" else "Missing"}")
                appendLine("Scoped configs: ${runtime.configCount}")
                append("Captured logs: ${runtime.logCount}")
            }

            binding.btnBootstrapRuntime.text = if (runtime.modulePresent) {
                "Reinstall Runtime"
            } else {
                "Bootstrap Runtime"
            }

            if (!status.hasLSPosed) {
                binding.tvLsposedInfo.text = "Compatibility mode: LSPosed not detected.\n\n" +
                        "GoMode runtime can still run daemon + config/log pipeline without LSPosed.\n" +
                        "For deepest Java API hooks (per-process interception), LSPosed remains optional compatibility.\n\n" +
                        "Phase-1 decoupling is now active: bootstrap the runtime above, reboot once, then test spoof/log behavior."
                binding.tvLsposedInfo.setTextColor(requireContext().getColor(R.color.text_secondary))
            } else {
                val ver = withContext(Dispatchers.IO) { rootManager.getLSPosedVersion() }
                val modules = withContext(Dispatchers.IO) { rootManager.getInstalledModules() }
                    .filter { it.name.lowercase().contains("xposed") ||
                            it.description.lowercase().contains("xposed") ||
                            it.id.lowercase().contains("lsposed") }

                binding.tvLsposedInfo.text = "Compatibility mode: LSPosed ${ver.ifEmpty { "Installed" }}\n\n" +
                        "GoMode runtime + LSPosed can run together.\n" +
                        "Runtime handles daemon/bootstrap flow, while LSPosed currently strengthens app-process Java hooks.\n\n" +
                        "Detected LSPosed/Xposed modules: ${modules.size}"
                binding.tvLsposedInfo.setTextColor(requireContext().getColor(R.color.text_secondary))
            }

            // Show full module list for Xposed scope management
            val allModules = withContext(Dispatchers.IO) { rootManager.getInstalledModules() }
            binding.rvLsposedModules.layoutManager = LinearLayoutManager(requireContext())
            binding.rvLsposedModules.adapter = ModuleAdapter(allModules) { module, action ->
                handleModuleAction(module, action, rootManager)
            }
        }
    }

    private fun loadZygiskTab() {
        binding.contentKernelsu.visibility = View.GONE
        binding.contentLsposed.visibility = View.GONE
        binding.contentZygisk.visibility = View.VISIBLE
        binding.contentXplex.visibility = View.GONE

        binding.tvTabTitle.text = "Zygisk / NeoZygisk"
        binding.tvTabSubtitle.text = "Zygote process injection & companion modules"

        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            val zygiskEnabled = withContext(Dispatchers.IO) { rootManager.isZygiskEnabled() }
            val zygiskModules = withContext(Dispatchers.IO) { rootManager.getZygiskModules() }
            showLoading(false)

            binding.tvZygiskInfo.text = buildString {
                appendLine("Zygisk Status: ${if (zygiskEnabled) "ENABLED" else "DISABLED"}")
                appendLine()
                appendLine("About Zygisk / NeoZygisk:")
                appendLine("Zygisk runs companion code inside every app process via Zygote")
                appendLine("injection. This enables deep system-level hooks that survive app")
                appendLine("restarts without requiring per-process injection.")
                appendLine()
                if (zygiskEnabled) {
                    appendLine("Active Zygisk modules: ${zygiskModules.size}")
                    if (zygiskModules.isNotEmpty()) {
                        appendLine()
                        appendLine("Zygisk modules detected:")
                        zygiskModules.forEach { appendLine("  • ${it.name} (${it.version})") }
                    }
                } else {
                    appendLine("To enable Zygisk:")
                    appendLine("  Magisk: Settings → Enable Zygisk → Reboot")
                    appendLine("  KernelSU: Install Zygisk-Next module")
                    appendLine()
                    appendLine("GoMode's ptrace injection works without Zygisk,")
                    appendLine("but Zygisk provides deeper integration.")
                }
            }

            binding.rvZygiskModules.layoutManager = LinearLayoutManager(requireContext())
            binding.rvZygiskModules.adapter = ModuleAdapter(zygiskModules) { module, action ->
                handleModuleAction(module, action, rootManager)
            }
        }
    }

    private fun loadXPLEXTab() {
        binding.contentKernelsu.visibility = View.GONE
        binding.contentLsposed.visibility = View.GONE
        binding.contentZygisk.visibility = View.GONE
        binding.contentXplex.visibility = View.VISIBLE

        binding.tvTabTitle.text = "Privacy Engine (XPL-EX)"
        binding.tvTabSubtitle.text = "Per-app data spoofing & privacy control"

        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            val isRooted = rootManager.getRootStatus().isRooted
            showLoading(false)

            binding.tvXplexInfo.text = buildString {
                appendLine("GoMode Privacy Engine")
                appendLine("Inspired by XPL-EX (0bbedCode)")
                appendLine()
                appendLine("Active protections:")
                appendLine("  • IMEI / IMSI spoofing via property hooks")
                appendLine("  • Android ID randomization")
                appendLine("  • GPS location override")
                appendLine("  • MAC address spoofing")
                appendLine("  • Build.FINGERPRINT override")
                appendLine("  • Network operator masking")
                appendLine("  • Advertising ID control")
                appendLine()
                appendLine("Hook mechanism: ${if (isRooted) "ptrace injection + Binder IPC hooks" else "Requires root"}")
                appendLine()
                appendLine("Configure per-app privacy controls in the Apps tab.")
                appendLine("Each app can have independent spoofed identities.")
            }

            // Load system property status
            val imei = withContext(Dispatchers.IO) {
                try { rootManager.execRootCommand("getprop ril.imei1 2>/dev/null").trim() }
                catch (e: Throwable) { "" }
            }
            val androidId = withContext(Dispatchers.IO) {
                try { rootManager.execRootCommand("settings get secure android_id").trim() }
                catch (e: Throwable) { "" }
            }
            val serial = withContext(Dispatchers.IO) {
                try { rootManager.execRootCommand("getprop ro.serialno").trim() }
                catch (e: Throwable) { "" }
            }
            val model = withContext(Dispatchers.IO) {
                try { rootManager.execRootCommand("getprop ro.product.model").trim() }
                catch (e: Throwable) { "" }
            }

            binding.tvDeviceIdentifiers.text = buildString {
                appendLine("Device Identifiers (live read):")
                appendLine("  IMEI: ${imei.ifEmpty { "N/A" }}")
                appendLine("  Android ID: ${androidId.ifEmpty { "N/A" }}")
                appendLine("  Serial: ${serial.ifEmpty { "N/A" }}")
                appendLine("  Model: ${model.ifEmpty { "N/A" }}")
            }
        }
    }

    private fun handleModuleAction(module: RootManager.Module, action: String, rootManager: RootManager) {
        when (action) {
            "toggle" -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        if (module.isEnabled) rootManager.disableModule(module.id)
                        else rootManager.enableModule(module.id)
                    }
                    if (success) {
                        val state = if (module.isEnabled) "disabled" else "enabled"
                        Toast.makeText(requireContext(), "${module.name} $state (reboot required)", Toast.LENGTH_SHORT).show()
                        loadCurrentTab()
                    } else {
                        Toast.makeText(requireContext(), "Failed to toggle module", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "delete" -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete Module")
                    .setMessage("Schedule '${module.name}' for deletion?\nThe module will be removed on next reboot.")
                    .setPositiveButton("Delete") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                rootManager.scheduleDeleteModule(module.id)
                            }
                            Toast.makeText(
                                requireContext(),
                                if (success) "${module.name} will be removed on reboot" else "Failed to delete module",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (success) loadCurrentTab()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        if (_binding == null) return
        binding.progressModules.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ===== RecyclerView Adapter =====

    class ModuleAdapter(
        private val modules: List<RootManager.Module>,
        private val onAction: (RootManager.Module, String) -> Unit
    ) : RecyclerView.Adapter<ModuleAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_module_name)
            val tvVersion: TextView = view.findViewById(R.id.tv_module_version)
            val tvAuthor: TextView = view.findViewById(R.id.tv_module_author)
            val tvDesc: TextView = view.findViewById(R.id.tv_module_description)
            val tvStatus: TextView = view.findViewById(R.id.tv_module_status)
            val btnToggle: android.widget.Button = view.findViewById(R.id.btn_toggle_module)
            val btnDelete: android.widget.Button = view.findViewById(R.id.btn_delete_module)
            val cardView: CardView = view.findViewById(R.id.card_module)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_module, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val module = modules[position]
            holder.tvName.text = module.name
            holder.tvVersion.text = "v${module.version}"
            holder.tvAuthor.text = "by ${module.author}"
            holder.tvDesc.text = module.description.ifEmpty { "No description" }

            val ctx = holder.itemView.context
            if (module.isEnabled) {
                holder.tvStatus.text = if (module.isZygisk) "Zygisk • Enabled" else "Enabled"
                holder.tvStatus.setTextColor(ctx.getColor(R.color.green_success))
                holder.cardView.setCardBackgroundColor(ctx.getColor(R.color.bg_card))
            } else {
                holder.tvStatus.text = "Disabled"
                holder.tvStatus.setTextColor(ctx.getColor(R.color.text_hint))
                holder.cardView.setCardBackgroundColor(ctx.getColor(R.color.bg_dark))
            }

            holder.btnToggle.text = if (module.isEnabled) "Disable" else "Enable"
            holder.btnToggle.setOnClickListener { onAction(module, "toggle") }
            holder.btnDelete.setOnClickListener { onAction(module, "delete") }
        }

        override fun getItemCount() = modules.size
    }
}
