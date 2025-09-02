# AnkiDroidAPIHelper
AnkiDroidAPIHelper is your easiest way to interact safely with the AnkiDroid API from your Android Fragments.

## Why AnkiDroidAPIHelper?
AnkiDroidAPIHelper handles for you all checks needed to safely call the API without handling any of the complications.
In practice, the API calls are gated on having the app installed, securing the permissions, app running...
Which are really cumbersome to handle End-2-End, in particular since the permissions require a Fragment but the
execution should only happen on an IO threads.

AnkiDroidAPIHelper handles for you:
- Checking that AnkiDroid is installed
- Checking for the Read/Write permissions
- Securing the permissions from the user on Read/Write to AnkiDroid
- Ensures Anki is running/can be run in the background or simply starts it. Note: China OEM like to disable background launch, hence the "is running". Read https://github.com/ankidroid/Anki-Android/issues/18286 for more details.
- Run the operation in a IO thread loop, or even run a service for longer operations (like a long import)

## How to use?
Instead of focusing on the E2E flow, you set it up and focus on call backs provided.

### Create an AnkiDelegate on Fragment creation
The 2 parameters for `AnkiDelegate` are a Fragment and a callbackHandler implementing `AnkiDelete.HandlerInterface`
which can be one and the same:
```
import fr.berliat.ankidroidhelper.AnkiDelegate

class MyFragment : Fragment(), AnkiDelegate.HandlerInterface {
    private lateinit var ankiDelegate: AnkiDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 
        ankiDelegate = AnkiDelegate(this, this)
    }

    override fun onAnkiOperationSuccess() {}
    override fun onAnkiOperationCancelled() {}
    override fun onAnkiOperationFailed(e: Throwable) {}
    override fun onAnkiSyncProgress(current: Int, total: Int, message: String) {}
    override fun onAnkiRequestPermissionGranted() {}
    override fun onAnkiRequestPermissionDenied() {}
    override fun onAnkiServiceStarting(serviceDelegate: AnkiSyncServiceDelegate) {}
}
```

### Call 'delegateToAnki' with short operations
Upon the call, the AnkiDelegate will do all checks and balances, and call the corresponding callbacks.
`delegateToAnki()` takes 1 argument: a suspend block that will executes when AnkiDroid is ready for you.
If AnkiDroid is not ready, you'll get one of the error callbacks.

[Some examples](https://github.com/licryle/Android-HSKFlashcardsWidget/blob/master/app/src/main/java/fr/berliat/hskwidget/data/repo/WordListRepository.kt)
```
    ankiDelegate.delegateToAnki(CardDAO.insertCardToAnki(card))
```
#### Tip 1
Call a function that returns a suspend block. Why?
The function will execute when delegateToAnki is called, this is perfect to update your *local* database.
Then, the returned suspend block contains your Anki API calls, and logic upon success/failure and will only be called if safe to do.
The suspend block should call Result.success(Unit) or Result.failure(Exception())

Beware of execution patterns, as the callbacks can mean Anki calls executing after whatever element you
change/delete.

#### Tip 2
If you use a viewModel, make sure to only pass the ankiDelegate::delegateToAnki method to not create memory leaks.
AnkiDelegate does reference a fragment after all. That function has a helper signature typealias called "AnkiDelegator".
```
class CardViewModel(val dao: cardDAO, val ankiCaller: AnkiDelegator) { }
```

### Call 'delegateToAnki' with an AnkiSyncService for long operations
For long operations, like syncing a whole dictionary to AnkiDroid, you should use an `AnkiSyncService`.

The AnkiSyncService provides you with the scaffolding for it:
- Requesting the notification permission
- Start/Failure/Cancel/Success a foreground service
- Progress notification
- Feeds all events back to the AnkiDelegate (and the callback handler you implemented as a result)

To use it, you *must* derive the `AnkiSyncService` and implement the following methods:
[An example](https://github.com/licryle/Android-HSKFlashcardsWidget/blob/master/app/src/main/java/fr/berliat/hskwidget/domain/AnkiSyncWordListsService.kt)

And simply delegate the service class:
```
     ankiDelegate.delegateToAnki(AnkiSyncAllCardsService::class)
```
You can, of course, apply the same tip above with a DAO and return a KClass argument.

Full class implementation:
```
class AnkiSyncAllCardsService: AnkiSyncService() {
    override suspend fun syncToAnki() {
        // Here are all the Anki operations
        // Don't forget to call in between
        updateProgress(progress, nbErrors, nbToImport, message)
        // And at the end
        Result.failure(Exception("$nbErrors ouf of $nbToImport imported to Anki."))
        // or
        Result.success(Unit)
    }

    override fun getSyncStartMessage(): String {
        return ""// Message in the notification at init
    }

    override fun getActivityClass(): Class<out Any> {
        // Return your MainActivity
        return MainActivity::class.java
    }

    override fun getNotificationTitle(): String {
        return getString(R.string.app_name)
    }

    override fun getNotificationLargeIcon(): Bitmap? {
        return BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
    }

    override fun getNotificationSmallIcon(): Int {
        return R.mipmap.ic_launcher_monochrome_mini
    }

    override fun getNotificationCancelIcon(): Int {
        return R.drawable.close_24px
    }

    override fun getNotificationCancelText(): String {
        return getString(R.string.cancel)
    }

    override fun getNotificationChannelTitle(): String {
        // Appears when requesting permission and in Settings > Notifications
        return getString(R.string.anki_sync_notification_name)
    }

    override fun getNotificationChannelDescription(): String {
        // Apears when requesting permission and in Settings > Notifications
        return getString(R.string.anki_sync_notification_description)
    }
}
```
