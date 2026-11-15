package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.engine.SafFileHelper
import com.example.engine.ScannedDocument
import com.example.model.SplitNamingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun appNameMatchesSplit666() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Split666", appName)
  }

  @Test
  fun navigationTabsSwitchBetweenSplitMergeAndSpecs() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pick_files_button").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_tab_join").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Merge .666xx & .001 Parts").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_tab_specs").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("FAT32 Chunk Calculator").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_tab_split").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("scan_folder_button").assertIsDisplayed()
  }

  @Test
  fun joinGroupingDetectsContiguousAndMissingParts() {
    val docs =
      listOf(
        ScannedDocument(Uri.parse("content://test/1"), "game.iso.66600", "PS3/game.iso.66600", 1000L),
        ScannedDocument(Uri.parse("content://test/2"), "game.iso.66601", "PS3/game.iso.66601", 1000L),
        ScannedDocument(Uri.parse("content://test/3"), "game.iso.66602", "PS3/game.iso.66602", 420L),
        ScannedDocument(Uri.parse("content://test/4"), "movie.mkv.001", "movie.mkv.001", 500L),
        ScannedDocument(Uri.parse("content://test/5"), "movie.mkv.003", "movie.mkv.003", 500L),
      )

    val groups = SafFileHelper.buildJoinGroups(docs)
    assertEquals(2, groups.size)

    val ps3Group = groups.first { it.baseFileName == "game.iso" }
    assertEquals(SplitNamingFormat.PS3_666, ps3Group.format)
    assertTrue(ps3Group.isContiguous)
    assertEquals(2420L, ps3Group.totalBytes)

    val movieGroup = groups.first { it.baseFileName == "movie.mkv" }
    assertEquals(SplitNamingFormat.SEQUENTIAL_001, movieGroup.format)
    assertFalse(movieGroup.isContiguous)
    assertEquals(listOf(2), movieGroup.missingIndices)
  }
}
