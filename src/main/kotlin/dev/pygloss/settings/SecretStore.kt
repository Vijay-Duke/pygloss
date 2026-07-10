package dev.pygloss.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking

/**
 * Secure wrapper around IntelliJ PasswordSafe for the LLM API key.
 */
@Service(Service.Level.APP)
class SecretStore {

    private val credentialAttributes: CredentialAttributes = CredentialAttributes(
        generateServiceName("PyGloss", CREDENTIAL_KEY)
    )

    /**
     * Retrieve the stored API key with the platform async API.
     */
    suspend fun getApiKey(): String {
        return try {
            PasswordSafe.instance.getAsync(credentialAttributes).unwrap()?.getPasswordAsString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Retrieve the API key on a pooled thread for synchronous platform UI callers.
     */
    fun getApiKeyAsync(onResult: (String) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            onResult(runBlocking { getApiKey() })
        }
    }

    /**
     * Store the given API key on a pooled thread.
     */
    fun setApiKeyAsync(key: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            setApiKeyOffEdt(key)
        }
    }

    private fun setApiKeyOffEdt(key: String) {
        try {
            if (key.isEmpty()) {
                PasswordSafe.instance.set(credentialAttributes, null)
            } else {
                PasswordSafe.instance.set(credentialAttributes, Credentials(CREDENTIAL_USER, key))
            }
        } catch (_: Exception) {
            // Silently ignore — key storage failures should not crash the IDE.
        }
    }

    companion object {
        /** Empty API key value for fresh installs and local providers. */
        const val EMPTY_API_KEY: String = ""

        private const val CREDENTIAL_KEY = "dev.pygloss.apiKey"
        private const val CREDENTIAL_USER = "PyGloss"

        /** Convenience accessor. */
        @JvmStatic
        fun getInstance(): SecretStore =
            ApplicationManager.getApplication().getService(SecretStore::class.java)
    }
}
