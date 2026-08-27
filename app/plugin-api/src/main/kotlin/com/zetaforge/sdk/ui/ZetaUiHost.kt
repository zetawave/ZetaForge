package com.zetaforge.sdk.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope

/**
 * Everything a screen plugin needs from the Host, and nothing more.
 *
 * A plugin gets this instead of the raw `Activity` on purpose. The Activity is
 * the Host's: handing it over would let a plugin finish it, replace its content
 * view, or start a permission request the Host has no record of. What a screen
 * legitimately needs is small, and all of it is here.
 */
interface ZetaUiHost {

    /** The plugin this screen belongs to, as declared in its manifest. */
    val pluginId: String

    /** The name shown in the title bar above the screen. */
    val pluginName: String

    /**
     * A context safe to keep for the lifetime of the screen.
     *
     * This is the *Activity* context, so it can theme a dialog and measure the
     * real window. It must not outlive the screen; for anything that does, use
     * `context.applicationContext`.
     */
    val context: Context

    /**
     * The plugin's saved settings, as the Host has them right now.
     *
     * Same keys and types as the `input` bundle of `execute`, so a plugin that
     * is both a screen and a job reads its configuration in exactly one way.
     * It is a snapshot: it does not update while the screen is open.
     */
    val settings: Bundle

    /**
     * A scope tied to the screen: cancelled when it closes, and with a handler
     * that turns an uncaught failure into the Host's error screen rather than a
     * dead process.
     *
     * Every suspend call a screen makes belongs here.
     */
    val scope: CoroutineScope

    /** Shows a short message at the bottom of the screen. */
    fun message(text: String)

    /** Replaces the line under the plugin name in the title bar; `null` clears it. */
    fun setSubtitle(text: String?)

    /**
     * Asks for the permissions the plugin's manifest declares, showing the
     * reasons written there.
     *
     * Returns true when everything mandatory is granted. It is the same code
     * path a scheduled run goes through, so a screen and a job cannot end up
     * with two different ideas of what has been granted. Safe to call more than
     * once; already granted permissions cost nothing.
     */
    suspend fun ensurePermissions(): Boolean

    /**
     * Closes the screen.
     *
     * The Host also offers its own way out, so a plugin never has to provide
     * one — this exists for screens that finish a task and should get out of the
     * way.
     */
    fun close()

    /**
     * Starts this plugin's own `execute()` as a background run, in the Host's
     * foreground service, and returns whether the request was made.
     *
     * For the shape of plugin a screen alone cannot serve: work the user sets
     * up by looking at it and then expects to keep going with the screen
     * closed and the phone in a pocket. A coroutine started in [scope] dies
     * with the screen, and a process with no foreground component is frozen by
     * Android within seconds — so the work has to move to where every other run
     * already lives.
     *
     * The run goes through the ordinary path: the same permission check, the
     * same notification with its Stop button, the same result reporting. The
     * plugin is the same loaded instance the screen is talking to, so a
     * singleton is all it takes for the two halves to see each other.
     *
     * Returns false when a run of this plugin is already in flight.
     */
    fun runInBackground(): Boolean

    /**
     * Asks a background run started by [runInBackground] to stop, exactly as
     * the notification's Stop button does.
     *
     * The run is cancelled cooperatively, so a plugin that holds its state on
     * disk resumes rather than loses it.
     */
    fun requestStop()

    /**
     * Whether a run of this plugin is in flight right now.
     *
     * Read at composition time to decide what the screen offers — Start or
     * Stop — without the plugin having to track a run that may have been
     * stopped from the notification.
     */
    fun isRunning(): Boolean

    /**
     * Asks the user to pick a file, and returns what they chose.
     *
     * `startActivityForResult` needs an Activity, and a plugin is deliberately
     * never given one: the picker therefore belongs to the Host, which owns the
     * launcher and hands back the `Uri`. Read it through
     * `context.contentResolver` — the grant is read-only and does not survive
     * the process.
     *
     * Returns null when the user backs out.
     *
     * @param mimeTypes what to offer, as MIME types such as an image wildcard.
     *   Empty means everything.
     */
    suspend fun pickContent(vararg mimeTypes: String): Uri?
}
