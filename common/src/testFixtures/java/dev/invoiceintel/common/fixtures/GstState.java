package dev.invoiceintel.common.fixtures;

/** States used by the generator, with their GST/TIN state code and a sample city. */
public enum GstState {
  DELHI("07", "Delhi", "New Delhi", '1'),
  WEST_BENGAL("19", "West Bengal", "Kolkata", '7'),
  GUJARAT("24", "Gujarat", "Ahmedabad", '3'),
  MAHARASHTRA("27", "Maharashtra", "Mumbai", '4'),
  KARNATAKA("29", "Karnataka", "Bengaluru", '5'),
  TAMIL_NADU("33", "Tamil Nadu", "Chennai", '6'),
  TELANGANA("36", "Telangana", "Hyderabad", '5');

  private final String code;
  private final String displayName;
  private final String city;
  private final char pinPrefix;

  GstState(String code, String displayName, String city, char pinPrefix) {
    this.code = code;
    this.displayName = displayName;
    this.city = city;
    this.pinPrefix = pinPrefix;
  }

  /** Two-digit state code that starts every GSTIN and pre-GST TIN. */
  public String code() {
    return code;
  }

  public String displayName() {
    return displayName;
  }

  public String city() {
    return city;
  }

  /** First digit of PIN codes in this state's postal zone. */
  public char pinPrefix() {
    return pinPrefix;
  }
}
