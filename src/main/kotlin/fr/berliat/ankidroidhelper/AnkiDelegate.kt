package fr.berliat.ankidroidhelper

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

import com.ichi2.anki.api.AddContentApi
import com.ichi2.anki.api.AddContentApi.READ_WRITE_PERMISSION

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlin.reflect.KClass

/**
 * AnkiDelegate is your easiest way to store things into Anki. This implementation is
 * coupled to the WordListManager.
 *
 * Due to the fact that Anki API can only be called after having the right permissions, and app
 * is running. There are lots of checks to be done, and the permissions one must happen in the
 * fragment thread. This Helper handles it all. All you focus on is returning suspend methods that
 * do the actual Anki API calls.
 *
 * Here's a simplistic example:
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *
 *         ankiDelegate = AnkiDelegate(applicationContext)
 *         ankiDelegate.attachActivity(this)
 *         ankiDelegate.delegateToAnki(WordListRepo.insertWordToList(list, word))
 *     }
 *
 * If you use a viewModel, make sure to only pass the ankiDelegate::delegateToAnki method to not
 * create memory leaks. AnkiDelegate only holds the application context plus a permission
 * launcher bound to the attached activity (re-attach after recreation).
 *
 * Beware of execution patterns, as the callbacks can mean Anki calls executing after whatever
 * element you change/delete.
 */
typealias AnkiDelegator = suspend ((suspend () -> Result<Unit>)?) -> Unit
typealias AnkiServiceDelegator = suspend (serviceClass: KClass<out AnkiSyncService>) -> Unit

