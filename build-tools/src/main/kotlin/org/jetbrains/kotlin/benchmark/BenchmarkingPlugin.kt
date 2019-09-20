package org.jetbrains.kotlin.benchmark

import groovy.lang.Closure
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.jvm.tasks.Jar
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.*
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetPreset
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.HostManager
import javax.inject.Inject
import kotlin.reflect.KClass
import kotlin.reflect.jvm.internal.impl.resolve.constants.KClassValue

internal val NamedDomainObjectContainer<KotlinSourceSet>.commonMain
    get() = maybeCreate("commonMain")

internal val NamedDomainObjectContainer<KotlinSourceSet>.nativeMain
    get() = maybeCreate("nativeMain")

internal val Project.nativeWarmup: Int
    get() = (property("nativeWarmup") as String).toInt()

internal val Project.attempts: Int
    get() = (property("attempts") as String).toInt()

internal val Project.nativeBenchResults: String
    get() = property("nativeBenchResults") as String

internal val Project.compilerArgs: List<String>
    get() = (findProperty("compilerArgs") as String?)?.split("\\s").orEmpty()

internal val Project.kotlinVersion: String
    get() = property("kotlinVersion") as String

internal val Project.konanVersion: String
    get() = property("konanVersion") as String

internal val Project.kotlinStdlibVersion: String
    get() = property("kotlinStdlibVersion") as String

internal val Project.kotlinStdlibRepo: String
    get() = property("kotlinStdlibRepo") as String

internal val Project.nativeJson: String
    get() = project.property("nativeJson") as String

internal val Project.jvmJson: String
    get() = project.property("jvmJson") as String

internal val Project.commonBenchmarkProperties: Map<String, Any>
    get() = mapOf(
        "cpu" to System.getProperty("os.arch"),
        "os" to System.getProperty("os.name"),
        "jdkVersion" to System.getProperty("java.version"),
        "jdkVendor" to System.getProperty("java.vendor"),
        "kotlinVersion" to kotlinVersion
    )

open class BenchmarkExtension @Inject constructor(val project: Project) {
    var applicationName: String = project.name
    var commonSrcDirs: Collection<Any> = emptyList()
    var nativeSrcDirs: Collection<Any> = emptyList()
    var compileTasks: List<String> = emptyList()
    var linkerOpts: Collection<String> = emptyList()

    val dependencies: BenchmarkDependencies = BenchmarkDependencies()

    fun dependencies(action: BenchmarkDependencies.() -> Unit) =
        dependencies.action()

    fun dependencies(action: Closure<*>) {
        ConfigureUtil.configure(action, dependencies)
    }

    inner class BenchmarkDependencies  {
        public val sourceSets: NamedDomainObjectContainer<KotlinSourceSet>
            get() = project.kotlin.sourceSets

        fun project(path: String): Dependency = project.dependencies.project(mapOf("path" to path))

        fun project(path: String, configuration: String): Dependency =
            project.dependencies.project(mapOf("path" to path, "configuration" to configuration))

        fun common(notation: Any) = sourceSets.commonMain.dependencies {
            implementation(notation)
        }

        fun native(notation: Any) = sourceSets.nativeMain.dependencies {
            implementation(notation)
        }
    }
}

/**
 * A plugin configuring a benchmark Kotlin/Native project.
 */
abstract class BenchmarkingPlugin: Plugin<Project> {
    protected abstract val Project.nativeExecutable: String
    protected abstract val Project.nativeLinkTask: Task
    protected abstract val Project.benchmark: BenchmarkExtension
    protected abstract val Project.jvmParameters: Pair<Int, String>?
    protected abstract val benchmarkExtensionName: String
    protected abstract val benchmarkExtensionClass: KClass<*>

    protected val mingwPath: String = System.getenv("MINGW64_DIR") ?: "c:/msys64/mingw64"

    protected open fun Project.determinePreset(): KotlinNativeTargetPreset =
        defaultHostPreset(this).also { preset ->
            logger.quiet("$project has been configured for ${preset.name} platform.")
        } as KotlinNativeTargetPreset

    protected abstract fun NamedDomainObjectContainer<KotlinSourceSet>.configureSources(project: Project)

    protected open fun NamedDomainObjectContainer<KotlinSourceSet>.additionalConfigurations(project: Project) {}

