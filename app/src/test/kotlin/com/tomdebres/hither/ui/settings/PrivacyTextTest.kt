package com.tomdebres.hither.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyTextTest {
    @Test
    fun `headings, paragraphs and bullets come out as blocks`() {
        val blocks = parsePrivacy(
            """
            # Privacy

            First line of a paragraph
            and its second line.

            ## What is kept

            - **The address**, as typed.
            - A bullet that runs
              onto a second line.
            After the list.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                PrivacyBlock.Title("Privacy"),
                PrivacyBlock.Paragraph(listOf(Run("First line of a paragraph and its second line."))),
                PrivacyBlock.Heading("What is kept"),
                PrivacyBlock.Bullet(listOf(Run("The address", bold = true), Run(", as typed."))),
                PrivacyBlock.Bullet(listOf(Run("A bullet that runs onto a second line. After the list."))),
            ),
            blocks,
        )
    }

    @Test
    fun `code and links read as plain text, and bold keeps no backticks`() {
        val blocks = parsePrivacy("The cookie (`connect.sid`), **`http` on a LAN**, see <https://example.invalid/terms>.")
        val runs = (blocks.single() as PrivacyBlock.Paragraph).runs
        assertEquals(
            listOf(
                Run("The cookie (connect.sid), "),
                Run("http on a LAN", bold = true),
                Run(", see https://example.invalid/terms."),
            ),
            runs,
        )
    }

    @Test
    fun `the app's privacy page is docs privacy md, every section of it`() {
        // The build copies this very file into the app's assets (app/build.gradle.kts).
        val file = File("../docs/privacy.md")
        val blocks = parsePrivacy(file.readText())
        assertEquals(PrivacyBlock.Title("Privacy"), blocks.first())
        val headings = blocks.filterIsInstance<PrivacyBlock.Heading>().map { it.text }
        assertEquals(
            listOf(
                "What the app stores, on the device only",
                "What the app sends, and to whom",
                "On the TV's home screen",
                "What the server does with it",
                "Changes",
            ),
            headings,
        )
        val text = blocks.joinToString("\n") {
            when (it) {
                is PrivacyBlock.Paragraph -> it.runs.plain()
                is PrivacyBlock.Bullet -> it.runs.plain()
                is PrivacyBlock.Heading -> it.text
                is PrivacyBlock.Title -> it.text
            }
        }
        // nothing of the Markdown is left to show on the screen
        assertTrue(text, listOf("**", "`", "## ", "<http").none { it in text })
        assertTrue(text.contains("There is no analytics, no crash reporting, no advertising"))
    }
}
