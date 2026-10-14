package ru.finassist.pf.feature.profile.impl.data

import java.util.TimeZone
import javax.inject.Inject

/** The device time zone id, read anew on every call (the system updates the default zone on a change). */
fun interface DeviceZone {
    fun id(): String
}

class SystemDeviceZone @Inject constructor() : DeviceZone {
    override fun id(): String = TimeZone.getDefault().id
}
