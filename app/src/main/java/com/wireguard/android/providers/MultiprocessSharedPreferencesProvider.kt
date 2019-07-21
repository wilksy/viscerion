/*
 * Copyright © 2017-2019 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("DEPRECATION", "UNCHECKED_CAST") // androidx.preference hides a method that we need here.
package com.wireguard.android.providers

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.UriMatcher
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.Base64
import androidx.collection.ArrayMap
import androidx.core.util.Pair
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import org.json.JSONArray
import org.json.JSONException
import timber.log.Timber
import java.util.ArrayList
import java.util.HashSet

/**
 *  Multi-process compatible SharedPreferences provider for use in applications that utilise multiple
 *  processes to improve memory usage. Original written by Copyright (C) 2016 Jorge Ruesga in Java.
 */
class MultiprocessSharedPreferencesProvider : ContentProvider() {

    private var mContext: Context? = null
    private val mPreferences = ArrayMap<String, SharedPreferences>()

    override fun onCreate(): Boolean {
        mContext = if (context is Activity) context!!.applicationContext else context
        return false
    }

    @Synchronized
    private fun getSharedPreferences(uri: Uri): SharedPreferences? {
        val name = decodePath(uri.pathSegments[1])
        if (!mPreferences.containsKey(name)) {
            mPreferences[name] = mContext!!.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        return mPreferences[name]
    }

    override fun getType(uri: Uri): String? {
        val match = urlMatcher.match(uri)
        return if (match == PREFERENCES_DATA) {
            "vnd.android.cursor.dir/$PREFERENCES_ENTITY"
        } else "vnd.android.cursor.item/$PREFERENCES_ENTITY"
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {

        var c: MatrixCursor? = null
        when (urlMatcher.match(uri)) {
            PREFERENCES_DATA -> {
                val map = getSharedPreferences(uri)!!.all
                c = MatrixCursor(PROJECTION)
                for (key in map.keys) {
                    val row = c.newRow()
                    row.add(key)
                    val columnValue = map[key]
                    if (columnValue is Set<*>) {
                        row.add(marshallSet((columnValue as Set<String>?)!!))
                    } else {
                        row.add(columnValue)
                    }
                }
            }

            PREFERENCES_DATA_ID -> {
                val key = decodePath(uri.pathSegments[3])
                val map = getSharedPreferences(uri)!!.all
                if (map.containsKey(key)) {
                    c = MatrixCursor(PROJECTION)
                    val row = c.newRow()
                    row.add(key)
                    val columnValue = map[key]
                    if (columnValue is Set<*>) {
                        row.add(marshallSet(columnValue as Set<String>))
                    } else {
                        row.add(columnValue)
                    }
                }
            }
        }

        c?.setNotificationUri(context?.contentResolver, uri)
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {

        var key: String? = null
        val match = urlMatcher.match(uri)
        var count = 0
        if (match == PREFERENCES_DATA) {
            val editor = getSharedPreferences(uri)!!.edit()
            key = values!!.get(FIELD_KEY) as String
            val value = values.get(FIELD_VALUE)
            if (value != null) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> // Test if the preference is a json array
                        try {
                            editor.putStringSet(key, unmarshallSet(value as String))
                        } catch (e: JSONException) {
                            editor.putString(key, value as String)
                        }
                }
            } else {
                editor.remove(key)
            }
            editor.apply()
            count = 1
        } else {
            Timber.d("Cannot insert URI: %s", uri)
        }

        // Notify
        if (count > 0) {
            val notifyUri = uri.buildUpon().appendPath(encodePath(key!!)).build()
            notifyChange(notifyUri)
            return notifyUri
        }
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        var count = 0
        when (urlMatcher.match(uri)) {
            PREFERENCES_DATA -> {
                count = getSharedPreferences(uri)!!.all.size
                getSharedPreferences(uri)!!.edit().clear().apply()
            }
            PREFERENCES_DATA_ID -> {
                val key = decodePath(uri.pathSegments[3])
                if (getSharedPreferences(uri)!!.contains(key)) {
                    getSharedPreferences(uri)!!.edit().remove(key).apply()
                    count = 0
                }
            }
            else -> Timber.d("Cannot delete URI: %s", uri)
        }

        if (count > 0) {
            notifyChange(uri)
        }
        return count
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        var count = 0
        val match = urlMatcher.match(uri)
        if (match == PREFERENCES_DATA_ID) {
            val editor = getSharedPreferences(uri)!!.edit()
            val key = decodePath(uri.pathSegments[3])
            val value = values!!.get(FIELD_VALUE)
            if (value != null) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> // Test if the preference is a json array
                        try {
                            editor.putStringSet(key, unmarshallSet(value as String))
                        } catch (e: JSONException) {
                            editor.putString(key, value as String)
                        }
                }
            } else {
                editor.remove(key)
            }
            count = 1
            editor.apply()
        } else {
            Timber.d("Cannot update URI: %s", uri)
        }

        if (count > 0) {
            notifyChange(uri)
        }

