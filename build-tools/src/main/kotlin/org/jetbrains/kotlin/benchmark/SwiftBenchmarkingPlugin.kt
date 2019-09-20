package org.jetbrains.kotlin.benchmark

import groovy.lang.Closure
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.*
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetPreset
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File
import javax.inject.Inject
import java.nio.file.Paths
import kotlin.reflect.KClass

enum class CodeSizeEntity { FRAMEWORK, EXECUTABLE }

open class SwiftBenchmarkExtension @Inject constructor(project: Project) : BenchmarkExtension(project) {
    var swiftSources: List<String> = emptyList()
    var useCodeSize: CodeSizeEntity = CodeSizeEntity.FRAMEWORK         // use as code size metric framework size or executable
}

/**
 * A plugin configuring a benchmark Kotlin/Native project.
 */
open class SwiftBenchmarkingPlugin : BenchmarkingPlugin() {
    override val benchmarkExtensionClass: KClass<*>
        get() = SwiftBenchmarkExtension::class

    override val Project.jvmParameters: Pair<Int, String>?
        get() = null

    override val Project.benchmark: SwiftBenchmarkExtension
        get() = extensions.getByName(benchmarkExtensionName) as SwiftBenchmarkExtension

    override val benchmarkExtensionName: String = "swiftBenchmark"

    override val Project.nativeExecutable: String
        get() = Paths.get(buildDir.absolutePath, benchmark.applicationName).toString()

    override val Project.nativeLinkTask: Task
        get() = tasks.getByName("buildSwift")

    private lateinit var framework: Framework
    val nativeFrameworkName = "benchmark"

    override fun NamedDomainObjectContainer<KotlinSourceSet>.configureSources(project: Project) {
        project.benchmark.let {
            commonMain.kotlin.srcDirs(*it.commonSrcDirs.toTypedArray())
            nativeMain.kotlin.srcDirs(*(it.nativeSrcDirs).toTypedArray())
        }
    }

    override fun Project.determinePreset(): KotlinNativeTargetPreset = kotlin.presets.macosX64 as KotlinNativeTargetPreset

    override fun KotlinNativeTarget.configureNativeOutput(project: Project) {
        binaries.framework(nativeFrameworkName, listOf(RELEASE)) {
            // Specify settings configured by a user in the benchmark extension.
            project.afterEvaluate {
                linkerOpts.addAll(project.benchmark.linkerOpts)
            }
        }
    }

    override fun Project.configureExtraTasks() {
        val nativeTarget = kotlin.targets.getByName(NATIVE_TARGET_NAME) as KotlinNativeTarget
        // Build executable from swift code.
        framework = nativeTarget.binaries.getFramework(nativeFrameworkName, NativeBuildType.RELEASE)
        val buildSwift = tasks.create("buildSwift") { task ->
            task.dependsOn(framework.linkTaskName)
            task.doLast {
                val frameworkParentDirPath = framework.outputDirectory.absolutePath
                val options = listOf("-Xlinker", "-rpath", "-Xlinker", frameworkParentDirPath, "-F", frameworkParentDirPath)
                compileSwift(project, nativeTarget.konanTarget, benchmark.swiftSources, options,
                        Paths.get(buildDir.absolutePath, benchmark.applicationName), false)
            }
        }
        framework.linkTask.finalizedBy(buildSwift)
    }

    override fun Project.collectCodeSize(applicationName: String) =
            getCodeSizeBenchmark(applicationName,
                    if (benchmark.useCodeSize == CodeSizeEntity.FRAMEWORK)
                        File("${framework.outputFile.absolutePath}/$nativeFrameworkName").canonicalPath
                    else
                        nativeExecutable
            )

    override fun Project.getCompilerFlags(nativeTarget: KotlinNativeTarget) =
            listOf<String>()
}
