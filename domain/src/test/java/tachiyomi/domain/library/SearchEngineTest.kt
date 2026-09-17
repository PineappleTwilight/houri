// KMK --> Library AST search contract: uppercase-only AND/NOT/OR, colon field vs title
package tachiyomi.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.library.model.LibrarySearchParser
import tachiyomi.domain.library.model.LibrarySearchToken
import tachiyomi.domain.library.model.positiveText

@Execution(ExecutionMode.CONCURRENT)
class SearchEngineTest {

    @Test
    fun `uppercase AND is an operator, not a search term`() {
        val tokens = LibrarySearchParser.parse("Naruto AND Hinata")

        tokens shouldBe listOf(
            LibrarySearchToken.Term("Naruto"),
            LibrarySearchToken.Term("Hinata"),
        )
    }

    @Test
    fun `lowercase and stays literal text`() {
        val tokens = LibrarySearchParser.parse("Naruto and Hinata")

        tokens shouldBe listOf(
            LibrarySearchToken.Term("Naruto"),
            LibrarySearchToken.Term("and"),
            LibrarySearchToken.Term("Hinata"),
        )
    }

    @Test
    fun `uppercase OR is an operator, lowercase or stays literal`() {
        LibrarySearchParser.parse("Naruto OR Boruto") shouldBe listOf(
            LibrarySearchToken.Term("Naruto"),
            LibrarySearchToken.Term("Boruto"),
        )
        LibrarySearchParser.parse("Naruto or Boruto") shouldBe listOf(
            LibrarySearchToken.Term("Naruto"),
            LibrarySearchToken.Term("or"),
            LibrarySearchToken.Term("Boruto"),
        )
    }

    @Test
    fun `uppercase NOT excludes the next term, lowercase not stays literal`() {
        LibrarySearchParser.parse("Naruto NOT Boruto") shouldBe listOf(
            LibrarySearchToken.Term("Naruto"),
            LibrarySearchToken.Term("Boruto", excluded = true),
        )
        LibrarySearchParser.parse("Naruto not Boruto") shouldBe listOf(
            LibrarySearchToken.Term("Naruto"),
            LibrarySearchToken.Term("not"),
            LibrarySearchToken.Term("Boruto"),
        )
    }

    @Test
    fun `colon title stays title search`() {
        val tokens = LibrarySearchParser.parse("One: Piece")

        (tokens.any { it is LibrarySearchToken.Field }) shouldBe false
        tokens.mapNotNull { it.positiveText() } shouldBe listOf("One:", "Piece")
    }

    @Test
    fun `genre and artist fields route to namespace`() {
        LibrarySearchParser.parse("genre:action") shouldBe listOf(
            LibrarySearchToken.Field("genre", "action"),
        )
        LibrarySearchParser.parse("artist:foo") shouldBe listOf(
            LibrarySearchToken.Field("artist", "foo"),
        )
    }

    @Test
    fun `single letter aliases route to mapped namespace`() {
        LibrarySearchParser.parse("a:foo") shouldBe listOf(
            LibrarySearchToken.Field("artist", "foo"),
        )
        LibrarySearchParser.parse("c:foo") shouldBe listOf(
            LibrarySearchToken.Field("character", "foo"),
        )
    }

    @Test
    fun `category and subcategory fields route to namespace`() {
        LibrarySearchParser.parse("category:Hentai") shouldBe listOf(
            LibrarySearchToken.Field("category", "Hentai"),
        )
        LibrarySearchParser.parse("cat:Hentai") shouldBe listOf(
            LibrarySearchToken.Field("category", "Hentai"),
        )
        LibrarySearchParser.parse("subcategory:Kawakami") shouldBe listOf(
            LibrarySearchToken.Field("subcategory", "Kawakami"),
        )
        LibrarySearchParser.parse("sub:Kawakami") shouldBe listOf(
            LibrarySearchToken.Field("subcategory", "Kawakami"),
        )
    }

    @Test
    fun `field names are case-insensitive, values keep their case`() {
        LibrarySearchParser.parse("Genre:Action") shouldBe listOf(
            LibrarySearchToken.Field("genre", "Action"),
        )
    }

    @Test
    fun `quoted operators stay literal`() {
        LibrarySearchParser.parse("\"AND\"") shouldBe listOf(
            LibrarySearchToken.Term("AND"),
        )
    }

    @Test
    fun `ast disabled keeps everything literal`() {
        LibrarySearchParser.parse("a AND b", enableAst = false) shouldBe listOf(
            LibrarySearchToken.Term("a"),
            LibrarySearchToken.Term("AND"),
            LibrarySearchToken.Term("b"),
        )
    }
}
// KMK <--
