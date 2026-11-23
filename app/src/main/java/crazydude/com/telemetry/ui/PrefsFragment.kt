package crazydude.com.telemetry.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.*
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import crazydude.com.telemetry.R
import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.manager.PreferenceManager
import androidx.preference.ListPreference
import com.hoho.android.usbserial.driver.UsbSerialProber
import crazydude.com.telemetry.protocol.sniffer.SerialLink
import crazydude.com.telemetry.protocol.sniffer.SnifferUid
import crazydude.com.telemetry.protocol.sniffer.SnifferUidChanger
import crazydude.com.telemetry.protocol.sniffer.UsbSerialLink
import crazydude.com.telemetry.service.DataService
import crazydude.com.telemetry.terrain.TerrainInstaller
import crazydude.com.telemetry.terrain.TerrainLoader
import crazydude.com.telemetry.utils.FileLogger
import java.io.IOException

class PrefsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val REQUEST_IMPORT_FLIGHT_PLAN = 100
        private const val REQUEST_INSTALL_TERRAIN = 101
        private const val ACTION_USB_PERMISSION = "jauler.telemetry.USB_PERMISSION_BINDING_PHRASE"
    }

    /** The running data service, if any: its USB connection is used to apply a binding phrase. */
    private var dataService: DataService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            dataService = (binder as DataService.DataBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dataService = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefManager: PreferenceManager
    private lateinit var flightPlanManager: FlightPlanManager
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateSummary()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "settings"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        prefManager = PreferenceManager(context!!)
        flightPlanManager = FlightPlanManager(context!!)

        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        findPreference("copy_debug_info").setOnPreferenceClickListener {
            context?.let {
                val clipboardManager =
                    it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(null, FileLogger(it).copyLogFile()))
                } catch (e: IOException) {
                    Toast.makeText(it, "No log data available yet", Toast.LENGTH_SHORT).show()
                    return@let
                }
                Toast.makeText(it, "Debug data has been copied", Toast.LENGTH_SHORT).show()
            }
            return@setOnPreferenceClickListener false
        }
        findPreference("clear_debug_info").setOnPreferenceClickListener {
            context?.let {
                FileLogger(it).clearLogFile()
                Toast.makeText(it, "Debug data has been cleared", Toast.LENGTH_SHORT).show()
            }
            return@setOnPreferenceClickListener false
        }
        findPreference("import_flight_plan").setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(intent, "Select CSV file"), REQUEST_IMPORT_FLIGHT_PLAN)
            return@setOnPreferenceClickListener true
        }
        findPreference("manage_flight_plans").setOnPreferenceClickListener {
            showManageFlightPlansDialog()
            return@setOnPreferenceClickListener true
        }
        // Validate: display altitude ceiling must be >= warning altitude ceiling
        findPreference("fr24_warning_alt_ceiling").setOnPreferenceChangeListener { _, newValue ->
            val warningCeiling = (newValue as? String)?.toIntOrNull() ?: 1500
            val displayCeiling = prefManager.getFr24DisplayAltCeilingM()
            if (warningCeiling > displayCeiling) {
                val displayPref = findPreference("fr24_display_alt_ceiling") as? ListPreference
                displayPref?.value = newValue.toString()
                Toast.makeText(context, "Display ceiling raised to match warning ceiling", Toast.LENGTH_SHORT).show()
            }
            true
        }
        findPreference("fr24_display_alt_ceiling").setOnPreferenceChangeListener { _, newValue ->
            val displayCeiling = (newValue as? String)?.toIntOrNull() ?: 3000
            val warningCeiling = prefManager.getFr24WarningAltCeilingM()
            if (displayCeiling < warningCeiling) {
                Toast.makeText(context, "Display ceiling cannot be lower than warning ceiling ($warningCeiling m)", Toast.LENGTH_LONG).show()
                false
            } else {
                true
            }
        }

        bindNumberPreference(
            "terrain_height_limit", 120f, 1f, 10000f,
            "Height above the ground the aircraft is allowed to reach (120 m in the EU open category)"
        )
        bindNumberPreference(
            "terrain_antenna_height", 2f, 0f, 5000f,
            "Height of the antenna the aircraft talks to (e.g. a repeater above the pilot); used for " +
                    "the minimum line of sight altitude"
        )
        bindNumberPreference(
            "terrain_los_warn_min_distance", 200f, 0f, 100000f,
            "The line of sight readout does not turn red while the aircraft is closer than this to the home point"
        )
        findPreference("terrain_info").setOnPreferenceClickListener {
            showTerrainDialog()
            return@setOnPreferenceClickListener true
        }
        updateTerrainInfo()

        findPreference("elrs_binding_phrases_manage").setOnPreferenceClickListener {
            showBindingPhrasesDialog()
            return@setOnPreferenceClickListener true
        }
        findPreference("elrs_binding_phrase_apply").setOnPreferenceClickListener {
            showApplyBindingPhraseDialog()
            return@setOnPreferenceClickListener true
        }
        updateBindingPhraseSummaries()

        updateSummary()
    }

    override fun onResume() {
        super.onResume()
        updateTerrainInfo()
    }

    override fun onStart() {
        super.onStart()
        // only if it runs already: not connected, the port is opened here
        serviceBound = context!!.bindService(Intent(context, DataService::class.java), serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) context?.unbindService(serviceConnection)
        serviceBound = false
        dataService = null
    }

    // --- ELRS sniffer binding phrases ---

    private fun uidText(phrase: String): String = SnifferUid.format(SnifferUid.fromPhrase(phrase))

    private fun updateBindingPhraseSummaries() {
        val phrases = prefManager.getBindingPhrases()
        findPreference("elrs_binding_phrases_manage").summary =
            "Binding phrases of the pilots whose links the sniffer receiver can follow\n" +
                    when (phrases.size) {
                        0 -> "None saved yet. Tap to add one"
                        1 -> "1 saved. Tap to add, edit or delete"
                        else -> "${phrases.size} saved. Tap to add, edit or delete"
                    }
        val applied = prefManager.getAppliedBindingUid()
        val current = when {
            applied == null -> "Not applied from this phone yet"
            else -> {
                val name = phrases.firstOrNull { uidText(it.phrase) == applied }?.name
                "Last applied: ${name ?: "a phrase not in the list"} (UID $applied)"
            }
        }
        findPreference("elrs_binding_phrase_apply").summary =
            "Switch the sniffer receiver on the USB serial port to another pilot's link, " +
                    "without reflashing it\n$current"
    }

    private fun showBindingPhrasesDialog() {
        val phrases = prefManager.getBindingPhrases()
        val items = phrases.map { "${it.name}\nUID ${uidText(it.phrase)}" } + "Add a binding phrase"
        AlertDialog.Builder(context!!)
            .setTitle("Binding phrases")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == phrases.size) editBindingPhrase(-1) else showBindingPhraseOptions(which)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBindingPhraseOptions(index: Int) {
        val phrase = prefManager.getBindingPhrases().getOrNull(index) ?: return
        AlertDialog.Builder(context!!)
            .setTitle(phrase.name)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> editBindingPhrase(index)
                    1 -> AlertDialog.Builder(context!!)
                        .setTitle("Delete ${phrase.name}?")
                        .setPositiveButton("Delete") { _, _ ->
                            val phrases = prefManager.getBindingPhrases().toMutableList()
                            if (index < phrases.size && phrases[index] == phrase) {
                                phrases.removeAt(index)
                                prefManager.setBindingPhrases(phrases)
                            }
                            updateBindingPhraseSummaries()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Adds a binding phrase ([index] -1) or edits the one at [index]. */
    private fun editBindingPhrase(index: Int) {
        val ctx = context!!
        val existing = if (index >= 0) prefManager.getBindingPhrases().getOrNull(index) else null
        val padding = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(ctx)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(padding, padding / 2, padding, 0)
        val nameEdit = EditText(ctx)
        nameEdit.hint = "Name, e.g. the pilot's"
        nameEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        nameEdit.setText(existing?.name ?: "")
        val phraseEdit = EditText(ctx)
        phraseEdit.hint = "Binding phrase"
        // no suggestions: the keyboard should not learn it
        phraseEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        phraseEdit.setText(existing?.phrase ?: "")
        val uidView = TextView(ctx)
        uidView.setPadding(padding / 4, padding / 4, 0, 0)
        fun refreshUid() {
            val text = phraseEdit.text.toString()
            uidView.text = if (text.isBlank()) "UID: -" else "UID: ${uidText(text)}"
        }
        refreshUid()
        phraseEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshUid()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        layout.addView(nameEdit)
        layout.addView(phraseEdit)
        layout.addView(uidView)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Add a binding phrase" else "Edit binding phrase")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            // checked here, so that a wrong entry keeps the dialog open
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                val phrase = phraseEdit.text.toString().trim()
                val error = when {
                    name.isEmpty() -> "Enter a name"
                    phrase.isEmpty() -> "Enter the binding phrase"
                    !SnifferUid.isUsable(SnifferUid.fromPhrase(phrase)) -> "This UID is all zero, it cannot be used"
                    else -> null
                }
                if (error != null) {
                    Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val phrases = prefManager.getBindingPhrases().toMutableList()
                val entry = PreferenceManager.BindingPhrase(name, phrase)
                if (existing != null && index < phrases.size && phrases[index] == existing) {
                    phrases[index] = entry
                } else {
                    phrases.add(entry)
                }
                prefManager.setBindingPhrases(phrases)
                updateBindingPhraseSummaries()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showApplyBindingPhraseDialog() {
        val phrases = prefManager.getBindingPhrases()
        if (phrases.isEmpty()) {
            Toast.makeText(context, "Add a binding phrase first", Toast.LENGTH_SHORT).show()
            return
        }
        val applied = prefManager.getAppliedBindingUid()
        val items = phrases.map {
            val uid = uidText(it.phrase)
            "${it.name}${if (uid == applied) " (last applied)" else ""}\nUID $uid"
        }
        AlertDialog.Builder(context!!)
            .setTitle("Apply binding phrase")
            .setItems(items.toTypedArray()) { _, which -> applyBindingPhrase(phrases[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Over the USB connection of the telemetry if there is one, otherwise the USB serial port is
     * opened just for this.
     */
    private fun applyBindingPhrase(phrase: PreferenceManager.BindingPhrase) {
        val service = dataService
        val connectedLink = service?.usbSerialLink()
        if (connectedLink != null) {
            runBindingChange(phrase, connectedLink, null, service)
            return
        }
        openUsbSerial { link -> runBindingChange(phrase, link, link, null) }
    }

    /** Opens the first USB serial port, asking for the permission first if needed. */
    private fun openUsbSerial(onOpen: (UsbSerialLink) -> Unit) {
        val ctx = context ?: return
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        val port = driver?.ports?.firstOrNull()
        if (driver == null || port == null) {
            Toast.makeText(ctx, "No USB serial adapter found. Plug in the adapter with the sniffer receiver",
                Toast.LENGTH_LONG).show()
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            val appContext = ctx.applicationContext
            appContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    appContext.unregisterReceiver(this)
                    if (!isAdded) return
                    if (intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true) {
                        openUsbSerial(onOpen)
                    } else {
                        Toast.makeText(appContext, "The USB permission is needed to talk to the receiver",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }, IntentFilter(ACTION_USB_PERMISSION))
            usbManager.requestPermission(driver.device, PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION), 0))
            return
        }
        val link = try {
            val connection = usbManager.openDevice(driver.device) ?: throw java.io.IOException("cannot open the device")
            UsbSerialLink(port, connection, prefManager.getUsbSerialBaudrate())
        } catch (e: Exception) {
            Toast.makeText(ctx, "Cannot open the USB serial port: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        onOpen(link)
    }

    private fun runBindingChange(
        phrase: PreferenceManager.BindingPhrase,
        link: SerialLink,
        toClose: UsbSerialLink?,
        service: DataService?
    ) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext
        val uid = SnifferUid.fromPhrase(phrase.phrase)
        val dialog = ProgressDialog(ctx)
        dialog.setCancelable(false)
        dialog.setMessage("Switching the sniffer receiver to ${phrase.name}")
        dialog.show()
        Thread {
            val result = try {
                SnifferUidChanger(link).apply(uid)
            } catch (e: Exception) {
                SnifferUidChanger.Result.Failed("Cannot talk to the receiver: ${e.message}")
            } finally {
                toClose?.close()
            }
            handler.post {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                }
                when (result) {
                    is SnifferUidChanger.Result.Done -> prefManager.setAppliedBindingUid(SnifferUid.format(result.uid))
                    is SnifferUidChanger.Result.Refused -> prefManager.setAppliedBindingUid(SnifferUid.format(result.uid))
                    else -> {}
                }
                // another pilot: another aircraft, which has not taken off where the last one did
                if (result is SnifferUidChanger.Result.Done && result.changed) service?.onSnifferLinkChanged()
                if (!isAdded) {
                    Toast.makeText(appContext, bindingResultMessage(phrase, result), Toast.LENGTH_LONG).show()
                    return@post
                }
                updateBindingPhraseSummaries()
                AlertDialog.Builder(context!!)
                    .setTitle(if (result is SnifferUidChanger.Result.Done) phrase.name else "Binding phrase not applied")
                    .setMessage(bindingResultMessage(phrase, result))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun bindingResultMessage(phrase: PreferenceManager.BindingPhrase, result: SnifferUidChanger.Result): String {
        return when (result) {
            is SnifferUidChanger.Result.Done ->
                if (result.changed) {
                    "The sniffer receiver switched to ${phrase.name} (UID ${SnifferUid.format(result.uid)}) and " +
                            "is looking for that link. The telemetry comes once the pilot's radio and the " +
                            "aircraft are on, usually within a few seconds."
                } else {
                    "The sniffer receiver already follows ${phrase.name} (UID ${SnifferUid.format(result.uid)})."
                }
            is SnifferUidChanger.Result.Refused ->
                "The sniffer receiver refused the change and still uses UID ${SnifferUid.format(result.uid)}. " +
                        "It does so while it is in WiFi or firmware update mode."
            is SnifferUidChanger.Result.NoReply ->
                "The sniffer receiver does not answer. Check the wire from the adapter's TX to the receiver's " +
                        "RX, the USB serial baudrate (${prefManager.getUsbSerialBaudrate()}, the receiver's is " +
                        "usually 420000), and that the receiver runs sniffer firmware which can change the " +
                        "binding phrase."
            is SnifferUidChanger.Result.Failed -> result.message
        }
    }

    private fun formatNumber(value: Float): String {
        return if (value == Math.rint(value.toDouble()).toFloat()) value.toInt().toString() else value.toString()
    }

    /**
     * Turns a plain preference into a "type a number of meters" one. The value is stored as a
     * string, like the values of the list preferences.
     */
    private fun bindNumberPreference(key: String, default: Float, min: Float, max: Float, description: String) {
        val preference = findPreference(key)
        val prefs = preferenceManager.sharedPreferences

        fun current(): Float {
            return prefs.getString(key, null)?.replace(',', '.')?.toFloatOrNull() ?: default
        }

        fun refresh() {
            preference.summary = "$description\n${formatNumber(current())} m"
        }

        refresh()
        preference.setOnPreferenceClickListener {
            val editText = EditText(context)
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.setText(formatNumber(current()))
            editText.setSelection(editText.text.length)
            AlertDialog.Builder(context!!)
                .setTitle("${preference.title}, m")
                .setView(editText)
                .setPositiveButton("OK") { _, _ ->
                    val value = editText.text.toString().replace(',', '.').toFloatOrNull()
                    if (value == null || value < min || value > max) {
                        Toast.makeText(
                            context,
                            "Enter a value between ${formatNumber(min)} and ${formatNumber(max)}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        prefs.edit().putString(key, formatNumber(value)).apply()
                        refresh()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return@setOnPreferenceClickListener true
        }
    }

    private fun updateTerrainInfo() {
        val ctx = context ?: return
        val preference = findPreference("terrain_info")
        val model = try {
            TerrainLoader.open(ctx)
        } catch (e: Exception) {
            null
        }
        if (model != null) {
            preference.summary = model.description + "\nTap to replace or remove"
            model.close()
        } else {
            preference.summary = "Not installed. Height above ground, altitude limit and line of sight " +
                    "need it.\nTap to install it from a terrain pack file (*.tpk) on this phone"
        }
    }

    private fun megabytes(bytes: Long): Long {
        return (bytes + 500000L) / 1000000L
    }

    private fun pickTerrainFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(
            Intent.createChooser(intent, "Select terrain pack (*.tpk)"), REQUEST_INSTALL_TERRAIN
        )
    }

    private fun showTerrainDialog() {
        val ctx = context ?: return
        if (!TerrainInstaller.isInstalled(ctx)) {
            pickTerrainFile()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Terrain data")
            .setItems(arrayOf("Install from another file", "Remove the installed data")) { _, which ->
                when (which) {
                    0 -> pickTerrainFile()
                    1 -> {
                        TerrainInstaller.remove(ctx.applicationContext)
                        Toast.makeText(ctx, "Terrain data removed", Toast.LENGTH_SHORT).show()
                        updateTerrainInfo()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Copies the picked file into place on a background thread, with a progress dialog. */
    private fun installTerrainFrom(uri: Uri) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext
        var size = -1L
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) size = it.getLong(0)
            }
        } catch (e: Exception) {
        }

        val dialog = ProgressDialog(ctx)
        dialog.setCancelable(false)
        dialog.setMessage("Installing terrain data")
        if (size > 0) {
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            dialog.max = megabytes(size).toInt()
            dialog.setProgressNumberFormat("%1d of %2d MB")
        }
        dialog.show()

        Thread {
            val message = try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("cannot open the file")
                val description = TerrainInstaller.installFromStream(appContext, input) { copied ->
                    handler.post { dialog.progress = megabytes(copied).toInt() }
                }
                "Terrain data installed: $description"
            } catch (e: Exception) {
                "Cannot install terrain data: ${e.message}"
            }
            handler.post {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                }
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                if (isAdded) updateTerrainInfo()
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            context!!.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: uri.lastPathSegment ?: "Flight plan"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_TERRAIN && resultCode == Activity.RESULT_OK) {
            data?.data?.let { installTerrainFrom(it) }
            return
        }
        if (requestCode == REQUEST_IMPORT_FLIGHT_PLAN && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val fileName = displayName(uri)
                    val inputStream = context!!.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val plan = flightPlanManager.importFromCsv(inputStream, fileName)
                        if (plan != null) {
                            Toast.makeText(context, "Imported \"${plan.name}\" with ${plan.waypoints.size} waypoints", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid CSV: need at least 2 waypoints (lat,lon per line)", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showManageFlightPlansDialog() {
        val plans = flightPlanManager.getPlans()
        if (plans.isEmpty()) {
            Toast.makeText(context, "No flight plans imported", Toast.LENGTH_SHORT).show()
            return
        }

        val names = plans.map { "${it.name} (${it.waypoints.size} pts)${if (!it.visible) " [hidden]" else ""}" }.toTypedArray()
        AlertDialog.Builder(context!!)
            .setTitle("Flight Plans")
            .setItems(names) { _, which ->
                showFlightPlanOptionsDialog(plans[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFlightPlanOptionsDialog(plan: FlightPlanManager.FlightPlan) {
        val visibilityLabel = if (plan.visible) "Hide" else "Show"
        val options = arrayOf(visibilityLabel, "Delete", "Rename")
        AlertDialog.Builder(context!!)
            .setTitle(plan.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        flightPlanManager.updatePlanVisibility(plan.id, !plan.visible)
                        Toast.makeText(context, if (!plan.visible) "Plan shown" else "Plan hidden", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        AlertDialog.Builder(context!!)
                            .setTitle("Delete ${plan.name}?")
                            .setPositiveButton("Delete") { _, _ ->
                                flightPlanManager.deletePlan(plan.id)
                                Toast.makeText(context, "Plan deleted", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        val editText = EditText(context)
                        editText.setText(plan.name)
                        editText.setSelection(editText.text.length)
                        AlertDialog.Builder(context!!)
                            .setTitle("Rename flight plan")
                            .setView(editText)
                            .setPositiveButton("Rename") { _, _ ->
                                val name = editText.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    flightPlanManager.renamePlan(plan.id, name)
                                    Toast.makeText(context, "Plan renamed", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun updateSummary() {
    }
}
