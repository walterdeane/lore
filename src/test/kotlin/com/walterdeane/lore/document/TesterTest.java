package com.walterdeane.lore.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TesterTest {

    @Test
    void isPalindrome() {

        assertTrue(new Tester().isPalindrome("racecar"));
        assertFalse(new Tester().isPalindrome("hello"));
    }
}