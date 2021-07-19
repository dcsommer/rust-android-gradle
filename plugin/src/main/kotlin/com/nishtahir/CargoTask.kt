package com.nishtahir;

import com.android.build.gradle.*
import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input;
import org.apache.tools.ant.taskdefs.condition.Os
import java.io.ByteArrayOutputStream
import java.io.File

open class CargoTask : DefaultTask() {
    @Input
    var toolchain: Toolchain? = null
    @Input
    var profile: String? = null
}

fun getNdkDirectory(project: Project): File {
    project.plugins.forEach {
        val cls = when (it) {
            is AppPlugin -> AppExtension::class
            is LibraryPlugin -> LibraryExtension::class
            else -> null
        }
        if (cls != null) {
            return project.extensions[cls].ndkDirectory
        }
    }
    throw GradleException("No app or library plugin for this project")
}

fun getDefaultTargetTriple(project: Project, rustc: String): String? {
    val stdout = ByteArrayOutputStream()
    val result = project.exec { spec ->
        spec.standardOutput = stdout
        spec.commandLine = listOf(rustc, "--version", "--verbose")
    }
    if (result.exitValue != 0) {
        project.logger.warn(
            "Failed to get default target triple from rustc (exit code: ${result.exitValue})")
        return null
    }
    val output = stdout.toString()

    // The `rustc --version --verbose` output contains a number of lines like `key: value`.
    // We're only interested in `host: `, which corresponds to the default target triple.
    val triplePrefix = "host: "

    val triple = output.split("\n")
        .find { it.startsWith(triplePrefix) }
        ?.let { it.substring(triplePrefix.length).trim() }

    if (triple == null) {
        project.logger.warn("Failed to parse `rustc -Vv` output! (Please report a rust-android-gradle bug)")
    } else {
        project.logger.info("Default rust target triple: $triple")
    }
    return triple
}

