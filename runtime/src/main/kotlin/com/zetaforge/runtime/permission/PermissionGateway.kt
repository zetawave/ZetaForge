package com.zetaforge.runtime.permission

/**
 * Asks the user for whatever a [PermissionPlan] found missing.
 *
 * The runtime cannot do this on its own: the system dialog needs a foreground
 * Activity, and the special-access screens need to be started and waited on.
 * The Host installs an implementation (see `ActivityPermissionGateway`); when
 * none is installed, [DenyingPermissionGateway] keeps the runtime usable and
 * fully testable headlessly.
 */
interface PermissionGateway {

    /**
     * Requests everything in [plan] and returns what the user decided.
     *
     * Implementations must return promptly when nothing can be asked (for
     * instance because the app is in the background) rather than hanging.
     */
    suspend fun request(plan: PermissionPlan): PermissionOutcome
}

/**
 * Default gateway: asks nothing and grants nothing.
 *
 * With this in place a plugin missing a permission fails with a clear result
 * instead of silently misbehaving - which is exactly what instrumented tests and
 * headless execution need.
 */
object DenyingPermissionGateway : PermissionGateway {
    override suspend fun request(plan: PermissionPlan): PermissionOutcome = PermissionOutcome.NOTHING_ASKED
}
