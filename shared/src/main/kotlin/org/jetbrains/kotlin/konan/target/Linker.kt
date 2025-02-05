/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.konan.target

import java.lang.ProcessBuilder
import java.lang.ProcessBuilder.Redirect
import org.jetbrains.kotlin.konan.exec.Command
import org.jetbrains.kotlin.konan.file.*

typealias ObjectFile = String
typealias ExecutableFile = String

enum class LinkerOutputKind {
    DYNAMIC_LIBRARY,
    STATIC_LIBRARY,
    EXECUTABLE
}

// Here we take somewhat unexpected approach - we create the thin
// library, and then repack it during post-link phase.
// This way we ensure .a inputs are properly processed.
private fun staticGnuArCommands(ar: String, executable: ExecutableFile,
                                objectFiles: List<ObjectFile>, libraries: List<String>) = when {
        HostManager.hostIsMingw -> {
            val temp = executable.replace('/', '\\') + "__"
            val arWindows = ar.replace('/', '\\')
            listOf(
                    Command(arWindows, "-rucT", temp).apply {
                        +objectFiles
                        +libraries
                    },
                    Command("cmd", "/c").apply {
                        +"(echo create $executable & echo addlib ${temp} & echo save & echo end) | $arWindows -M"
                    },
                    Command("cmd", "/c", "del", "/q", temp))
        }
        HostManager.hostIsLinux || HostManager.hostIsMac -> listOf(
                     Command(ar, "cqT", executable).apply {
                        +objectFiles
                        +libraries
                     },
                     Command("/bin/sh", "-c").apply {
                        +"printf 'create $executable\\naddlib $executable\\nsave\\nend' | $ar -M"
                     })
        else -> TODO("Unsupported host ${HostManager.host}")
    }

// Use "clang -v -save-temps" to write linkCommand() method 
// for another implementation of this class.
abstract class LinkerFlags(val configurables: Configurables)
/* : Configurables by configurables */ {

    protected val llvmBin = "${configurables.absoluteLlvmHome}/bin"
    protected val llvmLib = "${configurables.absoluteLlvmHome}/lib"

    private val libLTODir = when (HostManager.host) {
        KonanTarget.MACOS_X64, KonanTarget.LINUX_X64 -> llvmLib
        KonanTarget.MINGW_X64 -> llvmBin
        else -> error("Don't know libLTO location for this platform.")
    }

    open val useCompilerDriverAsLinker: Boolean get() = false // TODO: refactor.

    abstract fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean,
                              kind: LinkerOutputKind, outputDsymBundle: String): List<Command>

    abstract fun filterStaticLibraries(binaries: List<String>): List<String>

    open fun linkStaticLibraries(binaries: List<String>): List<String> {
        val libraries = filterStaticLibraries(binaries)
        // Let's just pass them as absolute paths.
        return libraries
    }
}

open class AndroidLinker(targetProperties: AndroidConfigurables)
    : LinkerFlags(targetProperties), AndroidConfigurables by targetProperties {

    private val clangQuad = when (targetProperties.targetArg) {
        "arm-linux-androideabi" -> "armv7a-linux-androideabi"
        else -> targetProperties.targetArg
    }
    private val prefix = "$absoluteTargetToolchain/bin/${clangQuad}${Android.API}"
    private val clang = if (HostManager.hostIsMingw) "$prefix-clang.cmd" else "$prefix-clang"
    private val ar = "$absoluteTargetToolchain/${targetProperties.targetArg}/bin/ar"

    override val useCompilerDriverAsLinker: Boolean get() = true

    override fun filterStaticLibraries(binaries: List<String>) = binaries.filter { it.isUnixStaticLib }

    override fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean,
                              kind: LinkerOutputKind, outputDsymBundle: String): List<Command> {
        if (kind == LinkerOutputKind.STATIC_LIBRARY)
            return staticGnuArCommands(ar, executable, objectFiles, libraries)

        val dynamic = kind == LinkerOutputKind.DYNAMIC_LIBRARY
        val toolchainSysroot = "${absoluteTargetToolchain}/sysroot"
        val architectureDir = Android.architectureDirForTarget(target)
        val apiSysroot = "$absoluteTargetSysRoot/$architectureDir"
        val clangTarget = targetArg!!
        val libDirs = listOf(
                "--sysroot=$apiSysroot",
                if (target == KonanTarget.ANDROID_X64) "-L$apiSysroot/usr/lib64" else "-L$apiSysroot/usr/lib",
                "-L$toolchainSysroot/usr/lib/$clangTarget/${Android.API}",
                "-L$toolchainSysroot/usr/lib/$clangTarget")
        return listOf(Command(clang).apply {
            +"-o"
            +executable
            +"-fPIC"
            +"-shared"
            +"-target"
            +targetArg!!
            +libDirs
            +objectFiles
            if (optimize) +linkerOptimizationFlags
            if (!debug) +linkerNoDebugFlags
            if (dynamic) +linkerDynamicFlags
            if (dynamic) +"-Wl,-soname,${File(executable).name}"
            +linkerKonanFlags
            +libraries
            +linkerArgs
        })
    }
}