        return count
    }

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    class Pref(private val context: Context?, val sharedPreferencesName: String) : SharedPreferences {

        private val observer = object : ContentObserver(Application.APP_PROCESS_HANDLER) {
            override fun deliverSelfNotifications(): Boolean {
                return false
            }

            override fun onChange(selfChange: Boolean, uri: Uri) {
                val name = decodePath(uri.pathSegments[1])
                if (name == sharedPreferencesName) {
                    val key = decodePath(uri.lastPathSegment!!)
                    for (cb in listeners) {
                        cb.onSharedPreferenceChanged(this@Pref, key)
                    }
                }
            }
        }
        private var isObserving: Boolean = false
        private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

        private class MultiProcessEditor(private val context: Context?, private val mPreferencesFileName: String) : SharedPreferences.Editor {
            private val mValues: MutableList<Pair<String, Any>>
            private val removedEntries: MutableSet<String>
            private var mClearAllFlag: Boolean = false

            init {
                mValues = ArrayList()
                removedEntries = HashSet()
                mClearAllFlag = false
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                mValues.add(Pair(key, value))
                removedEntries.remove(key)
                return this
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                mValues.add(Pair(key, values))
                removedEntries.remove(key)
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                mValues.add(Pair(key, value))
                removedEntries.remove(key)
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                mValues.add(Pair(key, value))
                removedEntries.remove(key)
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                mValues.add(Pair(key, value))
                removedEntries.remove(key)
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                mValues.add(Pair(key, value))
                removedEntries.remove(key)
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                val it = mValues.iterator()
                while (it.hasNext()) {
                    if (it.next().first == key) {
                        it.remove()
                        break
                    }
                }
                removedEntries.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                mClearAllFlag = true
                removedEntries.clear()
                mValues.clear()
                return this
            }

            override fun commit(): Boolean {
                try {
                    if (mClearAllFlag) {
                        val uri = resolveUri(null, mPreferencesFileName)
                        context?.contentResolver?.delete(uri, null, null)
                    }
                    mClearAllFlag = false

                    val values = ContentValues()
                    for (v in mValues) {
                        val uri = resolveUri(v.first, mPreferencesFileName)
                        values.put(FIELD_KEY, v.first)
                        when {
                            v.second is Boolean -> values.put(FIELD_VALUE, v.second as Boolean)
                            v.second is Long -> values.put(FIELD_VALUE, v.second as Long)
                            v.second is Int -> values.put(FIELD_VALUE, v.second as Int)
                            v.second is Float -> values.put(FIELD_VALUE, v.second as Float)
                            v.second is String -> values.put(FIELD_VALUE, v.second as String)
                            v.second is Set<*> -> values.put(FIELD_VALUE, marshallSet((v.second as Set<String>)))
                            else -> return false
                        }
                        context?.contentResolver?.update(uri, values, null, null)
                    }

                    for (key in removedEntries) {
                        val uri = resolveUri(key, mPreferencesFileName)
                        context?.contentResolver?.delete(uri, null, null)
                    }

                    return true
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                    return false
                }
            }

            override fun apply() {
                AsyncTask.SERIAL_EXECUTOR.execute { this.commit() }
            }
        }

        init {
            val uri = CONTENT_URI.buildUpon().appendPath(PREFERENCES_ENTITY).build()
            context?.contentResolver?.registerContentObserver(uri, true, observer)
            isObserving = true
        }

        @Throws(Throwable::class)
        @Override
        protected fun finalize() {
            if (isObserving) {
                context?.contentResolver?.unregisterContentObserver(observer)
                isObserving = false
            }
        }

        override fun getAll(): Map<String, *> {
            val values = ArrayMap<String, Any>()

            try {
                context?.contentResolver?.query(
                        resolveUri(null, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                    while (c != null && c.moveToNext()) {
                        val key = c.getString(c.getColumnIndexOrThrow(FIELD_KEY))
                        val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                        var value: Any? = null
                        if (type == Cursor.FIELD_TYPE_INTEGER) {
                            value = c.getLong(c.getColumnIndexOrThrow(FIELD_VALUE))
                        } else if (type == Cursor.FIELD_TYPE_FLOAT) {
                            value = c.getFloat(c.getColumnIndexOrThrow(FIELD_VALUE))
                        } else if (type == Cursor.FIELD_TYPE_STRING) {
                            val v = c.getString(c.getColumnIndexOrThrow(FIELD_VALUE))
                            if (v == "true" || v == "false") {
                                value = v.toBoolean()
                            } else {
                                try {
                                    value = unmarshallSet(v)
                                } catch (e: JSONException) {
                                    value = v
                                }
                            }
                        }

                        values[key] = value
                    }
                }
            } catch (ignored: Exception) {
            }

            return values
        }

        override fun getString(key: String, defValue: String?): String? {
            if (context == null) {
                return ""
            }

            context.contentResolver?.query(
                    resolveUri(key, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return defValue
                }
                val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                return if (type != Cursor.FIELD_TYPE_STRING) {
                    defValue
                } else c.getString(c.getColumnIndexOrThrow(FIELD_VALUE))
            }
        }

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            if (context == null) {
                return HashSet()
            }

            context.contentResolver?.query(
                    resolveUri(key, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return defValues
                }
                val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                if (type != Cursor.FIELD_TYPE_STRING) {
                    return defValues
                }

                try {
                    val v = c.getString(c.getColumnIndexOrThrow(FIELD_VALUE))
                    val array = JSONArray(v)
                    val size = array.length()
                    val set = HashSet<String>(size)
                    for (i in 0 until size) {
                        set.add(array.getString(i))
                    }
                    return set
                } catch (ignored: JSONException) {
                }

                return defValues
            }
        }

        override fun getInt(key: String, defValue: Int): Int {
            if (context == null) {
                return 0
            }

            context.contentResolver?.query(
                    resolveUri(key, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return defValue
                }
                val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                return if (type != Cursor.FIELD_TYPE_INTEGER) {
                    defValue
                } else c.getInt(c.getColumnIndexOrThrow(FIELD_VALUE))
            }
        }

        override fun getLong(key: String, defValue: Long): Long {
            if (context == null) {
                return 0L
            }

            context.contentResolver?.query(
                    resolveUri(key, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return defValue
                }
                val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                return if (type != Cursor.FIELD_TYPE_INTEGER) {
                    defValue
                } else c.getLong(c.getColumnIndexOrThrow(FIELD_VALUE))
            }
        }

        override fun getFloat(key: String, defValue: Float): Float {
            if (context == null) {
                return 0f
            }

            context.contentResolver?.query(
                    resolveUri(key, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return defValue
                }
                val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                return if (type != Cursor.FIELD_TYPE_FLOAT) {
                    defValue
                } else c.getFloat(c.getColumnIndexOrThrow(FIELD_VALUE))
            }
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            if (context == null) {
                return false
            }

            context.contentResolver?.query(
                    resolveUri(key, sharedPreferencesName), PROJECTION, null, null, null).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return defValue
                }
                val type = c.getType(c.getColumnIndexOrThrow(FIELD_VALUE))
                return if (type != Cursor.FIELD_TYPE_STRING) {
                    defValue
                } else java.lang.Boolean.parseBoolean(c.getString(c.getColumnIndexOrThrow(FIELD_VALUE)))
            }
        }

        override fun contains(key: String): Boolean {
            return all.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor {
            return MultiProcessEditor(context, sharedPreferencesName)
        }

        override fun registerOnSharedPreferenceChangeListener(cb: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(cb)
        }

        override fun unregisterOnSharedPreferenceChangeListener(cb: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(cb)
        }
    }

    companion object {
        private const val AUTHORITY = BuildConfig.APPLICATION_ID + ".prefs_provider"

        private const val PREFERENCES_ENTITY = "preferences"
        private const val PREFERENCE_ENTITY = "preference"

        private val CONTENT_URI = Uri.parse("content://$AUTHORITY")

        private const val PREFERENCES_DATA = 1
        private const val PREFERENCES_DATA_ID = 2

        private val urlMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY,
                    "$PREFERENCES_ENTITY/*/$PREFERENCE_ENTITY",
                    PREFERENCES_DATA
            )
            addURI(AUTHORITY,
                    "$PREFERENCES_ENTITY/*/$PREFERENCE_ENTITY/*",
                    PREFERENCES_DATA_ID
            )
        }

        private const val FIELD_KEY = "key"
        private const val FIELD_VALUE = "value"

        private val PROJECTION = arrayOf(FIELD_KEY, FIELD_VALUE)

        private fun marshallSet(set: Set<String>): String {
            val array = JSONArray()
            for (value in set) {
                array.put(value)
            }
            return array.toString()
        }

        @Throws(JSONException::class)
        private fun unmarshallSet(value: String): Set<String> {
            val array = JSONArray(value)
            val size = array.length()
            val set = HashSet<String>(size)
            for (i in 0 until size) {
                set.add(array.getString(i))
            }
            return set
        }

        private val sInstances = ArrayMap<String, Pref>()

        fun getDefaultSharedPreferences(context: Context): Pref? {
            val defaultName: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PreferenceManager.getDefaultSharedPreferencesName(context)
            } else {
                context.packageName + "_preferences"
            }
            return getSharedPreferences(context, defaultName)
        }

        private fun getSharedPreferences(context: Context, name: String): Pref? {
            synchronized(sInstances) {
                if (!sInstances.containsKey(name)) {
                    sInstances[name] = Pref(
                            if (context is Activity) context.getApplicationContext() else context, name)
                }

                return sInstances[name]
            }
        }

        private fun encodePath(path: String): String {
            return String(Base64.encode(path.toByteArray(), Base64.NO_WRAP))
        }

        private fun decodePath(path: String): String {
            return String(Base64.decode(path.toByteArray(), Base64.NO_WRAP))
        }

        fun resolveUri(key: String?, prefFileName: String): Uri {
            var builder: Uri.Builder = CONTENT_URI.buildUpon()
                    .appendPath(PREFERENCES_ENTITY)
                    .appendPath(encodePath(prefFileName))
                    .appendPath(PREFERENCE_ENTITY)
            if (!TextUtils.isEmpty(key)) {
                builder = builder.appendPath(encodePath(key!!))
            }
            return builder.build()
        }
    }
}
