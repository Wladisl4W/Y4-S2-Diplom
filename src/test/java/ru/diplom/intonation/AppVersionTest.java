package ru.diplom.intonation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppVersionTest {
    @Test void embeddedVersionIsShownInName() {
        assertEquals(System.getProperty("expectedAppVersion"), AppVersion.value());
        assertTrue(AppVersion.value().matches("\\d+\\.\\d+\\.\\d+"));
        assertEquals("Тренировка интонации · v" + AppVersion.value(), AppVersion.displayName());
    }
}
