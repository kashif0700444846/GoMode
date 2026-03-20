package com.godmode.app.ui.terminal

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.databinding.FragmentTerminalBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var currentDir = "/data/local/tmp"
    private val outputBuffer = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        outputBuffer.append("GoMode Root Shell\nType commands below. Root access required.\n\n")
        updateOutput()
        updateDirDisplay()

        binding.btnRun.setOnClickListener { executeCurrentCommand() }

        binding.etCommand.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                executeCurrentCommand()
                true
            } else false
        }

        binding.btnClearTerminal.setOnClickListener {
            outputBuffer.clear()
            outputBuffer.append("Terminal cleared.\n\n")
            updateOutput()
        }

        binding.btnHistoryUp.setOnClickListener {
            if (commandHistory.isNotEmpty() && historyIndex < commandHistory.size - 1) {
                historyIndex++
                binding.etCommand.setText(commandHistory[commandHistory.size - 1 - historyIndex])
                binding.etCommand.setSelection(binding.etCommand.text?.length ?: 0)
            }
        }

        binding.btnHistoryDown.setOnClickListener {
            if (historyIndex > 0) {
                historyIndex--
                binding.etCommand.setText(commandHistory[commandHistory.size - 1 - historyIndex])
                binding.etCommand.setSelection(binding.etCommand.text?.length ?: 0)
            } else {
                historyIndex = -1
                binding.etCommand.setText("")
            }
        }
    }

    private fun executeCurrentCommand() {
        val input = binding.etCommand.text?.toString()?.trim() ?: return
        if (input.isEmpty()) return

        commandHistory.add(input)
        historyIndex = -1
        binding.etCommand.setText("")

        appendToOutput("# $input\n")

        if (input == "clear" || input == "cls") {
            outputBuffer.clear()
            updateOutput()
            return
        }

        if (input.startsWith("cd ")) {
            val target = input.removePrefix("cd ").trim()
            val newDir = if (target.startsWith("/")) target else "$currentDir/$target"
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val rootManager = (requireActivity().application as GodModeApp).rootManager
                val result = try {
                    rootManager.execRootCommand("cd $newDir && pwd").trim()
                } catch (e: Throwable) { "Error: ${e.message}" }
                withContext(Dispatchers.Main) {
                    if (result.startsWith("/") && !result.contains("No such") && !result.contains("error")) {
                        currentDir = result.lines().first()
                        updateDirDisplay()
                        appendToOutput("→ $currentDir\n\n")
                    } else {
                        appendToOutput("cd: no such file or directory: $target\n\n")
                    }
                }
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val rootManager = (requireActivity().application as GodModeApp).rootManager
            val fullCmd = "cd $currentDir && $input"
            val result = try {
                rootManager.execRootCommand(fullCmd)
            } catch (e: Throwable) { "Error: ${e.message}" }
            withContext(Dispatchers.Main) {
                if (result.isNotEmpty()) {
                    appendToOutput("$result\n")
                }
                appendToOutput("\n")
            }
        }
    }

    private fun appendToOutput(text: String) {
        outputBuffer.append(text)
        if (outputBuffer.length > 50000) {
            val trimmed = outputBuffer.substring(outputBuffer.length - 40000)
            outputBuffer.clear()
            outputBuffer.append("[...trimmed...]\n")
            outputBuffer.append(trimmed)
        }
        updateOutput()
        scrollToBottom()
    }

    private fun updateOutput() {
        binding.tvOutput.text = outputBuffer.toString()
    }

    private fun updateDirDisplay() {
        binding.tvCurrentDir.text = "root@android:$currentDir"
    }

    private fun scrollToBottom() {
        binding.scrollOutput.post {
            binding.scrollOutput.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
