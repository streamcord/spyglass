package io.streamcord.spyglass

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class EventHandler(private val numFlows: Int, private val workerIndex: Long, private val workerTotal: Long) {
    private val mutableSharedFlows = List(numFlows) { MutableSharedFlow<suspend () -> Unit>() }

    suspend fun submitEvent(streamerID: Long, task: suspend () -> Unit) {
        if (streamerID % workerTotal != workerIndex) return
        val eventHandlerIndex = ((streamerID - workerIndex) / workerTotal % numFlows).toInt()
        mutableSharedFlows[eventHandlerIndex].emit(task)
    }

    fun collectIn(scope: CoroutineScope) = mutableSharedFlows.forEach { flow ->
        scope.launch {
            flow.asSharedFlow().collect { it.invoke() }
        }
    }
}
