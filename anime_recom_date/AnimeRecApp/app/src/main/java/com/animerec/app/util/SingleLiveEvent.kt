/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 *
 * Developed by: Shuvam Banerji Seal
 * GitHub: https://github.com/technicallittlemaster
 *
 * This file is part of AnimeRec.
 * Licensed under the MIT License.
 */
package com.animerec.app.util

import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [MutableLiveData] that delivers each value to exactly one observer, once.
 *
 * Plain LiveData re-delivers its last value to every new observer, which is
 * right for state ("here is the current list") and wrong for events ("a swipe
 * was undone"). With plain LiveData a rotation replays the last event: the
 * toast fires again, the card stack rewinds again.
 *
 * Only one observer will be notified of any given change — register a single
 * observer per event stream.
 */
class SingleLiveEvent<T> : MutableLiveData<T>() {

    private val pending = AtomicBoolean(false)

    @MainThread
    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        super.observe(owner) { value ->
            if (pending.compareAndSet(true, false)) {
                observer.onChanged(value)
            }
        }
    }

    @MainThread
    override fun setValue(value: T?) {
        pending.set(true)
        super.setValue(value)
    }

    override fun postValue(value: T?) {
        pending.set(true)
        super.postValue(value)
    }

    /** Emit an event with no payload. */
    @MainThread
    fun call() {
        setValue(null)
    }
}