open class AnkiDelegate(
    context: Context, val callbackHandler: HandlerInterface?
) {
    private var callbackListener = callbackHandler

    interface HandlerInterface {
        fun onAnkiOperationSuccess()
        fun onAnkiOperationCancelled()
        fun onAnkiOperationFailed(e: Throwable)
        fun onAnkiSyncProgress(current: Int, total: Int, message: String)
        fun onAnkiRequestPermissionGranted()
        fun onAnkiRequestPermissionDenied()
        fun onAnkiServiceStarting(serviceDelegate: AnkiSyncServiceDelegate)
    }

    private val context = context.applicationContext
    private val delegateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val callQueue: ArrayDeque<suspend () -> Result<Unit>> = ArrayDeque()
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    init {
        observeUiEvents()
    }

    /**
     * Binds the Anki permission launcher. Must be called from Activity.onCreate
     * (before STARTED). Call again after activity recreation.
     */
    fun attachActivity(activity: FragmentActivity) {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { result ->
            onAnkiRequestPermissionsResult(result[READ_WRITE_PERMISSION] ?: false)
        }
    }

    /** Cancels event observation. The delegate holds no activity reference. */
    open fun destroy() {
        delegateScope.cancel()
    }

    fun replaceListener(callbackHandler: HandlerInterface) {
        callbackListener = callbackHandler
    }

    suspend fun delegateToAnki(ankiAction: (suspend () -> Result<Unit>)?) = withContext(Dispatchers.IO) {
        ankiAction?.let {
            val result = safelyModifyAnkiDbIfAllowed {
                try {
                    withContext(Dispatchers.IO) {
                        ankiAction.invoke() // action sync must happen on IO thread.
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Anki operation yielded an Exception." + e.message
                    )
                    Result.failure(Exception("Anki Operation Crashed: " + e.message))
                }
            }

            result.onSuccess { onAnkiOperationSuccess(context) }
                .onFailure { e ->
                    if (e is CancellationException)
                        onAnkiOperationCancelled(context)
                    else
                        onAnkiOperationFailed(context, e)
                }
        }
    }

    suspend fun delegateToAnkiService(serviceClass: KClass<out AnkiSyncService>) = withContext(Dispatchers.IO) {
        delegateToAnki(suspend {
            val serviceDelegate = AnkiSyncServiceDelegate(context, serviceClass.java)
            serviceDelegate.startSyncToAnkiOperation()
            val result = serviceDelegate.awaitOperationCompletion()

            serviceDelegate.cleanup()

            result
        })
    }

    /********** Anki Permissions ************/
    protected fun requestPermission() {
        val launcher = checkNotNull(permissionLauncher) {
            "AnkiDelegate.attachActivity() must be called from Activity.onCreate before any Anki operation"
        }
        launcher.launch(arrayOf(READ_WRITE_PERMISSION))
    }

    protected fun shouldRequestPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, READ_WRITE_PERMISSION) !=
                PackageManager.PERMISSION_GRANTED
    }

    protected open fun onAnkiRequestPermissionsResult(granted: Boolean) {
        Log.i(TAG, "AnkiPermissions to read/write is granted? $granted")

        if (granted) {
            callbackListener?.onAnkiRequestPermissionGranted()
            while (callQueue.isNotEmpty()) {
                val action = callQueue.removeFirst()
                delegateScope.launch(Dispatchers.IO) {
                    safelyModifyAnkiDb { action() }
                }
            }
        } else {
            callbackListener?.onAnkiRequestPermissionDenied()
        }
    }

    /********* Checking Anki's Running & Installed **********/
    protected open suspend fun ensureAnkiDroidIsRunning() {
        withContext(Dispatchers.Main) {
            if (!isAnkiRunning()) startAnkiDroid()
        }
    }

    protected fun isApiAvailable(): Boolean {
        return AddContentApi.getAnkiDroidPackageName(context) != null
    }

    protected open suspend fun startAnkiDroid(): Boolean {
        // Necessary, based on https://github.com/ankidroid/Anki-Android/issues/18286
        val intent = Intent().apply {
            action = AddContentApi.getAnkiDroidPackageName(context) + ".DO_SYNC"
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)

            repeat(10) {
                if (isAnkiRunning()) return true

                delay(100)
            }

            throw Exception("Couldn't start Anki in 0.5 second")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Anki is not installed, cannot start: $e")
            false
        }
    }

    @SuppressLint("ServiceCast")
    private fun isAnkiRunning(): Boolean {
        return AddContentApi(context).deckList != null
    }

    /********** Our main listening loop **********/
    private fun observeUiEvents() {
        delegateScope.launch(Dispatchers.IO) {
            AnkiSharedEventBus.uiEvents.collect { event ->
                delegateScope.launch(Dispatchers.Main) {
                    when (event) {
                        is AnkiSharedEventBus.UiEvent.AnkiServiceProgress -> {
                            // Handle progress updates for long operations
                            Log.d(TAG, "Progress update: ${event.state.progress}/${event.state.total} - ${event.state.message}")

                            // Forward progress to registered callback
                            onAnkiSyncProgress(context, event)
                        }
                        is AnkiSharedEventBus.UiEvent.AnkiServiceStarting -> {
                            onAnkiServiceStarting(context, event.serviceDelegate)
                        }
                        is AnkiSharedEventBus.UiEvent.AnkiServiceCancelled -> {
                            onAnkiOperationCancelled(context)
                        }
                        is AnkiSharedEventBus.UiEvent.AnkiServiceError -> {
                            onAnkiOperationFailed(context, Exception(event.state.message))
                        }
                        is AnkiSharedEventBus.UiEvent.AnkiServiceCompleted -> {
                            onAnkiOperationSuccess(context)
                        }
                    }
                }
            }
        }
    }

    protected suspend fun safelyModifyAnkiDb(ankiDbAction: suspend () -> Result<Unit>): Result<Unit> {
        ensureAnkiDroidIsRunning()
        return withContext(Dispatchers.IO) {
            ankiDbAction()
        }
    }

    protected open suspend fun safelyModifyAnkiDbIfAllowed(ankiDbAction: suspend () -> Result<Unit>): Result<Unit> {
        if (!isApiAvailable()) {
            onAnkiNotInstalled()

            return Result.failure(AnkiOperationsFailures.AnkiFailure_NotInstalled)
        }

        if (shouldRequestPermission()) {
            callQueue.add(ankiDbAction)
            requestPermission()
            return Result.failure(AnkiOperationsFailures.AnkiFailure_Deferred)
        }

        return safelyModifyAnkiDb(ankiDbAction)
    }

    protected fun appContextToast(context: Context?, message: String) {
        if (context == null) {
            return
        }

        Toast.makeText(
            context,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    /*********** CallBacks ***********/
    protected open fun onAnkiOperationFailed(context: Context?, e: Throwable) {
        if (e is AnkiOperationsFailures.AnkiFailure_Deferred
            || e is AnkiOperationsFailures.AnkiFailure_Off
            || context == null
        )
            return

        callbackListener?.onAnkiOperationFailed(e)
    }

    protected open fun onAnkiServiceStarting(context: Context?, serviceDelegate: AnkiSyncServiceDelegate) {
        if (context == null) return

        callbackListener?.onAnkiServiceStarting(serviceDelegate)
    }

    protected open fun onAnkiSyncProgress(context: Context?, event: AnkiSharedEventBus.UiEvent.AnkiServiceProgress) {
        if (context == null) return

        // The service does the notification update

        callbackListener?.onAnkiSyncProgress(event.state.progress, event.state.total, event.state.message)
    }

    protected open fun onAnkiOperationSuccess(context: Context?) {
        if (context == null) return

        callbackListener?.onAnkiOperationSuccess()
    }

    protected open fun onAnkiOperationCancelled(context: Context?) {
        if (context == null) return

        callbackListener?.onAnkiOperationCancelled()
    }

    protected open fun onAnkiNotInstalled() {
    }

    sealed class AnkiOperationsFailures: Throwable() {
        object AnkiFailure_Deferred : AnkiOperationsFailures()
        object AnkiFailure_NotInstalled : AnkiOperationsFailures()
        object AnkiFailure_Off : AnkiOperationsFailures()
    }

    companion object {
        private const val TAG = "AnkiDelegate"
    }
}
