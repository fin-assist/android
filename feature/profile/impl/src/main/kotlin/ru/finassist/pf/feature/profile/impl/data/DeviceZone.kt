package ru.finassist.pf.feature.profile.impl.data

import java.util.TimeZone
import javax.inject.Inject

/** The device time zone id, read anew on every call (the system updates the default zone on a change). */
interface DeviceZone {
    fun id(): String

    /**
     * Drops the process's cached default zone. The system resets it on a change by a separate call to the app, in no
     * set order with `ACTION_TIMEZONE_CHANGED`; a check that ran before that reset would still see the old zone.
     */
    fun reset()
}

class SystemDeviceZone @Inject constructor() : DeviceZone {
    override fun id(): String = TimeZone.getDefault().id

    // What the system itself does on a change: the next getDefault() reads the zone setting again.
    override fun reset() = TimeZone.setDefault(null)
}
