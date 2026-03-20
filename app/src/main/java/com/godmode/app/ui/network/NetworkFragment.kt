package com.godmode.app.ui.network

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.databinding.FragmentNetworkBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkFragment : Fragment() {

    private var _binding: FragmentNetworkBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        loadNetworkInfo()
    }

    private fun setupControls() {
        val rm = (requireActivity().application as GodModeApp).rootManager

        // WiFi toggle
        binding.switchWifi.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { rm.setWifiEnabled(checked) }
                if (!ok) Toast.makeText(requireContext(), "Failed - grant root", Toast.LENGTH_SHORT).show()
            }
        }

        // Mobile Data toggle
        binding.switchMobileData.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { rm.setMobileDataEnabled(checked) }
            }
        }

        // Hotspot toggle
        binding.switchHotspot.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { rm.setHotspotEnabled(checked) }
                Toast.makeText(requireContext(), if (checked) "Hotspot enabling..." else "Hotspot disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Bluetooth toggle
        binding.switchBluetooth.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { rm.setBluetoothEnabled(checked) }
            }
        }

        // Airplane Mode
        binding.switchAirplaneMode.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { rm.setAirplaneMode(checked) }
                Toast.makeText(requireContext(), if (checked) "Airplane mode ON" else "Airplane mode OFF", Toast.LENGTH_SHORT).show()
            }
        }

        // Scan WiFi networks
        binding.btnScanWifi.setOnClickListener {
            scanWifiNetworks()
        }

        // Refresh stats
        binding.btnRefreshNetwork.setOnClickListener {
            loadNetworkInfo()
        }

        // DNS settings
        binding.btnSetDns.setOnClickListener {
            showDnsDialog()
        }

        // Hosts file editor
        binding.btnEditHosts.setOnClickListener {
            showHostsEditor()
        }

        // iptables status
        binding.btnIptables.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val rules = withContext(Dispatchers.IO) {
                    rm.execRootCommand("iptables -L -v -n 2>/dev/null | head -50")
                }
                showInfoDialog("iptables Firewall Rules", rules.ifEmpty { "No rules or root not granted" })
            }
        }

        // Network connections
        binding.btnConnections.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val conn = withContext(Dispatchers.IO) {
                    rm.execRootCommand("ss -tunap 2>/dev/null | head -40")
                }
                showInfoDialog("Active Connections", conn.ifEmpty { "No data" })
            }
        }

        // Traffic stats
        binding.btnTrafficStats.setOnClickListener {
            loadTrafficStats()
        }

        // Build props editor
        binding.btnBuildProps.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val props = withContext(Dispatchers.IO) {
                    rm.execRootCommand("getprop ro.product.model; getprop ro.product.brand; getprop ro.build.version.release; getprop ro.serialno; getprop ro.build.fingerprint")
                }
                showInfoDialog("System Properties", props)
            }
        }

        // resetprop
        binding.btnResetprop.setOnClickListener {
            showSetpropDialog()
        }
    }

    private fun loadNetworkInfo() {
        val rm = (requireActivity().application as GodModeApp).rootManager
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressNetwork.visibility = View.VISIBLE

            val wifiInfo = withContext(Dispatchers.IO) {
                rm.execRootCommand("dumpsys wifi 2>/dev/null | grep -E 'SSID|signalLevel|mNetworkInfo|wifiState|ip' | head -12").trim()
            }

            val ipInfo = withContext(Dispatchers.IO) {
                rm.execRootCommand("ip addr show wlan0 2>/dev/null | grep -E 'inet |link' | head -4").trim()
            }

            val mobileInfo = withContext(Dispatchers.IO) {
                rm.execRootCommand("dumpsys telephony.registry 2>/dev/null | grep -E 'mDataConnectionState|mDataActivity|mSignalStrength|mNetworkType' | head -6").trim()
            }

            val dnsInfo = withContext(Dispatchers.IO) {
                rm.execRootCommand("getprop net.dns1; getprop net.dns2; getprop net.wlan0.dns1 2>/dev/null").trim()
            }

            binding.tvWifiDetails.text = buildString {
                appendLine("WiFi Status:")
                appendLine(wifiInfo.ifEmpty { "WiFi info unavailable" })
                appendLine()
                appendLine("IP Info:")
                appendLine(ipInfo.ifEmpty { "Not connected" })
            }

            binding.tvMobileDetails.text = buildString {
                appendLine("Mobile Data:")
                appendLine(mobileInfo.ifEmpty { "Mobile data info unavailable" })
            }

            binding.tvDnsInfo.text = "DNS: ${dnsInfo.lines().filter { it.isNotBlank() }.joinToString(" | ").ifEmpty { "Unknown" }}"

            binding.progressNetwork.visibility = View.GONE
        }
    }

    private fun scanWifiNetworks() {
        val rm = (requireActivity().application as GodModeApp).rootManager
        binding.btnScanWifi.isEnabled = false
        binding.tvWifiNetworks.text = "Scanning..."
        viewLifecycleOwner.lifecycleScope.launch {
            val networks = withContext(Dispatchers.IO) {
                rm.execRootCommand("wpa_cli -i wlan0 scan 2>/dev/null; sleep 2; wpa_cli -i wlan0 scan_results 2>/dev/null | tail -n+2 | head -20").trim()
            }
            binding.tvWifiNetworks.text = buildString {
                appendLine("Available Networks:")
                if (networks.isEmpty() || networks.startsWith("Error")) {
                    appendLine("Could not scan (try: Settings > WiFi for manual scanning)")
                } else {
                    appendLine(networks)
                }
            }
            binding.btnScanWifi.isEnabled = true
        }
    }

    private fun loadTrafficStats() {
        val rm = (requireActivity().application as GodModeApp).rootManager
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                rm.execRootCommand("cat /proc/net/xt_qtaguid/stats 2>/dev/null | head -30 || " +
                        "cat /proc/net/dev 2>/dev/null")
            }
            showInfoDialog("Network Traffic Stats", stats.ifEmpty { "Traffic stats unavailable" })
        }
    }

    private fun showDnsDialog() {
        val rm = (requireActivity().application as GodModeApp).rootManager
        val input = android.widget.EditText(requireContext()).apply {
            hint = "DNS (e.g. 1.1.1.1)"
            setText("1.1.1.1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Set Custom DNS")
            .setMessage("Set system DNS server (requires root, resets on reboot)")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val dns = input.text.toString().trim()
                if (dns.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            rm.execRootCommand("setprop net.dns1 $dns && setprop net.dns2 8.8.8.8")
                        }
                        Toast.makeText(requireContext(), "DNS set to $dns", Toast.LENGTH_SHORT).show()
                        binding.tvDnsInfo.text = "DNS: $dns | 8.8.8.8"
                    }
                }
            }
            .setNeutralButton("Use Cloudflare (1.1.1.1)") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { rm.execRootCommand("setprop net.dns1 1.1.1.1 && setprop net.dns2 1.0.0.1") }
                    Toast.makeText(requireContext(), "DNS: Cloudflare 1.1.1.1", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showHostsEditor() {
        val rm = (requireActivity().application as GodModeApp).rootManager
        viewLifecycleOwner.lifecycleScope.launch {
            val hosts = withContext(Dispatchers.IO) {
                rm.execRootCommand("cat /etc/hosts 2>/dev/null || cat /system/etc/hosts 2>/dev/null")
            }
            val input = android.widget.EditText(requireContext()).apply {
                setText(hosts)
                setPadding(24, 16, 24, 16)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 10
                typeface = android.graphics.Typeface.MONOSPACE
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Edit /etc/hosts")
                .setView(android.widget.ScrollView(requireContext()).also { it.addView(input) })
                .setPositiveButton("Save") { _, _ ->
                    val newHosts = input.text.toString()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val tmpFile = "/data/local/tmp/hosts_new"
                            rm.execRootCommand("printf '%s' '${newHosts.replace("'", "\\'")}' > $tmpFile && " +
                                    "mount -o rw,remount /system 2>/dev/null; " +
                                    "cp $tmpFile /etc/hosts && chmod 644 /etc/hosts")
                        }
                        Toast.makeText(requireContext(), "Hosts file saved", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun showSetpropDialog() {
        val rm = (requireActivity().application as GodModeApp).rootManager
        val ll = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val etProp = android.widget.EditText(requireContext()).apply { hint = "Property name (e.g. ro.product.model)" }
        val etVal = android.widget.EditText(requireContext()).apply { hint = "New value" }
        ll.addView(etProp)
        ll.addView(etVal)
        AlertDialog.Builder(requireContext())
            .setTitle("Set System Property")
            .setMessage("Applies instantly via resetprop. Survives until reboot.")
            .setView(ll)
            .setPositiveButton("Apply") { _, _ ->
                val prop = etProp.text.toString().trim()
                val value = etVal.text.toString().trim()
                if (prop.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            rm.execRootCommand("resetprop $prop \"$value\" 2>/dev/null || setprop $prop \"$value\" 2>/dev/null")
                        }
                        Toast.makeText(requireContext(), "Applied: $prop = $value", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showInfoDialog(title: String, content: String) {
        val sv = android.widget.ScrollView(requireContext())
        val tv = android.widget.TextView(requireContext()).apply {
            text = content.ifEmpty { "No data" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 16, 32, 16)
            setTextColor(requireContext().getColor(com.godmode.app.R.color.text_secondary))
        }
        sv.addView(tv)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
