package dev.mascwa.sky

import android.app.Application

/**
 * The process.
 *
 * ⚠️ **Named in the manifest so that the container is built once**, which is the reason the
 * nutrition application gives for having one: two containers in a process means two of every reader,
 * and [SkyContainer] documents what that costs here. The activity reads this one.
 *
 * ⚠️ **Deliberately does no work in `onCreate`.** The nutrition application installs a crash handler
 * and sends the previous launch's report from here, and both are right THERE — it has a network
 * permission and a token to send with. This one has neither, so a reporter would record faults it
 * could never deliver, and the honest version of that arrives with the updater rather than being
 * hinted at now. Every member of the container is lazy, so constructing it opens nothing.
 */
class SkyApplication : Application() {

    val container: SkyContainer by lazy { SkyContainer(this) }
}
