package fr.berliat.ankidroidhelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile

import fr.berliat.ankidroidhelper.AnkiSyncService.OperationState

/**
 * Delegate for handling long operations with progress tracking and cancellation support.
 * 
 * This delegate provides a clean interface to:
 * - Start long operations via service
 * - Monitor progress via StateFlow
 * - Cancel operations
 * - Handle service lifecycle
 */
class AnkiSyncServiceDelegate(
    private val context: Context,
    private val serviceClass: Class<out AnkiSyncService>
) {
    companion object {
        private const val TAG = "AnkiSyncServiceDelegate"
    }
    
    private var service: AnkiSyncService? = null
    private var isBound = false
    private val serviceDeferred = CompletableDeferred<AnkiSyncService>()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val longOperationBinder = binder as AnkiSyncService.LongOperationBinder
            service = longOperationBinder.getService()
            isBound = true
            serviceDeferred.complete(service!!)  // signal that service is ready
            Log.d(TAG, "Service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    /**
     * Start a sync to Anki operation
     */
    fun startSyncToAnkiOperation() {
        val intent = Intent(context, serviceClass).apply {
            action = AnkiSyncService.ACTION_START_OPERATION
            putExtra(AnkiSyncService.EXTRA_OPERATION_TYPE, AnkiSyncService.OPERATION_SYNC_TO_ANKI)
        }

        context.startForegroundService(intent) // or startService if not foreground
        bindService()

        ProcessLifecycleOwner.get().lifecycleScope.launch {
            AnkiSharedEventBus.emit(AnkiSharedEventBus.UiEvent.AnkiServiceStarting(this@AnkiSyncServiceDelegate))
        }

        Log.d(TAG, "Started sync to Anki operation")
    }
    
    /**
     * Cancel the current operation
     */
    fun cancelCurrentOperation() {
        if (!isBound) {
            Log.w(TAG, "Service not bound, cannot cancel operation")
            return
        }
        
        val intent = Intent(context, serviceClass).apply {
            action = AnkiSyncService.ACTION_CANCEL_OPERATION
        }
        
        context.startService(intent)
        Log.d(TAG, "Cancelled current operation")

        cleanup()
    }
    
    /**
     * Get the current operation state
     */
    fun getOperationState(): StateFlow<OperationState>? {
        return service?.operationState
    }

    /**
     * Return the end state only when done to propagate to AnkiDelegate (in most scenarios).
     */
    suspend fun awaitOperationCompletion(): Result<Unit> {
        val service = serviceDeferred.await() // suspend until service is connected

        var result: Result<Unit> = Result.failure(Exception("Operation didn't complete."))

        val stateFlow = service.operationState.filterNotNull()

        stateFlow.onEach { state ->
            when (state) {
                is OperationState.Idle -> {
                    Log.d(TAG, "Operation state: Idle")
                }
                is OperationState.Running -> {
                    Log.d(TAG, "Operation state: Running - ${state.progress}/${state.total} - ${state.message}")
                    AnkiSharedEventBus.emit(AnkiSharedEventBus.UiEvent.AnkiServiceProgress(state))
                }
                is OperationState.Completed -> {
                    Log.d(TAG, "Operation state: Completed")
                    AnkiSharedEventBus.emit(AnkiSharedEventBus.UiEvent.AnkiServiceCompleted(state))
                    result = Result.success(Unit)
                }
                is OperationState.Cancelled -> {
                    Log.d(TAG, "Operation state: Cancelled")
                    AnkiSharedEventBus.emit(AnkiSharedEventBus.UiEvent.AnkiServiceCancelled(state))
                    result = Result.failure(CancellationException())
                }
                is OperationState.Error -> {
                    Log.e(TAG, "Operation state: Error - ${state.message}")
                    AnkiSharedEventBus.emit(AnkiSharedEventBus.UiEvent.AnkiServiceError(state))
                    result = Result.failure(Exception(state.message))
                }
            }
        }.takeWhile { state ->
            // The flow continues as long as the state is NOT a final state.
            !(state is OperationState.Completed || state is OperationState.Cancelled || state is OperationState.Error)
        }.collect()

        return result
    }
    
    private fun bindService() {
        val intent = Intent(context, serviceClass)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun unbindService() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
    
    fun cleanup() {
        unbindService()
        service = null
    }
} 