fun runCargo(command: String, extraFlags: List<String>, project: Project, toolchain: Toolchain, profile: String, cargoExtension: CargoExtension) {
    val apiLevel = cargoExtension.apiLevels[toolchain.platform]!!
    val defaultTargetTriple = getDefaultTargetTriple(project, cargoExtension.rustcCommand)

    project.exec { spec ->
        with(spec) {
            standardOutput = System.out
            workingDir = File(project.project.projectDir, cargoExtension.module!!)

            val theCommandLine = mutableListOf(cargoExtension.cargoCommand)

            if (!cargoExtension.rustupChannel.isEmpty()) {
                val hasPlusSign = cargoExtension.rustupChannel.startsWith("+")
                val maybePlusSign = if (!hasPlusSign) "+" else ""

                theCommandLine.add(maybePlusSign + cargoExtension.rustupChannel)
            }

            theCommandLine.add(command)

            // Respect `verbose` if it is set; otherwise, log if asked to
            // with `--info` or `--debug` from the command line.
            if (cargoExtension.verbose ?: project.logger.isEnabled(LogLevel.INFO)) {
                theCommandLine.add("--verbose")
            }

            val features = cargoExtension.featureSpec.features
            // We just pass this along to cargo as something space separated... AFAICT
            // you're allowed to have featureSpec with spaces in them, but I don't think
            // there's a way to specify them in the cargo command line -- rustc accepts
            // them if passed in directly with `--cfg`, and cargo will pass them to rustc
            // if you use them as default featureSpec.
            when (features) {
                is Features.All -> {
                    theCommandLine.add("--all-features")
                }
                is Features.DefaultAnd -> {
                    if (!features.featureSet.isEmpty()) {
                        theCommandLine.add("--features")
                        theCommandLine.add(features.featureSet.joinToString(" "))
                    }
                }
                is Features.NoDefaultBut -> {
                    theCommandLine.add("--no-default-features")
                    if (!features.featureSet.isEmpty()) {
                        theCommandLine.add("--features")
                        theCommandLine.add(features.featureSet.joinToString(" "))
                    }
                }
            }

            // TODO: When --profile is stabilized use it instead of --release
            // https://github.com/rust-lang/cargo/issues/6988
            if (profile == "release") {
                theCommandLine.add("--release")
            } else if (profile != "dev") {
                throw GradleException("Profile may only be 'dev' or 'release', got '${profile}'")
            }

            if (toolchain.target != defaultTargetTriple) {
                // Only providing --target for the non-default targets means desktop builds
                // can share the build cache with `cargo build`/`cargo test`/etc invocations,
                // instead of requiring a large amount of redundant work.
                theCommandLine.add("--target=${toolchain.target}")
            }

            // Target-specific environment configuration, passed through to
            // the underlying `cargo build` invocation.
            val toolchain_target = toolchain.target.toUpperCase().replace('-', '_')
            val prefix = "RUST_ANDROID_GRADLE_TARGET_${toolchain_target}_"

            // For ORG_GRADLE_PROJECT_RUST_ANDROID_GRADLE_TARGET_x_KEY=VALUE, set KEY=VALUE.
            project.logger.info("Passing through project properties with prefix '${prefix}' (environment variables with prefix 'ORG_GRADLE_PROJECT_${prefix}'")
            project.properties.forEach { (key, value) ->
                                         if (key.startsWith(prefix)) {
                                             val realKey = key.substring(prefix.length)
                                             project.logger.debug("Passing through environment variable '${key}' as '${realKey}=${value}'")
                                             environment(realKey, value)
                                         }
            }

            // Cross-compiling to Android requires toolchain massaging.
            if (toolchain.type != ToolchainType.DESKTOP) {
                val toolchainDirectory = if (toolchain.type == ToolchainType.ANDROID_PREBUILT) {
                    val ndkPath = getNdkDirectory(project)
                    val hostTag = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                        if (Os.isArch("x86_64") || Os.isArch("amd64")) {
                            "windows-x86_64"
                        } else {
                            "windows"
                        }
                    } else if (Os.isFamily(Os.FAMILY_MAC)) {
                        "darwin-x86_64"
                    } else {
                        "linux-x86_64"
                    }
                    File("$ndkPath/toolchains/llvm/prebuilt", hostTag)
                } else {
                    cargoExtension.toolchainDirectory
                }

                val linker_wrapper =
                if (System.getProperty("os.name").startsWith("Windows")) {
                    File(project.rootProject.buildDir, "linker-wrapper/linker-wrapper.bat")
                } else {
                    File(project.rootProject.buildDir, "linker-wrapper/linker-wrapper.sh")
                }
                environment("CARGO_TARGET_${toolchain_target}_LINKER", linker_wrapper.path)

                val runner = if (System.getProperty("os.name").startsWith("Windows")) {
                    // TODO: this file does not exist yet. We don't support running executables
                    // (e.g. tests) on Windows yet.
                    File(project.rootProject.buildDir, "runner/run-on-android.bat")
                } else {
                    File(project.rootProject.buildDir, "runner/run-on-android.sh")
                }
                val targetForEnv = toolchain.target.toUpperCase().replace("-", "_")
                environment("CARGO_TARGET_${targetForEnv}_RUNNER", runner.path)

                val cc = File(toolchainDirectory, "${toolchain.cc(apiLevel)}").path;
                val cxx = File(toolchainDirectory, "${toolchain.cxx(apiLevel)}").path;
                val ar = File(toolchainDirectory, "${toolchain.ar(apiLevel)}").path;

                // For build.rs in `cc` consumers: like "CC_i686-linux-android".  See
                // https://github.com/alexcrichton/cc-rs#external-configuration-via-environment-variables.
                environment("CC_${toolchain.target}", cc)
                environment("CXX_${toolchain.target}", cxx)
                environment("AR_${toolchain.target}", ar)

                // Set CLANG_PATH in the environment, so that bindgen (or anything
                // else using clang-sys in a build.rs) works properly, and doesn't
                // use host headers and such.
                val shouldConfigure = cargoExtension.getFlagProperty(
                    "rust.autoConfigureClangSys",
                    "RUST_ANDROID_GRADLE_AUTO_CONFIGURE_CLANG_SYS",
                    // By default, only do this for non-desktop platforms. If we're
                    // building for desktop, things should work out of the box.
                    toolchain.type != ToolchainType.DESKTOP
                )
                if (shouldConfigure) {
                    environment("CLANG_PATH", cc)
                }

                // Configure our linker wrapper.
                environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", cargoExtension.pythonCommand)
                environment("RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY",
                        File(project.rootProject.buildDir, "linker-wrapper/linker-wrapper.py").path)
                environment("RUST_ANDROID_GRADLE_CC", cc)
                environment("RUST_ANDROID_GRADLE_CC_LINK_ARG", "-Wl,-soname,lib${cargoExtension.libname!!}.so")
            }

            theCommandLine.addAll(extraFlags)

            commandLine = theCommandLine
        }
        if (cargoExtension.exec != null) {
            (cargoExtension.exec!!)(spec, toolchain)
        }
    }.assertNormalExitValue()
}
