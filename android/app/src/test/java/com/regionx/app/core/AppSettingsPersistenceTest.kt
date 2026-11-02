package com.regionx.app.core

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.regionx.app.AppContainer
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class AppSettingsPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun legacySettingsDefaultToFullScreenWithoutAChosenRuntimeModel() {
        val restored = json.decodeFromString<AppSettings>("""{"ActiveModelId":"saved-upstream","AnswerPrompt":"custom prompt"}""")
        val saved = json.parseToJsonElement(json.encodeToString(restored)).jsonObject
        assertEquals(JsonPrimitive("FullScreen"), saved["CaptureMode"])
        assertEquals(JsonNull, saved["ActiveUpstreamModelName"])
        assertEquals(JsonPrimitive("saved-upstream"), saved["ActiveModelId"])
        assertEquals(JsonPrimitive("custom prompt"), saved["AnswerPrompt"])
    }

    @Test fun chosenCaptureAndRuntimeModelSurviveSettingsRoundTrip() {
        val restored = json.decodeFromString<AppSettings>("""{"CaptureMode":"Region","ActiveUpstreamModelName":"vision-model","OverlayBounds":"10,20,300,400"}""")
        val saved = json.parseToJsonElement(json.encodeToString(restored.normalized())).jsonObject
        assertEquals(JsonPrimitive("Region"), saved["CaptureMode"])
        assertEquals(JsonPrimitive("vision-model"), saved["ActiveUpstreamModelName"])
        assertEquals(JsonPrimitive("10,20,300,400"), saved["OverlayBounds"])
    }

    @Test fun settingsScreenReflectsChangesMadeOutsideTheScreen() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val store = ViewModelStore()
        try {
            container.initialize()
            val viewModel = SettingsViewModel(container)
            store.put("settings", viewModel)
            viewModel.reload()
            container.updateSettings { it.copy(activeModelId = "selected-in-overlay", themeName = "test-theme") }
            withTimeout(1500) {
                while (viewModel.settings.value.themeName != "test-theme") delay(10)
            }
            assertEquals("selected-in-overlay", viewModel.activeModelId.value)
        } finally {
            store.clear()
            container.taskQueue.cancelAll()
            container.appScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun editingActiveDefaultModelClearsOnlyItsRuntimeChoice() = withSettings { container, viewModel ->
        val active = ModelConfig(id = "active", name = "active config", modelName = "default-model")
        container.modelRepository.insert(active)
        container.updateSettings { it.copy(activeModelId = active.id, activeUpstreamModelName = "temporary-choice") }
        viewModel.reload()
        viewModel.selectModel(active)
        viewModel.updateEditor { it.copy(modelName = "new-default") }
        viewModel.saveModel()
        await { viewModel.editor.value == null }
        assertEquals("new-default", container.modelRepository.get(active.id)?.modelName)
        assertEquals(null, container.settings.value.activeUpstreamModelName)
        assertEquals(active.id, container.settings.value.activeModelId)
    }

    @Test fun editingInactiveConfigPreservesTheCurrentRuntimeChoice() = withSettings { container, viewModel ->
        val active = ModelConfig(id = "active", name = "active config", modelName = "default-model")
        val inactive = ModelConfig(id = "inactive", name = "inactive config", modelName = "other-model")
        container.modelRepository.insert(active)
        container.modelRepository.insert(inactive)
        container.updateSettings { it.copy(activeModelId = active.id, activeUpstreamModelName = "temporary-choice") }
        viewModel.reload()
        viewModel.selectModel(inactive)
        viewModel.updateEditor { it.copy(modelName = "new-inactive-default") }
        viewModel.saveModel()
        await { viewModel.editor.value == null }
        assertEquals("temporary-choice", container.settings.value.activeUpstreamModelName)
        assertEquals("default-model", container.modelRepository.get(active.id)?.modelName)
        assertEquals("new-inactive-default", container.modelRepository.get(inactive.id)?.modelName)
    }

    @Test fun deletingTheActiveConfigSelectsRemainingConfigAndClearsRuntimeChoice() = withSettings { container, viewModel ->
        val active = ModelConfig(id = "active", name = "active config", modelName = "default-model")
        val remaining = ModelConfig(id = "remaining", name = "remaining config", modelName = "remaining-model")
        container.modelRepository.insert(active)
        container.modelRepository.insert(remaining)
        container.updateSettings { it.copy(activeModelId = active.id, activeUpstreamModelName = "temporary-choice") }
        viewModel.reload()
        viewModel.deleteModel(active)
        await { container.settings.value.activeModelId == remaining.id }
        assertEquals(null, container.settings.value.activeUpstreamModelName)
        assertEquals(listOf(remaining.id), container.modelRepository.all().map { it.id })
    }

    private fun withSettings(block: suspend (AppContainer, SettingsViewModel) -> Unit) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val store = ViewModelStore()
        try {
            container.initialize()
            val viewModel = SettingsViewModel(container)
            store.put("settings", viewModel)
            viewModel.reload()
            block(container, viewModel)
        } finally {
            store.clear()
            container.taskQueue.cancelAll()
            container.appScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private suspend fun await(predicate: suspend () -> Boolean) {
        withTimeout(3000) { while (!predicate()) delay(10) }
    }
}
