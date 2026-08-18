package com.zetaforge.runtime.permission

import com.zetaforge.runtime.log.ZetaLogger
import com.zetaforge.runtime.manifest.ZetaManifest

/** What the runtime decided about a plugin's permissions before running it. */
sealed class PermissionDecision {

    /** Everything mandatory is in place; [missingOptional] may still be empty. */
    data class Allowed(
        val plan: PermissionPlan,
        val missingOptional: List<PermissionRequirement>,
    ) : PermissionDecision()

    /** The plugin cannot run. [errorCode] is what the failure result carries. */
    data class Blocked(
        val plan: PermissionPlan,
        val errorCode: String,
        val message: String,
        val undeclaredByHost: List<PermissionRequirement> = emptyList(),
    ) : PermissionDecision()
}

/**
 * Runs the whole permission story for one execution:
 *
 * ```
 * inspect -> (ask the user through the gateway) -> inspect again -> allow | block
 * ```
 *
 * Re-inspecting after the request is not paranoia: a permission can be revoked
 * from Settings while the dialog is open, special access is granted on a
 * different screen entirely, and Android auto-revokes permissions of unused
 * apps. Nothing is ever cached between runs.
 */
class PermissionCoordinator(
    private val inspector: PermissionInspector,
    private val logger: ZetaLogger,
) {

    @Volatile
    var gateway: PermissionGateway = DenyingPermissionGateway

    suspend fun ensureGranted(manifest: ZetaManifest): PermissionDecision {
        val pluginId = manifest.pluginId
        var plan = inspector.inspect(manifest)

        if (manifest.permissions.isNotEmpty() || manifest.specialAccess.isNotEmpty()) {
            logger.info(SOURCE, pluginId, "Permissions: " + plan.summary())
        }

        // A permission missing from the Host manifest can never be granted at
        // run time: report it as a build-time problem, with the exact fix.
        val undeclared = plan.undeclared.filterNot { it.optional }
        if (undeclared.isNotEmpty()) {
            val names = undeclared.joinToString { it.name }
            logger.error(
                SOURCE, pluginId,
                "Cannot request " + names + ": the Host APK does not declare it. " +
                    "Add it to zetaforge.permissions and rebuild the Host.",
            )
            return PermissionDecision.Blocked(
                plan = plan,
                errorCode = ERROR_NOT_DECLARED,
                message = "The Host does not declare " + names +
                    ". Add it to zetaforge.permissions and reinstall ZetaForge.",
                undeclaredByHost = undeclared,
            )
        }

        if (plan.isSatisfied) {
            return PermissionDecision.Allowed(plan, plan.missingOptional)
        }

        if (plan.isActionable) {
            plan.requestable.forEach { requirement ->
                logger.info(
                    SOURCE, pluginId,
                    "Requesting " + requirement.shortName +
                        (if (requirement.reason.isNotBlank()) " - " + requirement.reason else ""),
                )
            }
            plan.missingSpecialAccess.forEach { requirement ->
                logger.info(SOURCE, pluginId, "Requires special access: " + requirement.access.label)
            }

            val outcome = gateway.request(plan)
            inspector.markAsked(plan.requestable.map { it.name })

            if (outcome.cancelled) {
                logger.warn(SOURCE, pluginId, "Permission request dismissed by the user")
            }
            outcome.granted.forEach { logger.info(SOURCE, pluginId, "Granted: " + it.substringAfterLast('.')) }
            outcome.denied.forEach { logger.warn(SOURCE, pluginId, "Denied: " + it.substringAfterLast('.')) }
            outcome.permanentlyDenied.forEach {
                logger.warn(SOURCE, pluginId, "Denied permanently: " + it.substringAfterLast('.'))
            }
            outcome.specialAccessGranted.forEach { logger.info(SOURCE, pluginId, "Special access granted: " + it.label) }
            outcome.specialAccessDenied.forEach { logger.warn(SOURCE, pluginId, "Special access denied: " + it.label) }

            plan = inspector.inspect(manifest)
        }

        if (plan.isSatisfied) {
            logger.info(SOURCE, pluginId, "Permissions satisfied: " + plan.summary())
            return PermissionDecision.Allowed(plan, plan.missingOptional)
        }

        val blockers = buildList {
            addAll(plan.requestable.filterNot { it.optional }.map { it.shortName })
            addAll(plan.permanentlyDenied.filterNot { it.optional }.map { it.shortName + " (denied permanently)" })
            addAll(plan.missingSpecialAccess.filterNot { it.optional }.map { it.access.label })
        }
        val permanent = plan.permanentlyDenied.any { !it.optional }
        val code = when {
            plan.missingSpecialAccess.any { !it.optional } -> ERROR_SPECIAL_ACCESS
            permanent -> ERROR_PERMANENTLY_DENIED
            else -> ERROR_DENIED
        }
        val message = "Missing permission: " + blockers.joinToString() +
            if (permanent) ". Grant it from Android settings." else ""
        logger.warn(SOURCE, pluginId, message)

        return PermissionDecision.Blocked(plan, code, message)
    }

    companion object {
        const val SOURCE = "Permissions"

        const val ERROR_DENIED = "PERMISSION_DENIED"
        const val ERROR_PERMANENTLY_DENIED = "PERMISSION_PERMANENTLY_DENIED"
        const val ERROR_NOT_DECLARED = "PERMISSION_NOT_DECLARED_BY_HOST"
        const val ERROR_SPECIAL_ACCESS = "SPECIAL_ACCESS_REQUIRED"
    }
}
