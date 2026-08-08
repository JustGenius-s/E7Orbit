package com.e7orbit.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 装备抓包(VPNService)运行状态仓库。
 *
 * 上报 VPN 运行状态与抓包统计；装备解析和持久化状态由
 * [GearImportRepository] 负责。
 */
class VpnCaptureRepository {
    private val _isRunning = MutableStateFlow(false)
    private val _packets = MutableStateFlow(0L)
    private val _bytes = MutableStateFlow(0L)
    private val _capturedSegments = MutableStateFlow(0L)
    private val _capturedBytes = MutableStateFlow(0L)
    private val _lastError = MutableStateFlow<String?>(null)

    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    val packets: StateFlow<Long> = _packets.asStateFlow()
    val bytes: StateFlow<Long> = _bytes.asStateFlow()
    val capturedSegments: StateFlow<Long> = _capturedSegments.asStateFlow()
    val capturedBytes: StateFlow<Long> = _capturedBytes.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun attach() {
        _packets.value = 0L
        _bytes.value = 0L
        _capturedSegments.value = 0L
        _capturedBytes.value = 0L
        _lastError.value = null
        _isRunning.value = true
    }

    fun detach() {
        _isRunning.value = false
    }

    fun report(packets: Long, bytes: Long) {
        _packets.value = packets
        _bytes.value = bytes
    }

    fun reportCapture(segments: Long, bytes: Long) {
        _capturedSegments.value = segments
        _capturedBytes.value = bytes
    }

    fun reportError(message: String) {
        _lastError.value = message
    }
}
