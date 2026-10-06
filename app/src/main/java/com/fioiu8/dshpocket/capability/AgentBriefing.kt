package com.fioiu8.dshpocket.capability

import android.content.Context
import java.io.File

/**
 * Tells the agent what it can and cannot do right now, and how the user can change that.
 *
 * `@deepseek-ai/dsh-agent-instructions` loads the user-global `$DSH_HOME/AGENTS.md` into
 * the first durable baseline message of every session (enabled by default through
 * `dsh-base`), so this needs no plugin of our own. The file lives outside the workspace
 * so the agent's own file tools cannot clobber it, and the app rewrites it on every boot.
 */
object AgentBriefing {

    private const val INSTRUCTIONS = "AGENTS.md"
    private const val CAPABILITIES = "capabilities.json"

    fun write(context: Context, dshHome: File, workspace: File) {
        val allFiles = Capabilities.allFilesAccess(context) == CapabilityState.GRANTED
        val termuxState = Capabilities.termux(context)
        val shizukuState = Capabilities.shizuku(context)
        val termuxInstalled = termuxState == CapabilityState.INSTALLED
        val shizukuInstalled = shizukuState == CapabilityState.INSTALLED

        dshHome.mkdirs()
        File(dshHome, INSTRUCTIONS).writeText(
            buildString {
                appendLine("# 运行环境（由 DSH Pocket 写入，每次启动覆盖）")
                appendLine()
                appendLine("你运行在一台 Android 手机上的内置运行时里。以下是当前环境的事实，")
                appendLine("不要假设比这更多的能力。")
                appendLine()
                appendLine("## 工作区")
                appendLine("- 工作目录：`${workspace.absolutePath}`")
                appendLine("- 文件读写应限制在这个目录内。")
                appendLine()
                appendLine("## 当前能力")
                appendLine("- 文件读写 / 搜索 / 替换：可用")
                appendLine(
                    "- 「所有文件访问」：**" +
                        (if (allFiles) "已授予，可以读写手机共享存储" else "未授予，只能读写应用私有目录") +
                        "**",
                )
                appendLine(
                    "- 执行 shell 命令：**不可用**（工具列表里能看到 bash，但本机没有可用的" +
                        "沙箱后端，任何命令都会在执行前被拒绝，没有进程会被启动）",
                )
                appendLine("- Termux：${if (termuxInstalled) "已安装，但执行桥尚未接入" else "未安装"}")
                appendLine("- Shizuku：${if (shizukuInstalled) "已安装，但执行桥尚未接入" else "未安装"}")
                appendLine()
                appendLine("## 需要更高能力时怎么办")
                appendLine("你无法自行提权，也不要反复重试已知不可用的能力。当任务确实需要时：")
                appendLine("1. 明确告诉用户你缺什么、为什么需要；")
                appendLine("2. 请用户打开 DSH Pocket → 设置 → 能力与权限：")
                appendLine("   - 要读写手机上的真实文件 → 授予「所有文件访问」（授予后工作区会切到共享存储，需重启引擎）")
                appendLine("   - 要执行命令 → 安装 Termux（提供完整 Linux 环境）或 Shizuku（提供提权执行）")
                appendLine("3. 注意：Shizuku 只提升执行身份，本身不提供运行时；Termux 才提供环境。")
            },
        )

        File(dshHome, CAPABILITIES).writeText(
            buildString {
                appendLine("{")
                appendLine("  \"workspace\": \"${workspace.absolutePath.replace("\\", "\\\\")}\",")
                appendLine("  \"allFilesAccess\": $allFiles,")
                appendLine("  \"termuxInstalled\": $termuxInstalled,")
                appendLine("  \"shizukuInstalled\": $shizukuInstalled,")
                appendLine("  \"processExecution\": false,")
                appendLine("  \"note\": \"Shell execution is intentionally unavailable in the default tier. Ask the user to install Termux or Shizuku.\"")
                appendLine("}")
            },
        )
    }
}
