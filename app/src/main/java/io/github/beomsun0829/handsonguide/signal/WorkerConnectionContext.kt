package io.github.beomsun0829.handsonguide.signal

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/**
 * Car internally uses the three-argument bindService(), which dispatches ServiceConnection
 * on Main even if createCar ran on IO and an event handler was supplied. Car also holds its
 * connection lock across manager binder calls. Dispatch the entire connection on a worker
 * so a slow car service cannot indirectly lock Main during onServiceConnected/disconnected.
 * The wrapper preserves connection identity for unbind and works on the app's min API 28.
 */
internal class WorkerConnectionContext(base: Context, private val handler: Handler) : ContextWrapper(base) {
    private val wrappers = ConcurrentHashMap<ServiceConnection, ServiceConnection>()
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        val wrapper = wrappers.getOrPut(conn) {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { handler.post { conn.onServiceConnected(name, binder) } }
                override fun onServiceDisconnected(name: ComponentName) { handler.post { conn.onServiceDisconnected(name) } }
                override fun onBindingDied(name: ComponentName) { handler.post { conn.onBindingDied(name) } }
                override fun onNullBinding(name: ComponentName) { handler.post { conn.onNullBinding(name) } }
            }
        }
        return super.bindService(service, wrapper, flags)
    }
    override fun unbindService(conn: ServiceConnection) { super.unbindService(wrappers.remove(conn) ?: conn) }
}
