package crazydude.com.telemetry.ui

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import crazydude.com.telemetry.R
import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.manager.PreferenceManager
import androidx.preference.ListPreference
import crazydude.com.telemetry.terrain.TerrainLoader
import crazydude.com.telemetry.utils.FileLogger
import java.io.IOException

class PrefsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val REQUEST_IMPORT_FLIGHT_PLAN = 100
    }

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
        updateTerrainInfo()

        updateSummary()
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
            preference.summary = model.description
            model.close()
        } else {
            val dir = TerrainLoader.userDirectory(ctx)
            preference.summary = "Not installed. Height above ground, altitude limit and line of sight " +
                    "need a terrain pack (*.tpk)" + (if (dir != null) " in\n${dir.absolutePath}" else "")
        }
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
