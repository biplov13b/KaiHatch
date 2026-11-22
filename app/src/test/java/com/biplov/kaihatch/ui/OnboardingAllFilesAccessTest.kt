package com.biplov.kaihatch.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.biplov.kaihatch.R
import com.biplov.kaihatch.data.StorageHelper
import com.biplov.kaihatch.ui.screens.OnboardingScreen
import com.biplov.kaihatch.ui.theme.KaiHatchTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingAllFilesAccessTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        StorageHelper.allFilesAccessOverrideForTest = false
        app.getSharedPreferences("kaihatch_prefs", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        StorageHelper.allFilesAccessOverrideForTest = null
        app.getSharedPreferences("kaihatch_prefs", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `setup page hides status pill when not granted and swaps button for Granted pill on return`() {
        val viewModel = HatchViewModel(app)
        assertFalse(viewModel.allFilesAccessEnabled.value)

        composeRule.setContent {
            KaiHatchTheme {
                OnboardingScreen(viewModel = viewModel)
            }
        }

        // Advance from Step 0 (Welcome) -> Step 1 (What's KaiHatch) -> Step 2 (Permissions & Destination)
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()

        // Verify All Files Access card exists alongside Notifications and Destination cards
        composeRule.onNodeWithTag("onboarding_notifications_card").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_all_files_access_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_destination_card").performScrollTo().assertIsDisplayed()

        // When not granted: show label, description, and Allow button, without a redundant status pill
        composeRule.onNodeWithTag("onboarding_all_files_access_card").performScrollTo()
        composeRule.onNodeWithText(app.getString(R.string.settings_all_files_access_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.onboarding_all_files_access_subtitle)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_all_files_access_status").assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.onboarding_all_files_status_not_granted)).assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding_request_all_files_access_button").assertIsDisplayed()

        // Simulate user granting All Files Access in Android Settings and returning to setup
        StorageHelper.allFilesAccessOverrideForTest = true
        composeRule.runOnUiThread {
            viewModel.refreshPermissionsState()
        }
        composeRule.waitForIdle()

        assertTrue(viewModel.allFilesAccessEnabled.value)
        composeRule.onNodeWithTag("onboarding_all_files_access_status").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.onboarding_all_files_status_granted)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_request_all_files_access_button").assertDoesNotExist()
    }

    @Test
    fun `setup can advance and finish without granting All Files Access and uses standard Settings intent`() {
        val intent = StorageHelper.createManageAllFilesAccessIntent(app)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.action)
        assertEquals("package:${app.packageName}", intent.data?.toString())

        val viewModel = HatchViewModel(app)
        assertFalse(viewModel.allFilesAccessEnabled.value)
        assertFalse(viewModel.hasCompletedOnboarding.value)

        composeRule.setContent {
            KaiHatchTheme {
                OnboardingScreen(viewModel = viewModel)
            }
        }

        // Advance through all steps without granting All Files Access
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.onNodeWithTag("onboarding_all_files_access_card").performScrollTo().assertIsDisplayed()

        // Next is never blocked by optional All Files Access
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.onNodeWithTag("onboarding_get_started_button").performClick()
        composeRule.waitForIdle()

        assertTrue("Onboarding should complete without All Files Access", viewModel.hasCompletedOnboarding.value)
        assertFalse("All Files Access remains optional/not granted", viewModel.allFilesAccessEnabled.value)
    }
}