    protected open fun Project.configureSourceSets(kotlinVersion: String) {
        with(kotlin.sourceSets) {
            commonMain.dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinStdlibVersion")
            }

            project.configurations.getByName(nativeMain.implementationConfigurationName).apply {
                // Exclude dependencies already included into K/N distribution (aka endorsed libraries).
                exclude(mapOf("module" to "kotlinx.cli"))
            }

            repositories.maven {
                it.setUrl(kotlinStdlibRepo)
            }

            additionalConfigurations(this@configureSourceSets)

            // Add sources specified by a user in the benchmark DSL.
            afterEvaluate {
                configureSources(project)
            }
        }
    }

    protected open fun KotlinNativeTarget.configureNativeOutput(project: Project) {
            compilations.getByName("main").enableEndorsedLibs = true
        binaries.executable(NATIVE_EXECUTABLE_NAME, listOf(RELEASE)) {
            if (HostManager.hostIsMingw) {
                linkerOpts.add("-L${mingwPath}/lib")
            }

            runTask!!.apply {
                group = ""
                enabled = false
            }

            // Specify settings configured by a user in the benchmark extension.
            project.afterEvaluate {
                linkerOpts.addAll(project.benchmark.linkerOpts)
            }
        }
    }

    protected open fun Project.configureNativeTarget(hostPreset: KotlinNativeTargetPreset) {
        kotlin.targetFromPreset(hostPreset, NATIVE_TARGET_NAME) {
            compilations.getByName("main").kotlinOptions.freeCompilerArgs = project.compilerArgs + listOf("-l", "kotlinx-cli")
            configureNativeOutput(this@configureNativeTarget)
        }
    }

    protected open fun Project.configureAdditionalExtensions() {
    }

    protected open fun Project.configureMPPExtension() {
        configureSourceSets(kotlinVersion)
        configureNativeTarget(determinePreset())
        configureAdditionalExtensions()
    }

    protected open fun Project.configureNativeTask(nativeTarget: KotlinNativeTarget): Task {
        val konanRun = createRunTask(this, "konanRun", nativeLinkTask,
                nativeExecutable, buildDir.resolve(nativeBenchResults).absolutePath).apply {
            group = BENCHMARKING_GROUP
            description = "Runs the benchmark for Kotlin/Native."
        }
        afterEvaluate {
            (konanRun as RunKotlinNativeTask).args(
                    "-w", nativeWarmup.toString(),
                    "-r", attempts.toString(),
                    "-p", "${benchmark.applicationName}::"
            )
        }
        return konanRun
    }

    protected open fun Project.configureJvmTask(): Task {
        return tasks.create("jvmRun", RunJvmTask::class.java) { task ->
            jvmParameters?.let { (jvmWarmup, jvmBenchResults) ->
                task.dependsOn("build")
                val mainCompilation = kotlin.jvm().compilations.getByName("main")
                val runtimeDependencies = configurations.getByName(mainCompilation.runtimeDependencyConfigurationName)
                task.classpath(files(mainCompilation.output.allOutputs, runtimeDependencies))
                task.main = "MainKt"

                task.group = BENCHMARKING_GROUP
                task.description = "Runs the benchmark for Kotlin/JVM."

                // Specify settings configured by a user in the benchmark extension.
                afterEvaluate {
                    task.args(
                            "-w", jvmWarmup,
                            "-r", attempts,
                            "-o", buildDir.resolve(jvmBenchResults),
                            "-p", "${benchmark.applicationName}::"
                    )
                }
            } ?: run {
                task.doLast {
                    println("JVM run is unsupported")
                }
            }
        }
    }

    protected open fun Project.getCompilerFlags(nativeTarget: KotlinNativeTarget) =
            nativeTarget.compilations.main.kotlinOptions.freeCompilerArgs.map { "\"$it\"" }

    protected open fun Project.collectCodeSize(applicationName: String) =
        getCodeSizeBenchmark(applicationName, nativeExecutable)

    protected open fun Project.configureKonanJsonTask(nativeTarget: KotlinNativeTarget): Task {
        return tasks.create("konanJsonReport") {
            it.group = BENCHMARKING_GROUP
            it.description = "Builds the benchmarking report for Kotlin/Native."

            it.doLast {
                val applicationName = benchmark.applicationName
                val benchContents = buildDir.resolve(nativeBenchResults).readText()
                val nativeCompileTime = if (benchmark.compileTasks.isEmpty()) getNativeCompileTime(applicationName)
                    else getNativeCompileTime(applicationName, benchmark.compileTasks)

                val properties = commonBenchmarkProperties + mapOf(
                        "type" to "native",
                        "compilerVersion" to konanVersion,
                        "flags" to getCompilerFlags(nativeTarget),
                        "benchmarks" to benchContents,
                        "compileTime" to listOf(nativeCompileTime),
                        "codeSize" to collectCodeSize(applicationName)
                )

                val output = createJsonReport(properties)
                buildDir.resolve(nativeJson).writeText(output)
            }
        }
    }

    protected open fun Project.configureJvmJsonTask(jvmRun: Task): Task {
        return tasks.create("jvmJsonReport") {
            it.group = BENCHMARKING_GROUP
            it.description = "Builds the benchmarking report for Kotlin/JVM."

            it.doLast {
                jvmParameters?.let { (_, jvmBenchResults) ->
                    val applicationName = benchmark.applicationName
                    val jarPath = (tasks.getByName("jvmJar") as Jar).archiveFile.get().asFile
                    val jvmCompileTime = getJvmCompileTime(applicationName)
                    val benchContents = buildDir.resolve(jvmBenchResults).readText()

                    val properties: Map<String, Any> = commonBenchmarkProperties + mapOf(
                            "type" to "jvm",
                            "compilerVersion" to kotlinVersion,
                            "benchmarks" to benchContents,
                            "compileTime" to listOf(jvmCompileTime),
                            "codeSize" to getCodeSizeBenchmark(applicationName, jarPath.absolutePath)
                    )

                    val output = createJsonReport(properties)
                    buildDir.resolve(jvmJson).writeText(output)
                } ?: println("JVM run is unsupported")
            }

            jvmRun.finalizedBy(it)
        }
    }

    protected open fun Project.configureExtraTasks() {}

    private fun Project.configureTasks() {
        val nativeTarget = kotlin.targets.getByName(NATIVE_TARGET_NAME) as KotlinNativeTarget
        configureExtraTasks()
        // Native run task.
        configureNativeTask(nativeTarget)

        // JVM run task.
        val jvmRun = configureJvmTask()

        // Native report task.
        configureKonanJsonTask(nativeTarget)

        // JVM report task.
        configureJvmJsonTask(jvmRun)
    }

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("kotlin-multiplatform")

        // Use Kotlin compiler version specified by the project property.
        dependencies.add("kotlinCompilerClasspath", "org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
        addTimeListener(this)

        extensions.create(benchmarkExtensionName, benchmarkExtensionClass.java, this)
        configureMPPExtension()
        configureTasks()
    }

    companion object {
        const val NATIVE_TARGET_NAME = "native"
        const val NATIVE_EXECUTABLE_NAME = "benchmark"
        const val BENCHMARKING_GROUP = "benchmarking"
    }
}
