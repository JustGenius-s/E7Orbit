package com.e7orbit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E7RtaParserTest {
    @Test
    fun `season response maps and sorts newest first`() {
        val seasons = parseRtaSeasons(
            """
            {
              "code": 0,
              "message": "SUCCESS",
              "value": {
                "result_body": [
                  {
                    "season_code": "pvp_rta_ss19",
                    "name": "Spring 2026",
                    "startDate": "2025-12-13 00:00:00.0",
                    "endDate": "2026-03-07 00:00:00.0",
                    "is_now_season": 0
                  },
                  {
                    "season_code": "pvp_rta_ss20",
                    "name": "Summer 2026",
                    "startDate": "2026-04-04 00:00:00.0",
                    "endDate": "2026-07-11 00:00:00.0",
                    "is_now_season": 0
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("pvp_rta_ss20", "pvp_rta_ss19"), seasons.map { it.code })
        assertEquals("Summer 2026", seasons.first().name)
        assertFalse(seasons.first().isCurrent)
    }

    @Test
    fun `hero analysis keeps position distributions separate`() {
        val analysis = parseHeroRtaAnalysis(
            """
            {
              "code": 0,
              "message": "SUCCESS",
              "value": {
                "result_body": {
                  "heroCode": "c5154",
                  "seasonCode": "pvp_rta_ss20",
                  "seasonTierCode": "master",
                  "current_seasontier_tot": 383,
                  "equip": [
                    {
                      "rank": 1,
                      "equip_list": ["set_immune", "set_speed"],
                      "rate": 35.0,
                      "win_rate": 65.09
                    }
                  ],
                  "pick": [{"num": 1, "rate": 77.39}],
                  "ban": [{"num": 1, "rate": 80.88}],
                  "win_rate": [
                    {
                      "season_code": "pvp_rta_ss20",
                      "win_rate": 62.63,
                      "rank": 5
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("c5154", analysis.heroCode)
        assertEquals(383, analysis.sampleSize)
        assertEquals(62.63, analysis.winRate!!, 0.001)
        assertEquals(5, analysis.winRateRank)
        assertEquals(listOf("set_immune", "set_speed"), analysis.equipmentSets.single().setCodes)
        assertEquals(77.39, analysis.pickPositions.single().rate, 0.001)
        assertEquals(80.88, analysis.banPositions.single().rate, 0.001)
    }

    @Test
    fun `zero win rank is treated as unavailable`() {
        val analysis = parseHeroRtaAnalysis(
            """
            {
              "code": 0,
              "message": "SUCCESS",
              "value": {
                "result_body": {
                  "heroCode": "c0001",
                  "seasonCode": "pvp_rta_ss20",
                  "seasonTierCode": "master",
                  "win_rate": [{"season_code": "pvp_rta_ss20", "win_rate": 0.0, "rank": 0}]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(0.0, analysis.winRate!!, 0.001)
        assertNull(analysis.winRateRank)
        assertTrue(analysis.equipmentSets.isEmpty())
    }
}
