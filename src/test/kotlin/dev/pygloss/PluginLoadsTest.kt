package dev.pygloss

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginLoadsTest : BasePlatformTestCase() {

    fun testPluginDescriptorIsValid() {
        val pluginId = PluginId.getId("dev.pygloss")
        val pluginDescriptor: IdeaPluginDescriptor? = PluginManagerCore.getPlugin(pluginId)
        assertNotNull("Plugin should be loaded", pluginDescriptor)
        assertEquals("dev.pygloss", pluginDescriptor?.pluginId?.idString)
        assertEquals("PyGloss", pluginDescriptor?.name)
    }

    fun testPluginDependsOnPythonModule() {
        val pluginId = PluginId.getId("dev.pygloss")
        val pluginDescriptor: IdeaPluginDescriptor? = PluginManagerCore.getPlugin(pluginId)
        assertNotNull("Plugin should be loaded", pluginDescriptor)
        val dependencyIds: List<String> = pluginDescriptor?.dependencies
            ?.map { it.pluginId.idString }
            ?: emptyList()
        assertTrue(
            "Should depend on com.intellij.modules.python, but had: $dependencyIds",
            dependencyIds.contains("com.intellij.modules.python")
        )
    }
}
