package dev.pygloss.engine

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable

/** Run PSI-reading work under read access, preserving an existing read/write action when present. */
internal fun <T> withPsiReadAccess(action: () -> T): T {
    val application = ApplicationManager.getApplication()
    return if (application.isReadAccessAllowed) {
        action()
    } else {
        application.runReadAction(Computable { action() })
    }
}
