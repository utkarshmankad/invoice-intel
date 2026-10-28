package dev.invoiceintel.pii;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import dev.invoiceintel.pii.internal.IndianStates;
import dev.invoiceintel.pii.internal.Labels;
import dev.invoiceintel.text.TextLayer;
import dev.invoiceintel.text.TextPage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * NER layer (b) of PII detection (PII-2(b), architecture.md §6.3, ADR-15): a small token
 * classification model run locally with ONNX Runtime through DJL. Person entities become {@link
 * PiiType#PERSON}, location entities {@link PiiType#ADDRESS}; organisations, dates and other
 * entities are not PII here (specs.md §6.1). Words wholly covered by an Indian state/UT name are
 * never reported, because the buyer state is kept.
 *
 * <p>Each page's {@link PageText} is tokenized once and classified in overlapping windows, so pages
 * longer than the model's sequence limit are covered; every token takes its prediction from the
 * window where it is farthest from an edge. A word is reported when any of its tokens has at least
 * {@code threshold} probability for the entity (recall bias, specs.md §6.3). Spans cover whole
 * words and carry no text (PII-8).
 *
 * <p>The model directory is a pinned Hugging Face snapshot ({@code config.json}, {@code
 * tokenizer.json}, {@code onnx/model_quantized.onnx}) downloaded by the build. DJL runs in offline
 * mode: its natives come from the jars and it may never download anything at run time.
 */
public final class NerDetector implements PiiDetector, AutoCloseable {

  /** The ONNX file inside the model directory. */
  public static final String MODEL_FILE = "onnx/model_quantized.onnx";

  /** Default probability above which a token counts as part of an entity. */
  public static final double DEFAULT_THRESHOLD = 0.5;

  static final int DEFAULT_WINDOW = 256;
  static final int DEFAULT_OVERLAP = 64;

  private static final Pattern STATE =
      Pattern.compile("(?i)\\b(?:" + IndianStates.alternation() + ")\\b");

  private final String detectorName;
  private final HuggingFaceTokenizer tokenizer;
  private final ZooModel<long[], float[][]> model;
  private final PiiType[] labelTypes;
  private final double threshold;
  private final int window;
  private final int step;
  private final long clsId;
  private final long sepId;

  private NerDetector(
      String detectorName,
      HuggingFaceTokenizer tokenizer,
      ZooModel<long[], float[][]> model,
      PiiType[] labelTypes,
      double threshold,
      int window,
      int overlap) {
    this.detectorName = detectorName;
    this.tokenizer = tokenizer;
    this.model = model;
    this.labelTypes = labelTypes;
    this.threshold = threshold;
    this.window = window;
    this.step = window - overlap;
    long[] specials = tokenizer.encode("", true, false).getIds();
    this.clsId = specials[0];
    this.sepId = specials[specials.length - 1];
  }

  /**
   * Loads the model in {@code modelDir} with the default threshold and window.
   *
   * @param shortName model name used in the detector name, e.g. {@code distilbert-multilingual}
   */
  public static NerDetector load(String shortName, Path modelDir) throws IOException {
    return load(shortName, modelDir, DEFAULT_THRESHOLD, DEFAULT_WINDOW, DEFAULT_OVERLAP);
  }

  /** Loads the model in {@code modelDir} with the given entity probability threshold. */
  public static NerDetector load(String shortName, Path modelDir, double threshold)
      throws IOException {
    return load(shortName, modelDir, threshold, DEFAULT_WINDOW, DEFAULT_OVERLAP);
  }

  static NerDetector load(
      String shortName, Path modelDir, double threshold, int window, int overlap)
      throws IOException {
    Objects.requireNonNull(shortName, "shortName");
    Objects.requireNonNull(modelDir, "modelDir");
    if (!(threshold > 0 && threshold <= 1)) {
      throw new IllegalArgumentException("threshold must be in (0, 1]");
    }
    if (overlap < 0 || window - overlap < 1 || window > 510) {
      throw new IllegalArgumentException("need 0 <= overlap < window <= 510");
    }
    // ADR-15: no run-time downloads (models, natives) and no DJL telemetry.
    System.setProperty("ai.djl.offline", "true");
    PiiType[] labelTypes = labelTypes(modelDir.resolve("config.json"));
    HuggingFaceTokenizer tokenizer =
        HuggingFaceTokenizer.newInstance(
            modelDir,
            // Whole pages are tokenized at once; windowing, not truncation, bounds the length.
            Map.of("addSpecialTokens", "false", "truncation", "false", "padding", "false"));
    try {
      ZooModel<long[], float[][]> model =
          Criteria.builder()
              .setTypes(long[].class, float[][].class)
              .optModelPath(modelDir.resolve(MODEL_FILE))
              .optEngine("OnnxRuntime")
              .optTranslator(new LogitsTranslator())
              .build()
              .loadModel();
      return new NerDetector(
          "ner:" + shortName, tokenizer, model, labelTypes, threshold, window, overlap);
    } catch (ModelNotFoundException | MalformedModelException | RuntimeException e) {
      tokenizer.close();
      throw new IOException("cannot load NER model " + shortName, e);
    }
  }

  /** The PII type of each label index in the model's {@code id2label}, or null for none. */
  static PiiType[] labelTypes(Path configJson) throws IOException {
    JsonNode id2label;
    try (InputStream in = Files.newInputStream(configJson)) {
      id2label = JsonMapper.shared().readTree(in).path("id2label");
    }
    if (!id2label.isObject() || id2label.isEmpty()) {
      throw new IOException("config.json has no id2label");
    }
    PiiType[] types = new PiiType[id2label.size()];
    for (var entry : id2label.properties()) {
      int index = Integer.parseInt(entry.getKey());
      if (index < 0 || index >= types.length) {
        throw new IOException("config.json id2label index out of range");
      }
      types[index] = typeOf(entry.getValue().asString());
    }
    return types;
  }

  /** {@code B-PER}/{@code I-PER} → PERSON, {@code B-LOC}/{@code I-LOC} → ADDRESS, else null. */
  static PiiType typeOf(String label) {
    String entity = label.length() > 2 && label.charAt(1) == '-' ? label.substring(2) : label;
    return switch (entity) {
      case "PER", "PERSON" -> PiiType.PERSON;
      case "LOC", "LOCATION" -> PiiType.ADDRESS;
      default -> null;
    };
  }

  /** The detector name on every span, {@code ner:<shortName>}. */
  public String name() {
    return detectorName;
  }

  @Override
  public List<PiiSpan> detect(TextLayer layer, DocumentContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    var spans = new ArrayList<PiiSpan>();
    try (Predictor<long[], float[][]> predictor = model.newPredictor()) {
      for (TextPage page : layer.pages()) {
        PageText text = PageText.of(page);
        if (text.wordCount() > 0) {
          spans.addAll(detectPage(predictor, text));
        }
      }
    }
    return spans;
  }

  private List<PiiSpan> detectPage(Predictor<long[], float[][]> predictor, PageText text) {
    Encoding encoding = tokenizer.encode(titleCaseUpperWords(text.text()), false, false);
    long[] ids = encoding.getIds();
    CharSpan[] charSpans = encoding.getCharTokenSpans();
    float[][] probs = new float[ids.length][];
    int[] margin = new int[ids.length];
    Arrays.fill(margin, -1);
    for (int start = 0; start < ids.length; start += step) {
      int end = Math.min(ids.length, start + window);
      float[][] logits = classify(predictor, text.pageNo(), ids, start, end);
      for (int i = start; i < end; i++) {
        int m = Math.min(i - start, end - 1 - i);
        if (m > margin[i]) {
          margin[i] = m;
          probs[i] = softmax(logits[i - start + 1]);
        }
      }
      if (end == ids.length) {
        break;
      }
    }
    PiiType[] wordTypes = new PiiType[text.wordCount()];
    for (int t = 0; t < ids.length; t++) {
      PiiType type = tokenType(probs[t]);
      CharSpan span = charSpans[t];
      if (type != null && span != null && span.getEnd() > span.getStart()) {
        int word = wordAt(text, span.getStart());
        if (wordTypes[word] != PiiType.PERSON) {
          wordTypes[word] = type;
        }
      }
    }
    return spans(text, wordTypes, stateWords(text), detectorName);
  }

  private float[][] classify(
      Predictor<long[], float[][]> predictor, int pageNo, long[] ids, int start, int end) {
    long[] input = new long[end - start + 2];
    input[0] = clsId;
    System.arraycopy(ids, start, input, 1, end - start);
    input[input.length - 1] = sepId;
    try {
      return predictor.predict(input);
    } catch (TranslateException e) {
      // The cause comes from ONNX Runtime and holds no document text.
      throw new IllegalStateException("NER inference failed on page " + pageNo, e);
    }
  }

  // The entity whose B- and I- probabilities together reach the threshold; PERSON wins ties,
  // because a name is the costlier miss.
  private PiiType tokenType(float[] probs) {
    double person = 0;
    double address = 0;
    for (int i = 0; i < probs.length && i < labelTypes.length; i++) {
      if (labelTypes[i] == PiiType.PERSON) {
        person += probs[i];
      } else if (labelTypes[i] == PiiType.ADDRESS) {
        address += probs[i];
      }
    }
    if (person >= threshold && person >= address) {
      return PiiType.PERSON;
    }
    return address >= threshold ? PiiType.ADDRESS : null;
  }

  static float[] softmax(float[] logits) {
    float max = Float.NEGATIVE_INFINITY;
    for (float l : logits) {
      max = Math.max(max, l);
    }
    double sum = 0;
    double[] exp = new double[logits.length];
    for (int i = 0; i < logits.length; i++) {
      exp[i] = Math.exp(logits[i] - max);
      sum += exp[i];
    }
    float[] probs = new float[logits.length];
    for (int i = 0; i < logits.length; i++) {
      probs[i] = (float) (exp[i] / sum);
    }
    return probs;
  }

  /**
   * {@code text} with words written all in capitals (two or more ASCII letters, no lower case)
   * turned into title case, keeping every offset. Invoices often print the buyer name in capitals,
   * and cased models rarely tag capitalised text as a person (measured in ADR-15).
   */
  static String titleCaseUpperWords(String text) {
    char[] chars = text.toCharArray();
    int start = 0;
    while (start < chars.length) {
      int end = start;
      while (end < chars.length && chars[end] != ' ') {
        end++;
      }
      int upper = 0;
      boolean lower = false;
      for (int i = start; i < end; i++) {
        upper += chars[i] >= 'A' && chars[i] <= 'Z' ? 1 : 0;
        lower |= Character.isLowerCase(chars[i]);
      }
      if (upper >= 2 && !lower) {
        for (int i = start + 1; i < end; i++) {
          boolean afterLetter = Character.isLetter(chars[i - 1]);
          if (afterLetter && chars[i] >= 'A' && chars[i] <= 'Z') {
            chars[i] = (char) (chars[i] + ('a' - 'A'));
          }
        }
      }
      start = end + 1;
    }
    return new String(chars);
  }

  /** Index of the word containing character {@code offset}, or the word before a space. */
  static int wordAt(PageText text, int offset) {
    int lo = 0;
    int hi = text.wordCount() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (text.wordStart(mid) <= offset) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return lo;
  }

  /** Words whose letters and digits all lie inside an Indian state/UT name (specs.md §6.1). */
  static boolean[] stateWords(PageText text) {
    boolean[] state = new boolean[text.wordCount()];
    Matcher m = STATE.matcher(text.text());
    String all = text.text();
    while (m.find()) {
      for (int w = wordAt(text, m.start()); w < state.length; w++) {
        int start = text.wordStart(w);
        if (start >= m.end()) {
          break;
        }
        int end = text.wordEnd(w);
        String before = all.substring(start, Math.max(start, Math.min(end, m.start())));
        String after = all.substring(Math.min(end, Math.max(start, m.end())), end);
        state[w] |= !Labels.hasAlnum(before) && !Labels.hasAlnum(after);
      }
    }
    return state;
  }

  /** Consecutive words of one type, minus state words, as spans over whole words. */
  static List<PiiSpan> spans(
      PageText text, PiiType[] wordTypes, boolean[] stateWords, String detector) {
    var spans = new ArrayList<PiiSpan>();
    int w = 0;
    while (w < wordTypes.length) {
      PiiType type = wordTypes[w];
      if (type == null || stateWords[w]) {
        w++;
        continue;
      }
      int last = w;
      while (last + 1 < wordTypes.length && wordTypes[last + 1] == type && !stateWords[last + 1]) {
        last++;
      }
      spans.add(new PiiSpan(text.pageNo(), text.wordStart(w), text.wordEnd(last), type, detector));
      w = last + 1;
    }
    return spans;
  }

  @Override
  public void close() {
    model.close();
    tokenizer.close();
  }

  @Override
  public String toString() {
    return "NerDetector[" + detectorName + "]";
  }

  /**
   * Token IDs (with [CLS] and [SEP]) in, per-token logits out. Inputs are named after the model's
   * inputs; models without {@code token_type_ids} (DistilBERT) don't get one. No batchifier: the
   * ONNX Runtime engine has no stack operator, so arrays are built with the batch axis.
   */
  private static final class LogitsTranslator implements Translator<long[], float[][]> {

    @Override
    public NDList processInput(TranslatorContext ctx, long[] ids) {
      NDManager manager = ctx.getNDManager();
      Shape shape = new Shape(1, ids.length);
      long[] mask = new long[ids.length];
      Arrays.fill(mask, 1);
      var list = new NDList();
      for (String name : ctx.getModel().describeInput().keys()) {
        NDArray array =
            switch (name) {
              case "input_ids" -> manager.create(ids, shape);
              case "attention_mask" -> manager.create(mask, shape);
              case "token_type_ids" -> manager.create(new long[ids.length], shape);
              default -> throw new IllegalStateException("unexpected model input " + name);
            };
        array.setName(name);
        list.add(array);
      }
      return list;
    }

    @Override
    public float[][] processOutput(TranslatorContext ctx, NDList list) {
      NDArray logits = list.get(0);
      long[] shape = logits.getShape().getShape();
      int tokens = Math.toIntExact(shape[1]);
      int labels = Math.toIntExact(shape[2]);
      float[] flat = logits.toFloatArray();
      float[][] out = new float[tokens][];
      for (int t = 0; t < tokens; t++) {
        out[t] = Arrays.copyOfRange(flat, t * labels, (t + 1) * labels);
      }
      return out;
    }

    @Override
    public Batchifier getBatchifier() {
      return null;
    }
  }
}
