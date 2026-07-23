package com.walterdeane.lore.document;

public class Tester {

    public boolean isPalindrome(String testString) {
        for (int x = 0; x < testString.length(); x++) {
            int y = testString.length() - 1 - x;
            if (x >= y) {
                return true;
            }
            if (testString.charAt(x) != testString.charAt(y)) {
                return false;
            }
        }
        return false;
    }
}
