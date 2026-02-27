package me.palmdevs.covalent.tweaks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import me.palmdevs.covalent.TARGET_ACTIVITY_CLASS
import me.palmdevs.covalent.api.tweak
import me.palmdevs.covalent.hook
import me.palmdevs.covalent.method
import java.lang.ref.WeakReference

/**
 * Whether we have successfully received a [Context] yet.
 * Sometimes the app process is recreated and Xposed hooks way too late for us to get [Context] from [ContextWrapper.attachBaseContext].
 * But since Xposed hooks before [Activity.onCreate], we can still get it from there and still initialize properly.
 */
@Volatile
private var context = WeakReference<Context>(null)
@Volatile
private var activity = WeakReference<Activity>(null)

private var onContextHooks = mutableListOf<(Context) -> Unit>()
private var onActivityHooks = mutableListOf<(Activity) -> Unit>()

/**
 * A tweak that hooks into the lifecycle of the target app to provide [Context] and [Activity] to other tweaks.
 * This is necessary because many operations require a [Context] or [Activity], and we need to provide them to other tweaks in a safe way.
 */
val covalentLifecycleSupport by tweak {
    apply { _, classLoader ->
        val reactActivity = classLoader.loadClass(TARGET_ACTIVITY_CLASS)

        ContextWrapper::class.java.method("attachBaseContext", Context::class.java).hook {
            after {
                val ctx = args[0] as Context
                context = WeakReference(ctx)
                log.i("Got Context")

                for (hook in onContextHooks) hook(ctx)
                onContextHooks.clear()
            }
        }

        reactActivity.method("onCreate", Bundle::class.java).hook {
            after {
                val act = thisObject as Activity
                activity = WeakReference(act)
                log.i("Got Activity")

                if (context.get() == null) {
                    context = WeakReference(act)
                    log.w("Activity created before we got Context, process may have been recreated!")

                    for (hook in onContextHooks) hook(act)
                    onContextHooks.clear()
                }

                for (hook in onActivityHooks) hook(act)
                onActivityHooks.clear()

            }
        }
    }
}

fun withContext(hook: (Context) -> Unit) {
    val ctx = context.get()
    if (ctx != null) {
        hook(ctx)
        return
    }

    onContextHooks += hook
}

fun withActivity(hook: (Activity) -> Unit) {
    val act = activity.get()
    if (act != null) {
        hook(act)
        return
    }

    onActivityHooks += hook
}