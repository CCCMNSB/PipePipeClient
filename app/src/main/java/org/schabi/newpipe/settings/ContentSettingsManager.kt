package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.zip.ZipOutputStream

class ContentSettingsManager(private val fileLocator: NewPipeFileLocator) {
    companion object {
        const val TAG = "ContentSetManager"
        const val PIPEPIPE_PREFERENCES_KEY = "pipepipe_backup_preferences_key"
    }

    private var legacySettings = false

    /**
     * Exports given [SharedPreferences] to the file in given outputPath.
     * It also creates the file.
     */
    @Throws(Exception::class)
    fun exportDatabase(preferences: SharedPreferences, file: StoredFileHelper) {
        writeBackup(preferences.all, fileLocator.db.path, file)
    }

    @Throws(Exception::class)
    fun exportDatabaseToNewPipe(preferences: SharedPreferences, file: StoredFileHelper) {
        fileLocator.db.copyTo(fileLocator.newPipeDb, true)
        prepareNewPipeDatabase()
        try {
            writeBackup(getNewPipePreferences(preferences), fileLocator.newPipeDb.path, file)
        } finally {
            fileLocator.newPipeDb.delete()
        }
    }

    fun deleteSettingsFile() {
        fileLocator.settings.delete()
        fileLocator.newPipeDb.delete()
    }

    /**
     * Tries to create database directory if it does not exist.
     *
     * @return Whether the directory exists afterwards.
     */
    fun ensureDbDirectoryExists(): Boolean {
        return fileLocator.dbDir.exists() || fileLocator.dbDir.mkdir()
    }

    fun extractDb(file: StoredFileHelper): Boolean {
        val success = ZipHelper.extractFileFromZip(file, fileLocator.db.path, "newpipe.db")
        if (success) {
            fileLocator.dbJournal.delete()
            fileLocator.dbWal.delete()
            fileLocator.dbShm.delete()
        }

        return success
    }

    fun extractSettings(file: StoredFileHelper): Boolean {
        legacySettings = false
        if (ZipHelper.extractFileFromZip(file, fileLocator.settings.path, "preferences.json")) {
            return true
        }

        legacySettings = ZipHelper.extractFileFromZip(
            file,
            fileLocator.settings.path,
            "newpipe.settings"
        )
        return legacySettings
    }

    fun loadSharedPreferences(preferences: SharedPreferences) {
        try {
            val preferenceEditor = preferences.edit()

            FileInputStream(fileLocator.settings).use { input ->
                preferenceEditor.clear()
                @Suppress("UNCHECKED_CAST")
                val importedEntries = if (legacySettings) {
                    PreferencesObjectInputStream(input).use { it.readObject() as Map<String, *> }
                } else {
                    JsonParser.`object`().from(input)
                }
                val entries = (importedEntries[PIPEPIPE_PREFERENCES_KEY] as? String)?.let {
                    JsonParser.`object`().from(it)
                } ?: importedEntries
                for ((key, value) in entries) {
                    when (value) {
                        is Boolean -> {
                            preferenceEditor.putBoolean(key, value)
                        }
                        is Float -> {
                            preferenceEditor.putFloat(key, value)
                        }
                        is Double -> {
                            preferenceEditor.putFloat(key, value.toFloat())
                        }
                        is Int -> {
                            preferenceEditor.putInt(key, value)
                        }
                        is Long -> {
                            preferenceEditor.putLong(key, value)
                        }
                        is String -> {
                            preferenceEditor.putString(key, value)
                        }
                        is JsonArray -> {
                            preferenceEditor.putStringSet(
                                key,
                                value.mapNotNull { entry -> entry as? String }.toSet()
                            )
                        }
                        is Set<*> -> {
                            preferenceEditor.putStringSet(
                                key,
                                value.mapNotNull { entry -> entry as? String }.toSet()
                            )
                        }
                    }
                }
                preferenceEditor.commit()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        }
    }

    private class PreferencesObjectInputStream(stream: InputStream) : ObjectInputStream(stream) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            if (desc.name !in classWhitelist) {
                throw ClassNotFoundException("Class not allowed: ${desc.name}")
            }
            return super.resolveClass(desc)
        }

