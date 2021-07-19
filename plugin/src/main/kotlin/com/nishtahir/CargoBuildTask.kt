package com.nishtahir;

import com.android.build.gradle.*
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.File

open class CargoBuildTask : CargoTask() {
    @Suppress("unused")
    @TaskAction
    fun build() = with(project) {
        extensions[CargoExtension::class].apply {
            // Need to capture the value to dereference smoothly.
            val toolchain = toolchain
            if (toolchain == null) {
                throw GradleException("toolchain cannot be null")
            }

            val extraFlags = extraCargoBuildArguments ?: listOf()
            runCargo("build", extraFlags, project, toolchain, profile!!, this)

            val cargoOutputDir = getOutputDirectory(toolchain, profile!!, getDefaultTargetTriple(project, rustcCommand))
            copy { spec ->
                spec.from(File(project.projectDir, cargoOutputDir))
                spec.into(File(buildDir, "rustJniLibs/${toolchain.folder}"))

                // Need to capture the value to dereference smoothly.
                val targetIncludes = targetIncludes
                if (targetIncludes != null) {
                    spec.include(targetIncludes.asIterable())
                } else {
                    // It's safe to unwrap, since we bailed at configuration time if this is unset.
                    val libname = libname!!
                    spec.include("lib${libname}.so")
                    spec.include("lib${libname}.dylib")
                    spec.include("${libname}.dll")
                }
            }
        }
    }
}
