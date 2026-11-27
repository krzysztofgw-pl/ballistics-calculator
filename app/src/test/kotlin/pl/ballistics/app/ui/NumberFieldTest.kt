package pl.ballistics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NumberFieldTest {
    @get:Rule val rule = createComposeRule()

    private var stored = "8"

    private fun twoFields() = rule.setContent {
        var v by remember { mutableStateOf(stored) }
        var other by remember { mutableStateOf("1") }
        Column {
            NumberField("Wind", v, "m/s") { v = it; stored = it }
            NumberField("Other", other, "m") { other = it }
        }
    }

    private fun editable(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true)

    @Test
    fun focusEmptiesTheFieldAndKeepsTheOldValueAsHint() {
        twoFields()
        rule.onNodeWithText("Wind").performClick()
        rule.waitForIdle()
        // The text is gone (the field reads "" and placeholder "8"), yet "8" is still on screen.
        rule.onNodeWithText("Wind").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        editable("8").assertCountEquals(1)
        assertEquals("8", stored)
    }

    @Test
    fun typingReplacesTheValue() {
        twoFields()
        rule.onNodeWithText("Wind").performClick()
        rule.onNodeWithText("Wind").performTextInput("5")
        rule.waitForIdle()
        assertEquals("5", stored)
    }

    @Test
    fun leavingWithoutTypingRestoresTheValue() {
        twoFields()
        rule.onNodeWithText("Wind").performClick()
        rule.onNodeWithText("Other").performClick()
        rule.waitForIdle()
        assertEquals("8", stored)
        rule.onNodeWithText("Wind").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("8")))
    }
}
