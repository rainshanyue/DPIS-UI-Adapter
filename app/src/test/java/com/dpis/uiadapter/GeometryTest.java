package com.dpis.uiadapter;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class GeometryTest {

    @Test
    public void clampKeepsValuesInsideRange() {
        assertEquals(10, Geometry.clamp(5, 10, 20));
        assertEquals(20, Geometry.clamp(25, 10, 20));
        assertEquals(15, Geometry.clamp(15, 10, 20));
    }

    @Test
    public void portraitPositionClampsBelowStatusBarAndAboveNavBar() {
        int[] pos = Geometry.clampPosition(2000, 3000, 150, 150,
                1080, 2400, 100, 120);
        assertEquals(930, pos[0]);
        assertEquals(2130, pos[1]);
    }

    @Test
    public void rotationToLandscapeReclampsPreviousPortraitPosition() {
        int[] portrait = Geometry.defaultPosition(150, 150, 1080, 2400, 100, 120);
        // 98.2% of the horizontal travel range, centre at 43.3% of the drawing height
        assertEquals(913, portrait[0]);
        assertEquals(964, portrait[1]);
        int[] landscape = Geometry.clampPosition(portrait[0], portrait[1],
                150, 150, 2400, 1080, 80, 60);
        assertArrayEquals(new int[]{913, 870}, landscape);
    }
}
