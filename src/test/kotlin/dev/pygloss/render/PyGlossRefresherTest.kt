package dev.pygloss.render

import junit.framework.TestCase

/** Pure refresher seam tests for cached editor surfaces. */
class PyGlossRefresherTest : TestCase() {

    fun testRefreshCoversAllCachedEditorSurfaces() {
        assertEquals(
            setOf(
                RefreshSurface.DAEMON,
                RefreshSurface.FOLD_REGIONS
            ),
            PyGlossRefresher.surfacesForRefresh()
        )
    }
}