open class MacOSBasedLinker(targetProperties: AppleConfigurables)
    : LinkerFlags(targetProperties), AppleConfigurables by targetProperties {

    private val libtool = "$absoluteTargetToolchain/usr/bin/libtool"
    private val linker = "$absoluteTargetToolchain/usr/bin/ld"
    private val strip = "$absoluteTargetToolchain/usr/bin/strip"
    private val dsymutil = "$absoluteLlvmHome/bin/dsymutil"

    private val KonanTarget.isSimulator: Boolean
        get() = this == KonanTarget.TVOS_X64 || this == KonanTarget.IOS_X64 ||
                this == KonanTarget.WATCHOS_X86 || this == KonanTarget.WATCHOS_X64

    private fun provideCompilerRtLibrary(libraryName: String): String? {
        val prefix = when (target.family) {
            Family.IOS -> "ios"
            Family.WATCHOS -> "watchos"
            Family.TVOS -> "tvos"
            Family.OSX -> "osx"
            else -> error("Target $target is unsupported")
        }
        val suffix = if (libraryName.isNotEmpty() && target.isSimulator) {
            "sim"
        } else {
            ""
        }

        val dir = File("$absoluteTargetToolchain/usr/lib/clang/").listFiles.firstOrNull()?.absolutePath
        val mangledLibraryName = if (libraryName.isEmpty()) "" else "${libraryName}_"

        return if (dir != null) "$dir/lib/darwin/libclang_rt.$mangledLibraryName$prefix$suffix.a" else null
    }

    private val compilerRtLibrary: String? by lazy {
        provideCompilerRtLibrary("")
    }

    // Code coverage requires this library.
    private val profileLibrary: String? by lazy {
        provideCompilerRtLibrary("profile")
    }

    private val osVersionMinFlags: List<String> by lazy {
        listOf(osVersionMinFlagLd, osVersionMin + ".0")
    }

    override fun filterStaticLibraries(binaries: List<String>) = binaries.filter { it.isUnixStaticLib }

    override fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean, kind: LinkerOutputKind,
                              outputDsymBundle: String): List<Command> {
        if (kind == LinkerOutputKind.STATIC_LIBRARY)
            return listOf(Command(libtool).apply {
                +"-static"
                +listOf("-o", executable)
                +objectFiles
                +libraries
            })
        val dynamic = kind == LinkerOutputKind.DYNAMIC_LIBRARY

        val result = mutableListOf<Command>()

        result += Command(linker).apply {
            +"-demangle"
            +listOf("-dynamic", "-arch", arch)
            +osVersionMinFlags
            +listOf("-syslibroot", absoluteTargetSysRoot, "-o", executable)
            +objectFiles
            if (optimize) +linkerOptimizationFlags
            if (!debug) +linkerNoDebugFlags
            if (dynamic) +linkerDynamicFlags
            +linkerKonanFlags
            if (compilerRtLibrary != null) +compilerRtLibrary!!
            if (profileLibrary != null) +profileLibrary!!
            +libraries
            +linkerArgs
            +rpath(dynamic)
        }

        // TODO: revise debug information handling.
        if (debug) {
            result += dsymUtilCommand(executable, outputDsymBundle)
            if (optimize) {
                result += Command(strip, "-S", executable)
            }
        }

        return result
    }

    private fun rpath(dynamic: Boolean): List<String> = listOfNotNull(
            when (target.family) {
                Family.OSX -> "@executable_path/../Frameworks"
                Family.IOS,
                Family.WATCHOS,
                Family.TVOS -> "@executable_path/Frameworks"
                else -> error(target)
            },
            "@loader_path/Frameworks".takeIf { dynamic }
    ).flatMap { listOf("-rpath", it) }

    fun dsymUtilCommand(executable: ExecutableFile, outputDsymBundle: String) =
            object : Command(dsymutilCommand(executable, outputDsymBundle)) {
                override fun runProcess(): Int =
                        executeCommandWithFilter(command)
            }

    // TODO: consider introducing a better filtering directly in Command.
    private fun executeCommandWithFilter(command: List<String>): Int {
        val builder = ProcessBuilder(command)

        // Inherit main process output streams.
        val isDsymUtil = (command[0] == dsymutil)

        builder.redirectOutput(Redirect.INHERIT)
        builder.redirectInput(Redirect.INHERIT)
        if (!isDsymUtil)
            builder.redirectError(Redirect.INHERIT)

        val process = builder.start()
        if (isDsymUtil) {
            /**
             * llvm-lto has option -alias that lets tool to know which symbol we use instead of _main,
             * llvm-dsym doesn't have such a option, so we ignore annoying warning manually.
             */
            val errorStream = process.errorStream
            val outputStream = bufferedReader(errorStream)
            while (true) {
                val line = outputStream.readLine() ?: break
                if (!line.contains("warning: could not find object file symbol for symbol _main"))
                    System.err.println(line)
            }
            outputStream.close()
        }
        val exitCode = process.waitFor()
        return exitCode
    }

    open fun dsymutilCommand(executable: ExecutableFile, outputDsymBundle: String): List<String> =
            listOf(dsymutil, executable, "-o", outputDsymBundle)

    open fun dsymutilDryRunVerboseCommand(executable: ExecutableFile): List<String> =
            listOf(dsymutil, "-dump-debug-map", executable)
}

