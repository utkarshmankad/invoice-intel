package dev.invoiceintel.common.checksum;

/**
 * Verhoeff check digit, as used by the last digit of an Aadhaar number (PII-2(a)). It detects every
 * single-digit error and every transposition of adjacent digits.
 */
public final class Verhoeff {

  // Multiplication table of the dihedral group D5.
  private static final int[][] D = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
    {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
    {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
    {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
    {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
    {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
    {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
    {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
    {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
    {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
  };

  // Row k is the position permutation applied k times (row 1 composed with itself); the digit at
  // position i from the right goes through row i mod 8.
  private static final int[][] P = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
    {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
    {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
    {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
    {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
    {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
    {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
    {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
  };

  // Inverse of each element of D5.
  private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

  private Verhoeff() {}

  /**
   * Whether {@code digits}, check digit last, passes the Verhoeff check. Empty input or any
   * character other than an ASCII digit fails.
   */
  public static boolean isValid(CharSequence digits) {
    return !digits.isEmpty() && product(digits, 0) == 0;
  }

  /** The check digit to append to {@code digits}. */
  public static char checkDigit(CharSequence digits) {
    if (digits.isEmpty()) {
      throw new IllegalArgumentException("input must not be empty");
    }
    int c = product(digits, 1);
    if (c < 0) {
      throw new IllegalArgumentException("input must contain ASCII digits only");
    }
    return (char) ('0' + INV[c]);
  }

  /**
   * Verhoeff product of the digits, the rightmost at position {@code offset}; -1 if a character is
   * not an ASCII digit.
   */
  private static int product(CharSequence digits, int offset) {
    int c = 0;
    int n = digits.length();
    for (int i = 0; i < n; i++) {
      char ch = digits.charAt(n - 1 - i);
      if (ch < '0' || ch > '9') {
        return -1;
      }
      c = D[c][P[(i + offset) % 8][ch - '0']];
    }
    return c;
  }
}
