package io.github.beomsun0829.handsonguide.signal

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/** Read-only NaviHelper connection, owned by GuideService through GuideApp. */
class NavigationSource(context: Context, private val hub: SignalHub) {
    private val thread = HandlerThread("GuideNavigation").apply { start() }
    private val handler = Handler(thread.looper)
    private val serviceContext = WorkerConnectionContext(context.applicationContext, handler)
    private val state = NavigationState()
    private var sdk: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var epoch = 0L
    @Volatile private var stopped = false
    private var started = false
    private var connected = false
    private var retryCount = 0
    private var failedCalls = 0
    private var lastEventAt = 0L
    private val deniedQueries = HashSet<String>()

    fun start() { handler.post { if (!started && !stopped) { started = true; connect() } } }

    private fun connect() {
        if (stopped) return
        disconnect()
        val session = epoch
        try {
            val client = NaviHelper(serviceContext)
            sdk = client
            handler.postDelayed({
                if (session == epoch && !connected && !stopped) retry("initialization timeout")
            }, 12_000)
            client.initialize(onConnected = {
                handler.post {
                    if (session != epoch || stopped) return@post
                    connected = true
                    state.connection(true)
                    lastEventAt = SystemClock.elapsedRealtime()
                    failedCalls = 0
                    listener = events(session).also(client::addListener)
                    Log.i(TAG, "NaviHelper connected")
                    publish()
                    poll(session, 0)
                }
            }, onFailed = { error -> handler.post {
                if (session == epoch && !stopped) retry("initialization: ${error.javaClass.simpleName}")
            } })
        } catch (e: Exception) { retry("initialization: ${e.javaClass.simpleName}") }
    }

    private fun events(session: Long) = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) = receive(session) {
            state.status(status.name, now())
            Log.i(TAG, "Navigation status=${status.name}")
        }
        override fun onRouteStateInfo(info: RouteStateInfo) = receive(session) {
            state.routeState(info.naviMode.name, info.isRouting, now())
        }
        override fun onTBTInfo(info: List<TBTInfo>) = receive(session) {
            state.turns(info.map { NavTurn(it.type.name, it.distance, it.description) }, now())
        }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = receive(session) {
            state.location(info.latitude, info.longitude, info.address, info.routeId, info.currentRouteIndex, now())
        }
        override fun onDestinationInfo(info: DestinationInfo) = receive(session) {
            state.destination(info.name, info.remainDistance, info.remainTime, now())
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = receive(session) {
            state.speedLimit(speedLimit, now())
        }
        override fun onCautionPointInfo(info: List<CautionPoint>) = receive(session) {
            state.cautions(info.map { NavCaution(it.id, it.type.name, it.remainDistance) }, now())
        }
        override fun onCameraAlert(info: List<CameraAlert>) = receive(session) {
            state.cameras(info.map { NavCamera(it.id, it.type.name, it.remainDistance, it.speedLimit, it.status, it.isOverSpeed) }, now())
        }
        override fun onRouteStarted(info: RouteStartInfo) = receive(session) { state.routeChanging(now()) }
        override fun onDestinationChanged() = receive(session) { state.routeChanging(now()); queryRoute() }
        override fun onRoutingCompleted() = receive(session) { queryRoute() }
        override fun onReRouteEnded() = receive(session) { queryRoute() }
        override fun onRouteCancelled() = receive(session) { state.routeCancelled(now()) }
        override fun onDestinationArrived(info: DestinationArrivedInfo) = receive(session) { state.routeCancelled(now()) }
        override fun onError(errorCode: NaviErrorCode) = receive(session) {
            // Navigation request errors do not mean the Binder connection disappeared.
            Log.w(TAG, "Navigation request error=$errorCode")
        }
    }

    private fun receive(session: Long, apply: () -> Unit) { handler.post {
        if (stopped || session != epoch || !connected) return@post
        lastEventAt = SystemClock.elapsedRealtime()
        failedCalls = 0
        retryCount = 0
        try { apply(); publish() } catch (e: Exception) {
            Log.w(TAG, "Navigation event rejected: ${e.javaClass.simpleName}")
        }
    } }

    private fun poll(session: Long, tick: Int) {
        if (stopped || session != epoch || !connected) return
        if (SystemClock.elapsedRealtime() - lastEventAt > 30_000 || failedCalls >= 6) {
            retry("navigation callbacks unavailable")
            return
        }
        query("TBT") { getTBTInfo() }
        query("location") { getCurrentLocationInfo() }
        if (tick % 5 == 0) queryRoute()
        publish() // Expire old values even while the Navigation service is silent.
        handler.postDelayed({ poll(session, tick + 1) }, 1000)
    }

    private fun queryRoute() {
        query("route state") { getRouteStateInfo() }
        query("destination") { getDestinationInfo() }
    }

    private fun query(name: String, block: NaviHelper.() -> Unit) {
        if (!connected || name in deniedQueries) return
        try { sdk?.block() } catch (e: SecurityException) {
            deniedQueries += name
            Log.w(TAG, "Navigation permission unavailable for $name")
        } catch (e: Exception) {
            failedCalls++
            Log.w(TAG, "Navigation $name failed: ${e.javaClass.simpleName}")
        }
    }

    private fun retry(reason: String) {
        if (stopped) return
        Log.w(TAG, "NaviHelper unavailable: $reason")
        disconnect()
        val session = epoch
        val delay = (5_000L shl retryCount.coerceAtMost(3)).coerceAtMost(40_000)
        retryCount++
        handler.postDelayed({ if (session == epoch && !stopped) connect() }, delay)
    }

    private fun disconnect() {
        ++epoch
        connected = false
        val client = sdk; sdk = null
        listener?.let { l -> runCatching { client?.removeListener(l) } }; listener = null
        runCatching { client?.release() }
        state.connection(false)
        publish()
    }

    private fun publish() = hub.putAll(state.snapshot(now()))
    private fun now() = System.currentTimeMillis()

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        handler.post { disconnect(); thread.quitSafely() }
    }

    companion object { private const val TAG = "GuideNavigation" }
}