open class LinuxBasedLinker(targetProperties: LinuxBasedConfigurables)
    : LinkerFlags(targetProperties), LinuxBasedConfigurables by targetProperties {

    private val ar = if (HostManager.hostIsMac) "$absoluteTargetToolchain/bin/llvm-ar"
        else "$absoluteTargetToolchain/bin/ar"
    override val libGcc = "$absoluteTargetSysRoot/${super.libGcc}"
    private val linker = "$absoluteLlvmHome/bin/ld.lld"
    private val specificLibs = abiSpecificLibraries.map { "-L${absoluteTargetSysRoot}/$it" }

    override fun filterStaticLibraries(binaries: List<String>) = binaries.filter { it.isUnixStaticLib }

    override fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean,
                              kind: LinkerOutputKind, outputDsymBundle: String): List<Command> {
        if (kind == LinkerOutputKind.STATIC_LIBRARY)
            return staticGnuArCommands(ar, executable, objectFiles, libraries)

        val isMips = (configurables is LinuxMIPSConfigurables)
        val dynamic = kind == LinkerOutputKind.DYNAMIC_LIBRARY
        val crtPrefix = if (configurables.target == KonanTarget.LINUX_ARM64) "usr/lib" else "usr/lib64"
        // TODO: Can we extract more to the konan.configurables?
        return listOf(Command(linker).apply {
            +"--sysroot=${absoluteTargetSysRoot}"
            +"-export-dynamic"
            +"-z"
            +"relro"
            +"--build-id"
            +"--eh-frame-hdr"
            +"-dynamic-linker"
            +dynamicLinker
            +"-o"
            +executable
            if (!dynamic) +"$absoluteTargetSysRoot/$crtPrefix/crt1.o"
            +"$absoluteTargetSysRoot/$crtPrefix/crti.o"
            +if (dynamic) "$libGcc/crtbeginS.o" else "$libGcc/crtbegin.o"
            +"-L$llvmLib"
            +"-L$libGcc"
            if (!isMips) +"--hash-style=gnu" // MIPS doesn't support hash-style=gnu
            +specificLibs
            +listOf("-L$absoluteTargetSysRoot/../lib", "-L$absoluteTargetSysRoot/lib", "-L$absoluteTargetSysRoot/usr/lib")
            if (optimize) +linkerOptimizationFlags
            if (!debug) +linkerNoDebugFlags
            if (dynamic) +linkerDynamicFlags
            +objectFiles
            +linkerKonanFlags
            +listOf("-lgcc", "--as-needed", "-lgcc_s", "--no-as-needed",
                    "-lc", "-lgcc", "--as-needed", "-lgcc_s", "--no-as-needed")
            +if (dynamic) "$libGcc/crtendS.o" else "$libGcc/crtend.o"
            +"$absoluteTargetSysRoot/$crtPrefix/crtn.o"
            +libraries
            +linkerArgs
        })
    }
}

open class MingwLinker(targetProperties: MingwConfigurables)
    : LinkerFlags(targetProperties), MingwConfigurables by targetProperties {

    private val ar = "$absoluteTargetToolchain/bin/ar"
    private val linker = "$absoluteTargetToolchain/bin/clang++"

    override val useCompilerDriverAsLinker: Boolean get() = true

    override fun filterStaticLibraries(binaries: List<String>) = binaries.filter { it.isWindowsStaticLib || it.isUnixStaticLib }

    override fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean,
                              kind: LinkerOutputKind, outputDsymBundle: String): List<Command> {
        if (kind == LinkerOutputKind.STATIC_LIBRARY)
            return staticGnuArCommands(ar, executable, objectFiles, libraries)

        val dynamic = kind == LinkerOutputKind.DYNAMIC_LIBRARY
        return listOf(when {
                HostManager.hostIsMingw -> Command(linker)
                else -> Command("wine64", "$linker.exe")
        }.apply {
            +listOf("-o", executable)
            +objectFiles
            if (optimize) +linkerOptimizationFlags
            if (!debug) +linkerNoDebugFlags
            if (dynamic) +linkerDynamicFlags
            +libraries
            +linkerArgs
            +linkerKonanFlags
        })
    }
}

