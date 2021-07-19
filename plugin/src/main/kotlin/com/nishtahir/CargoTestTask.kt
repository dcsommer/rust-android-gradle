package com.nishtahir;

import com.android.build.gradle.*
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import java.io.File

open class CargoTestTask : CargoTask() {
    @Suppress("unused")
    @TaskAction
    fun build() = with(project) {
        extensions[CargoExtension::class].apply {
            val extraFlags = extraCargoTestArguments ?: listOf();
            runCargo("test", extraFlags, project, toolchain!!, profile!!, this)
        }
    }
}
