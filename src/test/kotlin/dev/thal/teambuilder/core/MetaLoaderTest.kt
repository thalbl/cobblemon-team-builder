package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaLoaderTest {

    private val sample = """
        {"month":"2026-10","cutoff":1500,
         "formatUsage":{"fmt":{"a":0.10,"b":0.05}},
         "species":{"a":{"name":"A","format":"fmt","tier":1,"usage":0.10,
           "moves":{"tackle":0.9},"items":{"leftovers":0.5},"abilities":{"overgrow":1.0},
           "spreads":[{"nature":"jolly","evs":[0,252,0,0,4,252],"freq":0.4}],
           "teammates":{"b":0.20,"c":0.10},"counters":{"b":0.7}}}}
    """.trimIndent()

    @Test
    fun parsesSpeciesAndComputesLift() {
        val meta = MetaLoader.parse(sample.reader())
        val a = assertNotNull(meta["a"])
        assertEquals("jolly", a.spreads.single().nature)
        assertEquals(0.9, a.moves["tackle"])
        // b aparece em 20% dos times de a, contra 5% no geral: lift 4x
        assertEquals(4.0, meta.teammateLift("a", "b")!!, 1e-9)
        // c nao tem uso de referencia no formato
        assertNull(meta.teammateLift("a", "c"))
    }

    @Test
    fun bundledSnapshotHasData() {
        val meta = MetaLoader.loadBundled()
        assertTrue(meta.size > 300, "snapshot embutido deveria ter centenas de Pokemon, tem ${meta.size}")
        assertNotNull(meta["rillaboom"])
    }
}
