package dev.pytoenglish

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginLoadsTest : BasePlatformTestCase() {

    fun testPluginDescriptorIsValid() {
        val pluginId = PluginId.getId("dev.pytoenglish")
        val pluginDescriptor: IdeaPluginDescriptor? = PluginManagerCore.getPlugin(pluginId)
        assertNotNull("Plugin should be loaded", pluginDescriptor)
        assertEquals("dev.pytoenglish", pluginDescriptor?.pluginId?.idString)
    }

    fun testPluginDependsOnPythonModule() {
        val pluginId = PluginId.getId("dev.pytoenglish")
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
