package io.streamcord.spyglass

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class EventHandler(private val numFlows: Int, private val workerInfo: WorkerInfo) {
    private val mutableSharedFlows = List(numFlows) { MutableSharedFlow<suspend () -> Unit>() }

    suspend fun submitEvent(streamerID: Long, task: suspend () -> Unit) {
        if (workerInfo shouldNotHandle streamerID) return
        val eventHandlerIndex = ((streamerID - workerInfo.index) / workerInfo.total % numFlows).toInt()
        mutableSharedFlows[eventHandlerIndex].emit(task)
    }

    fun collectIn(scope: CoroutineScope) = mutableSharedFlows.forEach { flow ->
        scope.launch {
            flow.asSharedFlow().collect { it.invoke() }
        }
    }
}
