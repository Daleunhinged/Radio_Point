package com.radiopoint.app

import com.radiopoint.app.gis.FieldNavigation
import org.junit.Assert.*
import org.junit.Test

class FieldNavigationTest {
    @Test fun cardinalBearingsAndDistance() {
        val north=FieldNavigation.between(0.0,0.0,1.0,0.0)
        assertEquals(111195.08,north.meters,1.0)
        assertEquals(0.0,north.bearingTrue!!,0.001)
        assertEquals(90.0,FieldNavigation.between(0.0,0.0,0.0,1.0).bearingTrue!!,0.001)
        assertEquals(180.0,FieldNavigation.between(0.0,0.0,-1.0,0.0).bearingTrue!!,0.001)
        assertEquals(270.0,FieldNavigation.between(0.0,0.0,0.0,-1.0).bearingTrue!!,0.001)
    }
    @Test fun crossingDateLineTakesShortPath() {
        val g=FieldNavigation.between(0.0,179.999,0.0,-179.999)
        assertEquals(222.39,g.meters,0.1)
        assertEquals(90.0,g.bearingTrue!!,0.01)
    }
    @Test fun overlappingAccuracyDoesNotClaimDirectionOrArrival() {
        assertNull(FieldNavigation.between(53.0,-123.0,53.0,-123.0).bearingTrue)
        val g=FieldNavigation.between(53.0,-123.0,53.0001,-123.0,8.0,8.0)
        assertTrue(g.meters>0)
        assertNull(g.bearingTrue)
    }
    @Test fun ageIncludesFixAgeAndRejectsFutureReceipts() {
        assertEquals(40L,FieldNavigation.reportAgeSeconds(100000,70000,10))
        assertNull(FieldNavigation.reportAgeSeconds(100000,100001,0))
        assertNull(FieldNavigation.reportAgeSeconds(100000,0,0))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidCoordinatesRejected() {
        FieldNavigation.between(Double.NaN,0.0,0.0,0.0)
    }
}