        companion object {
            private val classWhitelist = setOf(
                "java.lang.Boolean",
                "java.lang.Byte",
                "java.lang.Character",
                "java.lang.Short",
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Float",
                "java.lang.Double",
                "java.lang.Void",
                "java.util.HashMap",
                "java.util.HashSet"
            )
        }
    }

    private fun prepareNewPipeDatabase() {
        val database = SQLiteDatabase.openDatabase(
            fileLocator.newPipeDb.path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        database.setForeignKeyConstraintsEnabled(false)
        database.beginTransaction()
        try {
            database.execSQL(
                "CREATE TABLE pipepipe_backup_metadata " +
                    "(entity TEXT NOT NULL, uid INTEGER NOT NULL, value TEXT, " +
                    "PRIMARY KEY(entity, uid))"
            )
            database.execSQL(
                "INSERT INTO pipepipe_backup_metadata " +
                    "SELECT 'playlist_thumbnail', uid, thumbnail_url FROM playlists"
            )
            database.execSQL(
                "INSERT INTO pipepipe_backup_metadata " +
                    "SELECT 'stream_is_paid', uid, is_paid FROM streams WHERE is_paid != 0"
            )
            database.execSQL(
                "CREATE TABLE streams_new (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "service_id INTEGER NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, " +
                    "stream_type TEXT NOT NULL, duration INTEGER NOT NULL, uploader TEXT NOT NULL, " +
                    "uploader_url TEXT, thumbnail_url TEXT, view_count INTEGER, " +
                    "textual_upload_date TEXT, upload_date INTEGER, " +
                    "is_upload_date_approximation INTEGER)"
            )
            database.execSQL(
                "INSERT INTO streams_new SELECT uid, service_id, url, title, stream_type, " +
                    "duration, uploader, uploader_url, thumbnail_url, view_count, " +
                    "textual_upload_date, upload_date, is_upload_date_approximation FROM streams"
            )
            database.execSQL("DROP TABLE streams")
            database.execSQL("ALTER TABLE streams_new RENAME TO streams")
            database.execSQL(
                "CREATE UNIQUE INDEX index_streams_service_id_url ON streams (service_id, url)"
            )
            database.execSQL(
                "CREATE TABLE playlists_new (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT, is_thumbnail_permanent INTEGER NOT NULL, " +
                    "thumbnail_stream_id INTEGER NOT NULL, display_index INTEGER NOT NULL)"
            )
            database.execSQL(
                "INSERT INTO playlists_new SELECT p.uid, p.name, 0, " +
                    "COALESCE((SELECT s.uid FROM streams s " +
                    "WHERE s.thumbnail_url = p.thumbnail_url LIMIT 1), -1), p.display_index " +
                    "FROM playlists p"
            )
            database.execSQL("DROP TABLE playlists")
            database.execSQL("ALTER TABLE playlists_new RENAME TO playlists")
            database.execSQL(
                "CREATE TABLE remote_playlists_new (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "service_id INTEGER NOT NULL, name TEXT, url TEXT, thumbnail_url TEXT, " +
                    "uploader TEXT, display_index INTEGER NOT NULL, stream_count INTEGER)"
            )
            database.execSQL(
                "INSERT INTO remote_playlists_new SELECT uid, service_id, name, url, " +
                    "thumbnail_url, uploader, display_index, stream_count FROM remote_playlists"
            )
            database.execSQL("DROP TABLE remote_playlists")
            database.execSQL("ALTER TABLE remote_playlists_new RENAME TO remote_playlists")
            database.execSQL(
                "CREATE UNIQUE INDEX index_remote_playlists_service_id_url " +
                    "ON remote_playlists (service_id, url)"
            )
            database.execSQL(
                "UPDATE room_master_table SET identity_hash = " +
                    "'7591e8039faa74d8c0517dc867af9d3e' WHERE id = 42"
            )
            database.execSQL("PRAGMA user_version = 9")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            database.close()
        }
    }

    private fun getNewPipePreferences(preferences: SharedPreferences): Map<String, *> {
        val exportedPreferences = preferences.all.toMutableMap()
        exportedPreferences[PIPEPIPE_PREFERENCES_KEY] =
            JsonWriter.string().`object`(preferences.all).done()
        val savedTabs = exportedPreferences["saved_tabs_key"] as? String ?: return exportedPreferences
        val tabsObject = JsonParser.`object`().from(savedTabs)
        val tabs = tabsObject.getArray("tabs")
        tabs.removeAll { tab ->
            tab is JsonObject && tab.getInt("tab_id") == 5 && tab.getInt("service_id", -1) >= 5
        }
        exportedPreferences["saved_tabs_key"] = JsonWriter.string().`object`(tabsObject).done()
        return exportedPreferences
    }

    private fun writeBackup(
        preferences: Map<String, *>,
        databasePath: String,
        file: StoredFileHelper
    ) {
        file.create()
        ZipOutputStream(BufferedOutputStream(SharpOutputStream(file.stream))).use { outZip ->
            ZipHelper.addFileToZip(outZip, databasePath, "newpipe.db")

            try {
                FileOutputStream(fileLocator.settings).use { output ->
                    JsonWriter.indent("").on(output).`object`(preferences).done()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Unable to exportDatabase", e)
            }

            ZipHelper.addFileToZip(outZip, fileLocator.settings.path, "preferences.json")
        }
    }
}
