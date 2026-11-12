package com.droiddeck.launcher.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Progress shared by the Linux runtime row and the container manager for every long operation. */
object RuntimeOperation {
    var running by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var percent by mutableIntStateOf(-1)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun begin(stage: String, percent: Int = -1) {
        this.stage = stage
        this.percent = percent
        problem = null
        running = true
    }

    fun fail(message: String) {
        problem = message
        running = false
    }

    fun progress(stage: String, percent: Int) {
        this.stage = stage
        this.percent = percent
        running = true
    }

    fun end() {
        running = false
        stage = ""
        percent = -1
    }
}
