package com.nishtahir;

import com.android.build.gradle.*
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.File

open class CargoClippyTask : CargoTask() {
    @Suppress("unused")
    @TaskAction
    fun build() = with(project) {
        extensions[CargoExtension::class].apply {
            val extraFlags = extraCargoClippyArguments ?: listOf()
            runCargo("clippy", extraFlags, project, toolchain!!, profile!!, this)
        }
    }
}
