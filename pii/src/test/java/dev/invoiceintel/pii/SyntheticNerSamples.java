package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import dev.invoiceintel.text.TextSource;
import dev.invoiceintel.text.Word;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Synthetic invoice snippets for the NER benchmark (M2.8, risk R3): made-up Indian person names
 * (common given names and surnames combined at random, not real people) in invoice-like contexts,
 * and negatives without any person (products, cities, states, companies, amounts). Nothing here
 * comes from a real invoice.
 */
final class SyntheticNerSamples {

  static final List<String> GIVEN_NAMES =
      List.of(
          "Aarav",
          "Priya",
          "Rohan",
          "Ananya",
          "Vikram",
          "Sneha",
          "Arjun",
          "Kavya",
          "Rahul",
          "Lakshmi",
          "Suresh",
          "Meera",
          "Karthik",
          "Divya",
          "Aditya",
          "Pooja",
          "Siddharth",
          "Neha",
          "Rajesh",
          "Shreya",
          "Venkatesh",
          "Anjali",
          "Harish",
          "Deepa",
          "Manoj",
          "Swati",
          "Gaurav",
          "Nandini",
          "Abhishek",
          "Ritu",
          "Prakash",
          "Sunita",
          "Imran",
          "Farah",
          "Gurpreet",
          "Harpreet",
          "Joseph",
          "Ayesha",
          "Sanjay",
          "Bhavna",
          "Tanvi",
          "Yash",
          "Ishaan",
          "Pallavi",
          "Mohan",
          "Revathi",
          "Sudha",
          "Naveen",
          "Chitra",
          "Ramesh",
          "Zoya",
          "Faisal",
          "Hemant",
          "Jyoti",
          "Kiran",
          "Lalit",
          "Madhavi",
          "Nikhil",
          "Omkar",
          "Parvati");

  static final List<String> SURNAMES =
      List.of(
          "Sharma",
          "Patel",
          "Iyer",
          "Reddy",
          "Nair",
          "Menon",
          "Gupta",
          "Singh",
          "Kumar",
          "Rao",
          "Joshi",
          "Desai",
          "Mehta",
          "Chatterjee",
          "Banerjee",
          "Mukherjee",
          "Das",
          "Pillai",
          "Naidu",
          "Kulkarni",
          "Deshpande",
          "Bhat",
          "Shetty",
          "Agarwal",
          "Verma",
          "Mishra",
          "Pandey",
          "Yadav",
          "Choudhary",
          "Khan",
          "Sheikh",
          "Fernandes",
          "Gill",
          "Sandhu",
          "Bose",
          "Ghosh",
          "Hegde",
          "Krishnan",
          "Subramanian",
          "Venkataraman",
          "Chauhan",
          "Rathore",
          "Saxena",
          "Trivedi",
          "Bhattacharya",
          "Kapoor",
          "Malhotra",
          "Ansari",
          "Qureshi",
          "Thakur");

  /** Contexts that hold one name; {@code {}} is replaced by the name. */
  static final List<String> CONTEXTS =
      List.of(
          "Billing Address : {} Flat 402, Lotus Residency, MG Road",
          "Shipping Address : {} 17 Gandhi Nagar, Near City Mall",
          "Ship To: {}",
          "Bill To {} Plot 9, Sector 21",
          "Deliver To: {} House No 33, 2nd Cross",
          "{}",
          "Customer Name: {}",
          "Dear {}, thank you for shopping with us.",
          "Order placed by {} on 12.03.2025",
          "Sold to {} via online marketplace",
          "Name: {} Mobile: see account",
          "Consignee: {} C/o Sunrise Apartments",
          "{} 5th Main Road, Indiranagar",
          "Gift message: Happy birthday {}!",
          "Received by {} at the doorstep");

  /** Snippets without a person name: products, places, companies, amounts. */
  static final List<String> NEGATIVES =
      List.of(
          "Stainless Steel Pressure Cooker 5 Litre with Induction Base",
          "Wireless Bluetooth Earphones with Mic, Black",
          "Cotton Bedsheet Double Size with 2 Pillow Covers",
          "Basmati Rice Premium 5 kg Pack",
          "LED Bulb 9 Watt Cool Day Light Pack of 4",
          "Mixer Grinder 750 Watt with 3 Jars",
          "Place of Supply: Karnataka",
          "Place of Delivery: Maharashtra",
          "State/UT Code: 29",
          "Bengaluru, Karnataka",
          "Pune 411001 Maharashtra",
          "Chennai, Tamil Nadu",
          "Kolkata West Bengal",
          "Hyderabad Telangana",
          "Sold By : Sunrise Retail Private Limited",
          "Bluestone Traders LLP",
          "Shree Ganesh Enterprises",
          "Lakshmi Home Appliances Pvt Ltd",
          "Krishna Electronics and Mobiles",
          "Invoice Number : IN-4471 Invoice Date : 12.03.2025",
          "Total Amount: 1,299.00",
          "Amount in Words: One Thousand Two Hundred Ninety-nine only",
          "HSN: 85183000 Tax Rate 18% IGST",
          "Unit Price Qty Net Amount Tax Type",
          "Authorized Signatory",
          "Whether tax is payable under reverse charge - No",
          "Kashmir Willow Cricket Bat Full Size",
          "Darjeeling Tea Leaves 250 g",
          "Mysore Sandal Soap 150 g Pack of 3",
          "Nagpur Orange Marmalade 500 g");

  /** One sample: its text and the character ranges {@code [start, end)} of its person names. */
  record Sample(String text, List<int[]> names) {

    TextLayer layer() {
      var words = new ArrayList<Word>();
      double x = 10;
      for (String part : text.split(" ")) {
        if (!part.isEmpty()) {
          words.add(new Word(part, x, 10, part.length() * 5.0, 8, 1.0));
          x += part.length() * 5.0 + 3;
        }
      }
      return new TextLayer(List.of(new TextPage(1, 595, 842, TextSource.PDF_TEXT, words)));
    }
  }

  private SyntheticNerSamples() {}

  /** {@code count} name samples from a seeded generator; a quarter are upper case. */
  static List<Sample> positives(int count, long seed) {
    var random = new Random(seed);
    var samples = new ArrayList<Sample>();
    for (int i = 0; i < count; i++) {
      String name =
          GIVEN_NAMES.get(random.nextInt(GIVEN_NAMES.size()))
              + " "
              + SURNAMES.get(random.nextInt(SURNAMES.size()));
      if (i % 4 == 3) {
        name = name.toUpperCase(Locale.ROOT);
      }
      samples.add(withName(CONTEXTS.get(i % CONTEXTS.size()), name));
    }
    return samples;
  }

  static List<Sample> negatives() {
    return NEGATIVES.stream().map(text -> new Sample(text, List.of())).toList();
  }

  static Sample withName(String context, String name) {
    int at = context.indexOf("{}");
    String text = context.substring(0, at) + name + context.substring(at + 2);
    return new Sample(text, List.<int[]>of(new int[] {at, at + name.length()}));
  }
}
