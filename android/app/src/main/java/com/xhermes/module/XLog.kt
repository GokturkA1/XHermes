package com.xhermes.module

import android.util.Log

object XLog {
    const val TAG = "XHermes"

    fun i(msg: String) { Log.i(TAG, msg) }
    fun d(msg: String) { Log.d(TAG, msg) }
    fun w(msg: String) { Log.w(TAG, msg) }
    fun e(msg: String) { Log.e(TAG, msg) }

    fun i(msg: String, t: Throwable?) { Log.i(TAG, msg, t) }
    fun d(msg: String, t: Throwable?) { Log.d(TAG, msg, t) }
    fun w(msg: String, t: Throwable?) { Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable?) { Log.e(TAG, msg, t) }

    fun i(tag: String, msg: String) { Log.i(TAG, msg) }
    fun d(tag: String, msg: String) { Log.d(TAG, msg) }
    fun w(tag: String, msg: String) { Log.w(TAG, msg) }
    fun e(tag: String, msg: String) { Log.e(TAG, msg) }

    fun i(tag: String, msg: String, t: Throwable?) { Log.i(TAG, msg, t) }
    fun d(tag: String, msg: String, t: Throwable?) { Log.d(TAG, msg, t) }
    fun w(tag: String, msg: String, t: Throwable?) { Log.w(TAG, msg, t) }
    fun e(tag: String, msg: String, t: Throwable?) { Log.e(TAG, msg, t) }
}
