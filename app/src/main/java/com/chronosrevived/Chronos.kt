package com.chronosrevived

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Single connection to the disc. All state touched on the main thread only. */
@SuppressLint("MissingPermission")
object Chronos : BluetoothGattCallback() {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<Pair<String, () -> Boolean>>()
    private var inFlight: String? = null
    private var opSeq = 0
    private var deviceBusy = false

    var connected = false; private set

    /** Why the link is down, from the Bluetooth stack's disconnect reason. */
    enum class Link { NEVER, CONNECTING, CONNECTED, ASLEEP, OUT_OF_RANGE, BLUETOOTH_OFF }
    var link = Link.NEVER; private set
    /** When the disc was last connected (persisted), 0 = never. */
    val lastSeen: Long get() = if (::app.isInitialized) prefs.getLong("lastSeen", 0) else 0
    var inBootloader = false; private set
    var dfuActive = false
    var dfuProgress = -1
    var scanning = false; private set
    var battery = -1; private set
    var status = 0; private set
    var firmware = ""; private set
    var steps: Proto.Steps? = null; private set
    val history = mutableListOf<Proto.History>()

    val listeners = mutableListOf<() -> Unit>()
    val log = ArrayDeque<String>()

    /** Last battery reading and when it was taken, kept for while the disc sleeps. */
    val lastBattery: Pair<Int, Long>?
        get() = prefs.getInt("lastBattery", -1).takeIf { it >= 0 }?.let { it to prefs.getLong("lastBatteryAt", 0) }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                say("bond state ${i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)}"); next()
            }
        }, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        // Bluetooth toggled: drop the dead link when it goes off, start waiting for the disc when it comes back.
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> { release(); link = Link.BLUETOOTH_OFF; changed() }
                    BluetoothAdapter.STATE_ON -> { link = Link.ASLEEP; changed(); connect(autoConnect = true) }
                }
            }
        }, android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private val prefs get() = app.getSharedPreferences("chronos", Context.MODE_PRIVATE)
    var address: String?
        get() = prefs.getString("address", null)
        set(v) { prefs.edit().putString("address", v).apply() }

    fun say(msg: String) = main.post {
        android.util.Log.i("Chronos", msg)
        log.addLast(msg); while (log.size > 200) log.removeFirst()
        listeners.forEach { it() }
    }
    private fun markSeen() = prefs.edit().putLong("lastSeen", System.currentTimeMillis()).apply()

    private fun changed() = main.post { listeners.forEach { it() } }

    fun connect(autoConnect: Boolean = false) {
        val addr = address ?: return
        if (gatt != null || dfuActive) return
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) { say("Bluetooth is off"); link = Link.BLUETOOTH_OFF; changed(); return }
        if (link == Link.NEVER || link == Link.BLUETOOTH_OFF || !autoConnect) { link = Link.CONNECTING; changed() }
        say("Connecting to $addr (auto=$autoConnect)")
        gatt = adapter.getRemoteDevice(addr).connectGatt(app, autoConnect, this, BluetoothDevice.TRANSPORT_LE)
    }

    /** Finds the disc by name or service UUID and remembers it. */
    fun scan() {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) { say("Bluetooth is off"); return }
        if (scanning) return
        // A recent classic discovery makes Android show the disc's pairing request as a dialog
        // instead of burying it in the notification shade (where it times out after 30s).
        adapter.startDiscovery()
        main.postDelayed({ adapter.cancelDiscovery() }, 5000)
        val seen = HashSet<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: ""
                val uuids = r.scanRecord?.serviceUuids.orEmpty()
                val isChronos = name.equals("Chronos", ignoreCase = true) ||
                    uuids.any { it.uuid == Proto.SERVICE || it.uuid == Proto.DFU_FACTORY }
                if (seen.add(r.device.address) && isChronos) say("seen ${r.device.address} '$name' rssi=${r.rssi}")
                if (isChronos && address == null) {
                    say("Picked $name ${r.device.address}")
                    address = r.device.address
                    stop(this)
                    connect()
                    LinkService.start(app)
                }
            }
            override fun onScanFailed(errorCode: Int) { say("scan failed $errorCode"); stop(this) }
        }
        fun go() { scanning = true; changed(); scanner.startScan(cb); say("Scanning...") }
        go()
        main.postDelayed({ stop(cb) }, 20000)
    }

    private fun stop(cb: android.bluetooth.le.ScanCallback) {
        if (!scanning) return
        scanning = false
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(cb)
        changed()
    }

    /** Drop our GATT link without forgetting the address (DFU needs the radio to itself). */
    fun release() {
        gatt?.disconnect(); gatt?.close(); gatt = null
        connected = false; inBootloader = false; queue.clear(); inFlight = null
        changed()
    }

    fun forget() {
        address = null
        gatt?.disconnect(); gatt?.close(); gatt = null
        connected = false; queue.clear(); inFlight = null
        changed()
    }

    // --- request queue: Android GATT allows one outstanding operation ---

    private fun enqueue(name: String, op: () -> Boolean) = main.post { queue.addLast(name to op); next() }

    private var retryPosted = false
    private fun retryLater(ms: Long, before: () -> Unit = {}) {
        if (retryPosted) return
        retryPosted = true
        main.postDelayed({ retryPosted = false; before(); next() }, ms)
    }

    // While Android is pairing it silently holds GATT ops, so wait instead of timing out.
    private fun bonding() = gatt?.device?.bondState == BluetoothDevice.BOND_BONDING
    val bonding get() = bonding()

    private fun next() {
        if (inFlight != null || queue.isEmpty()) return
        if (bonding()) { retryLater(1000); return }
        if (deviceBusy) { retryLater(2500) { deviceBusy = false }; return }
        val (name, op) = queue.removeFirst()
        inFlight = name
        if (!op()) { say("op $name failed to start"); inFlight = null; next(); return }
        armWatchdog(++opSeq, name)
    }

    private fun armWatchdog(seq: Int, name: String) {
        main.postDelayed({
            if (inFlight == null || opSeq != seq) return@postDelayed
            if (bonding()) armWatchdog(seq, name)
            else { say("op $name timed out"); inFlight = null; next() }
        }, 5000)
    }

    private fun done() = main.post { inFlight = null; next() }

    private fun char(svc: UUID, ch: UUID) = gatt?.getService(svc)?.getCharacteristic(ch)

    private fun read(svc: UUID, ch: UUID) = enqueue("read $ch") {
        val c = char(svc, ch); c != null && gatt!!.readCharacteristic(c)
    }

    @Suppress("DEPRECATION")
    fun send(name: String, bytes: ByteArray) = enqueue(name) {
        val c = char(Proto.SERVICE, Proto.COMMAND) ?: return@enqueue false
        say("> $name ${Proto.hex(bytes)}")
        c.value = bytes
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt!!.writeCharacteristic(c)
    }

    @Suppress("DEPRECATION")
    private fun subscribe(svc: UUID, ch: UUID) = enqueue("sub $ch") {
        val c = char(svc, ch) ?: return@enqueue false
        gatt!!.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Proto.CCCD) ?: return@enqueue false
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt!!.writeDescriptor(d)
    }

    // --- public commands ---

    fun refresh() {
        read(Proto.BATTERY_SVC, Proto.BATTERY_LEVEL)
        read(Proto.SERVICE, Proto.STATUS)
        send("current steps", Proto.currentSteps())
    }

    fun fetchHistory() { history.clear(); send("history", Proto.historicalSteps()) }

    var buzzCount = 0; private set
    var lastBuzz = Proto.Color.BLUE to 2; private set

    fun notify(color: Proto.Color, pattern: Int) {
        main.post { buzzCount++; lastBuzz = color to pattern; changed() }
        send("light", Proto.light(color, pattern))
        send("vibrate", Proto.vibrate(pattern))
        send("play", Proto.PLAY)
    }

    // --- GATT callbacks (binder thread) ---

    override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
        say("connection status=$st state=$newState")
        if (st == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            main.postDelayed({ g.discoverServices() }, 600)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
            main.post {
                g.close()
                if (gatt === g) gatt = null
                val was = connected
                if (was) markSeen()
                connected = false; inBootloader = false; queue.clear(); inFlight = null
                // 19 = the disc ended the link itself (it sleeps when still); 8 = link timed out (out of range,
                // or it powered down without saying goodbye). Anything else while connecting is just a failed attempt.
                link = when (st) {
                    19, 0 -> Link.ASLEEP
                    8 -> Link.OUT_OF_RANGE
                    else -> if (link == Link.CONNECTING && lastSeen == 0L) Link.NEVER else link.takeIf { it != Link.CONNECTED && it != Link.CONNECTING } ?: Link.ASLEEP
                }
                if (was) say(if (link == Link.ASLEEP) "Disc went to sleep" else "Lost contact with disc")
                changed()
                // Background reconnect: autoConnect waits for the disc to come back in range.
                if (address != null && !dfuActive) main.postDelayed({ connect(autoConnect = true) }, 2000)
            }
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
        val svcs = g.services.joinToString { it.uuid.toString().substring(0, 8) }
        say("services st=$st: $svcs")
        if (g.getService(Proto.DFU_FACTORY) != null) {
            say("!! Disc is in bootloader/DFU mode (no app firmware). Tap Install firmware.")
            main.post { inBootloader = true; changed() }
            return
        }
        // Same order as the original app: battery, response, status notifications, then bond.
        subscribe(Proto.BATTERY_SVC, Proto.BATTERY_LEVEL)
        subscribe(Proto.SERVICE, Proto.RESPONSE)
        subscribe(Proto.SERVICE, Proto.STATUS)
        read(Proto.INFO_SVC, Proto.FIRMWARE_REV)
        send("brightness max", Proto.brightness(0xFF))
        // The disc only reports taps for features that are switched on (original app did this on connect).
        val st = Settings(app)
        send("gesture media", Proto.gesture(Proto.Gesture.MEDIA, st.mediaTaps))
        send("gesture find", Proto.gesture(Proto.Gesture.FIND_PHONE, st.findPhone))
        refresh()
        fetchHistory()
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) =
        onCharacteristicRead(g, c, c.value ?: ByteArray(0), st)

    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, st: Int) {
        if (st == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, v) else say("read ${c.uuid} failed st=$st")
        done()
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
        if (st != BluetoothGatt.GATT_SUCCESS) say("write failed st=$st")
        done()
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
        if (st != BluetoothGatt.GATT_SUCCESS) say("subscribe ${d.characteristic.uuid} failed st=$st")
        if (d.characteristic.uuid == Proto.STATUS) main.post {
            // Last subscription done: the original app bonds here.
            connected = true; link = Link.CONNECTED; markSeen(); changed()
            if (g.device.bondState == BluetoothDevice.BOND_NONE) g.device.createBond()
        }
        done()
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
        handle(c.uuid, c.value ?: ByteArray(0))

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) =
        handle(c.uuid, v)

    private fun handle(uuid: UUID, v: ByteArray) { main.post {
        when (uuid) {
            Proto.BATTERY_LEVEL -> {
                battery = v.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                if (battery >= 0) BatteryLog.add(app, battery, status)
                if (battery >= 0) prefs.edit().putInt("lastBattery", battery).putLong("lastBatteryAt", System.currentTimeMillis()).apply()
                if (battery in 0..25 && status and Proto.ON_CHARGER == 0) say("Low battery: $battery%")
            }
            Proto.FIRMWARE_REV -> firmware = String(v).trim()
            Proto.STATUS -> if (v.isNotEmpty()) {
                val old = status
                status = Proto.status(v)
                deviceBusy = status and Proto.BUSY != 0
                Gestures.onStatus(app, old, status)
            }
            Proto.RESPONSE -> when (val e = Proto.parseResponse(v)) {
                is Proto.Steps -> steps = e
                is Proto.History -> {
                    // remaining must count down; cap at 255 (u8) so a confused disc can't loop us forever.
                    val prev = history.lastOrNull()?.remaining ?: Int.MAX_VALUE
                    val sane = e.remaining in 1 until prev && history.size < 255
                    if (sane) history.add(e)
                    if (sane && e.remaining > 1) send("history", Proto.historicalSteps())
                    else StepLog.save(app, history)
                }
                else -> {}
            }
        }
        say("< ${uuidShort(uuid)} ${Proto.hex(v)}")
    } }

    private fun uuidShort(u: UUID) = u.toString().substring(4, 8)
}