open class WasmLinker(targetProperties: WasmConfigurables)
    : LinkerFlags(targetProperties), WasmConfigurables by targetProperties {

    override val useCompilerDriverAsLinker: Boolean get() = false

    override fun filterStaticLibraries(binaries: List<String>) = binaries.filter { it.isJavaScript }

    override fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean,
                              kind: LinkerOutputKind, outputDsymBundle: String): List<Command> {
        if (kind != LinkerOutputKind.EXECUTABLE) throw Error("Unsupported linker output kind")

        // TODO(horsh): maybe rethink it.
        return listOf(object : Command() {
            override fun execute() {
                val src = File(objectFiles.single())
                val dst = File(executable)
                src.recursiveCopyTo(dst)
                javaScriptLink(libraries, executable)
            }

            private fun javaScriptLink(jsFiles: List<String>, executable: String): String {
                val linkedJavaScript = File("$executable.js")

                val linkerHeader = "var konan = { libraries: [] };\n"
                val linkerFooter = """|if (isBrowser()) {
                                      |   konan.moduleEntry([]);
                                      |} else {
                                      |   konan.moduleEntry(arguments);
                                      |}""".trimMargin()

                linkedJavaScript.writeText(linkerHeader)

                jsFiles.forEach {
                    linkedJavaScript.appendBytes(File(it).readBytes())
                }

                linkedJavaScript.appendBytes(linkerFooter.toByteArray())
                return linkedJavaScript.name
            }
        })
    }
}

open class ZephyrLinker(targetProperties: ZephyrConfigurables)
    : LinkerFlags(targetProperties), ZephyrConfigurables by targetProperties {

    private val linker = "$absoluteTargetToolchain/bin/ld"

    override val useCompilerDriverAsLinker: Boolean get() = false

    override fun filterStaticLibraries(binaries: List<String>) = emptyList<String>()

    override fun linkCommands(objectFiles: List<ObjectFile>, executable: ExecutableFile,
                              libraries: List<String>, linkerArgs: List<String>,
                              optimize: Boolean, debug: Boolean,
                              kind: LinkerOutputKind, outputDsymBundle: String): List<Command> {
        if (kind != LinkerOutputKind.EXECUTABLE) throw Error("Unsupported linker output kind: $kind")
        return listOf(Command(linker).apply {
            +listOf("-r", "--gc-sections", "--entry", "main")
            +listOf("-o", executable)
            +objectFiles
            +libraries
            +linkerArgs
        })
    }
}

fun linker(configurables: Configurables): LinkerFlags =
        when (configurables.target) {
            KonanTarget.LINUX_X64, KonanTarget.LINUX_ARM32_HFP,  KonanTarget.LINUX_ARM64 ->
                LinuxBasedLinker(configurables as LinuxConfigurables)

            KonanTarget.LINUX_MIPS32, KonanTarget.LINUX_MIPSEL32 ->
                LinuxBasedLinker(configurables as LinuxMIPSConfigurables)

            KonanTarget.MACOS_X64,
            KonanTarget.TVOS_X64, KonanTarget.TVOS_ARM64,
            KonanTarget.IOS_ARM32, KonanTarget.IOS_ARM64, KonanTarget.IOS_X64,
            KonanTarget.WATCHOS_ARM64, KonanTarget.WATCHOS_ARM32,
            KonanTarget.WATCHOS_X64, KonanTarget.WATCHOS_X86 ->
                MacOSBasedLinker(configurables as AppleConfigurables)

            KonanTarget.ANDROID_ARM32, KonanTarget.ANDROID_ARM64,
            KonanTarget.ANDROID_X86, KonanTarget.ANDROID_X64 ->
                AndroidLinker(configurables as AndroidConfigurables)

            KonanTarget.MINGW_X64, KonanTarget.MINGW_X86 ->
                MingwLinker(configurables as MingwConfigurables)

            KonanTarget.WASM32 ->
                WasmLinker(configurables as WasmConfigurables)

            is KonanTarget.ZEPHYR ->
                ZephyrLinker(configurables as ZephyrConfigurables)
        }

