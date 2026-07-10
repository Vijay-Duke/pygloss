package dev.pygloss.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for deterministic line-level English used by editor folding. */
class EnglishViewProjectorTest {

    @Test
    fun `generic calls use the shared call phrase`() {
        assertEquals(
            "call logger's info with message — options: flush True",
            EnglishViewProjector.translateLine("logger.info(message, flush=True)"),
        )
    }

    @Test
    fun `translates billing-style imperative Python lines`() {
        assertEquals(
            "define process_invoices taking invoices, threshold:",
            EnglishViewProjector.translateLine("def process_invoices(invoices, threshold):"),
        )
        assertEquals(
            "    start an empty list approved",
            EnglishViewProjector.translateLine("    approved = []"),
        )
        assertEquals(
            "    for each inv in invoices:",
            EnglishViewProjector.translateLine("    for inv in invoices:"),
        )
        assertEquals(
            "        if inv's total is over threshold and inv is not flagged:",
            EnglishViewProjector.translateLine("        if inv.total > threshold and not inv.flagged:"),
        )
        assertEquals(
            "            set inv's status to \"approved\"",
            EnglishViewProjector.translateLine("            inv.status = \"approved\""),
        )
        assertEquals(
            "            add inv to approved",
            EnglishViewProjector.translateLine("            approved.append(inv)"),
        )
        assertEquals(
            "    give back approved",
            EnglishViewProjector.translateLine("    return approved"),
        )
    }

    @Test
    fun `translates simple list comprehension assignment`() {
        assertEquals(
            "set totals to the list of x's amount × 1.1, for every x in rows where x's region is \"AU\"",
            EnglishViewProjector.translateLine("""totals = [x.amount * 1.1 for x in rows if x.region == "AU"]"""),
        )
    }

    @Test
    fun `does not rewrite dotted names inside quoted strings`() {
        assertEquals(
            """set SAMPLE_FILE to Path(__file__).resolve().parents[3] / "sample_files" / "sample_d0010.ded"""",
            EnglishViewProjector.translateLine(
                """SAMPLE_FILE = Path(__file__).resolve().parents[3] / "sample_files" / "sample_d0010.ded""""
            ),
        )
    }

    @Test
    fun `translates simple set comprehension assignment`() {
        assertEquals(
            "set mpans to the set of r's mpan, for every r in result",
            EnglishViewProjector.translateLine("mpans = {r.mpan for r in result}"),
        )
    }

    @Test
    fun `translates augmented assignment without corrupting target`() {
        assertEquals("increase x by 1", EnglishViewProjector.translateLine("x += 1"))
        assertEquals("decrease x by 1", EnglishViewProjector.translateLine("x -= 1"))
        assertEquals("set x to x × 2", EnglishViewProjector.translateLine("x *= 2"))
        assertEquals("set x to x // 2", EnglishViewProjector.translateLine("x //= 2"))
        assertEquals("set x to x ** 2", EnglishViewProjector.translateLine("x **= 2"))
        assertEquals("set x to x | 1", EnglishViewProjector.translateLine("x |= 1"))
        assertEquals("set x to x << 2", EnglishViewProjector.translateLine("x <<= 2"))
    }

    @Test
    fun `does not treat keyword arguments as assignments`() {
        assertEquals(
            "call ax's text with 0.5 — options: fontsize 28",
            EnglishViewProjector.translateLine("ax.text(0.5, fontsize=28)"),
        )
    }

    @Test
    fun `translates genuine assignment whose value has keyword arguments`() {
        assertEquals(
            "set x to f(a=1)",
            EnglishViewProjector.translateLine("x = f(a=1)"),
        )
    }

    @Test
    fun `translates common Python control flow lines`() {
        assertEquals("otherwise:", EnglishViewProjector.translateLine("else:"))
        assertEquals("while inv's total is over threshold:", EnglishViewProjector.translateLine("while inv.total > threshold:"))
        assertEquals("try the following:", EnglishViewProjector.translateLine("try:"))
        assertEquals("if Foo happens (as e):", EnglishViewProjector.translateLine("except Foo as e:"))
        assertEquals("finally, always:", EnglishViewProjector.translateLine("finally:"))
        assertEquals("""using open(path, "w") as f:""", EnglishViewProjector.translateLine("""with open(path, "w") as f:"""))
    }

    @Test
    fun `translates common Python statements`() {
        assertEquals("signal ValueError(\"bad\")", EnglishViewProjector.translateLine("""raise ValueError("bad")"""))
        assertEquals("do nothing", EnglishViewProjector.translateLine("pass"))
        assertEquals("stop the loop", EnglishViewProjector.translateLine("break"))
        assertEquals("skip to the next iteration", EnglishViewProjector.translateLine("continue"))
        assertEquals("use module pathlib", EnglishViewProjector.translateLine("import pathlib"))
        assertEquals("from module pathlib use Path", EnglishViewProjector.translateLine("from pathlib import Path"))
    }

    @Test
    fun `translates first line of multiline def headers and preserves parameter detail`() {
        assertEquals(
            "define build taking path (a str), threshold (a float, defaults to 0.5), retries (defaults to 3):",
            EnglishViewProjector.translateLine("def build(self, path: str, threshold: float = 0.5, retries=3):"),
        )
        assertEquals(
            "define build taking path (a str):",
            EnglishViewProjector.translateLine("def build(path: str,"),
        )
        assertEquals(
            "define build taking rows (a dict[str, list[int]]), options (a tuple[str, int], defaults to (\"x\", 1)):",
            EnglishViewProjector.translateLine(
                """def build(rows: dict[str, list[int]], options: tuple[str, int] = ("x", 1)):"""
            ),
        )
    }

    @Test
    fun `public expression helpers match line translation phrasing`() {
        assertEquals(
            "inv's total is over threshold and inv is not flagged",
            EnglishViewProjector.translateCondition("inv.total > threshold and not inv.flagged"),
        )
        assertEquals(
            "the list of x's amount × 1.1, for every x in rows where x's region is \"AU\"",
            EnglishViewProjector.translateExpression("""[x.amount * 1.1 for x in rows if x.region == "AU"]"""),
        )
    }

    @Test
    fun `translates dict comprehensions only through explicit comprehension helper`() {
        assertEquals(
            "the mapping of k to v, for every k, v in key-value pairs from items",
            EnglishViewProjector.translateComprehension("{k: v for k, v in items.items()}"),
        )
        assertEquals(null, EnglishViewProjector.translateComprehension("(x * y for x, y in pairs)"))
    }
}
