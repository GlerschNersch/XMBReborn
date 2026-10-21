package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ps3LibraryTest {
    @Test fun readsBothFormsArmsx3Writes() {
        assertEquals(Ps3Library.Game("Killzone 3", "BCUS98234"), Ps3Library.parse("Killzone 3.ps3", "BCUS98234"))
        assertEquals(Ps3Library.Game("Killzone 3", "BCUS98234"), Ps3Library.parse("Killzone 3.ps3", "[title_id] BCUS98234\n"))
    }

    @Test fun toleratesCaseDashesAndWhitespace() {
        assertEquals("BLUS12345", Ps3Library.titleIdIn("  blus-12345  "))
        assertEquals("BLUS12345", Ps3Library.titleIdIn("\n\n[TITLE_ID]   BLUS12345\nsomething else"))
    }

    @Test fun stripsTheSuffixesAddedOnNameClashes() {
        assertEquals("Uncharted 2 - Among Thieves", Ps3Library.parse("Uncharted 2 - Among Thieves [BCES00757].ps3", "BCES00757")!!.title)
        assertEquals("Uncharted 2", Ps3Library.parse("Uncharted 2 (1).ps3", "BCES00757")!!.title)
        assertEquals("Uncharted 2", Ps3Library.parse("Uncharted 2 [BCES00757] (2).PS3", "BCES00757")!!.title)
    }

    @Test fun rejectsWhatIsntAShortcut() {
        assertNull(Ps3Library.parse("notes.txt", "BLUS12345"))
        assertNull(Ps3Library.parse("Game.ps3", "not an id"))
        assertNull(Ps3Library.parse("Game.ps3", ""))
        assertNull(Ps3Library.titleIdIn("BLUS1234"))
    }

    @Test fun anEmptyNameFallsBackToTheId() {
        assertEquals("NPUB30001", Ps3Library.parse(".ps3", "NPUB30001")!!.title)
    }
}
