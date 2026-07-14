package com.e7orbit.capture

import com.e7orbit.automation.ScreenCaptureException
import com.e7orbit.model.ScreenFrame
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface ProjectionFrameProvider {
    suspend fun capture(): ScreenFrame
}

class ProjectionCaptureRepository {
    private val provider = AtomicReference<ProjectionFrameProvider?>()
    private val _isReady = MutableStateFlow(false)

    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    fun attach(frameProvider: ProjectionFrameProvider) {
        provider.set(frameProvider)
        _isReady.value = true
    }

    fun detach(frameProvider: ProjectionFrameProvider) {
        provider.compareAndSet(frameProvider, null)
        _isReady.value = provider.get() != null
    }

    suspend fun capture(): ScreenFrame {
        val current = provider.get()
            ?: throw ScreenCaptureException("屏幕捕获尚未授权")
        return current.capture()
    }
}
