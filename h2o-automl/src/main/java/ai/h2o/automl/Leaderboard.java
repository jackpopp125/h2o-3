package ai.h2o.automl;

import hex.*;
import water.*;
import water.api.schemas3.KeyV3;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Frame;
import water.util.IcedHashMap;
import water.util.Log;
import water.util.TwoDimTable;

import java.text.SimpleDateFormat;
import java.util.*;

import static water.DKV.getGet;
import static water.Key.make;

/**
 * Utility to track all the models built for a given dataset type.
 * <p>
 * Note that if a new Leaderboard is made for the same project it'll
 * keep using the old model list, which allows us to run AutoML multiple
 * times and keep adding to the leaderboard.
 * <p>
 * The models are returned sorted by either an appropriate default metric
 * for the model category (auc, mean per class error, or mean residual deviance),
 * or by a metric that's set via #setMetricAndDirection.
 * <p>
 * TODO: make this robust against removal of models from the DKV.
 */
public class Leaderboard extends Keyed<Leaderboard> {
  /**
   * Identifier for models that should be grouped together in the leaderboard
   * (e.g., "airlines" and "iris").
   */
  private final String project;

  /**
   * List of models for this leaderboard, sorted by metric so that the best is first,
   * according to the standard metric for the given model type.
   * <p>
   * Updated inside addModels().
   */
  private Key<Model>[] models = new Key[0];

  /**
   * Test set ModelMetrics objects for the models.
   * <p>
   * Updated inside addModels().
   */
  private IcedHashMap<Key<ModelMetrics>, ModelMetrics> test_set_metrics = new IcedHashMap<>();

  /**
   * Sort metrics for the models in this leaderboard, in the same order as the models.
   * <p>
   * Updated inside addModels().
   */
  public double[] sort_metrics = new double[0];

  /**
   * Metric used to sort this leaderboard.
   */
  private String sort_metric;

  /**
   * Metric direction used in the sort.
   */
  private boolean sort_decreasing;

  /**
   * UserFeedback object used to send, um, feedback to the, ah, user.  :-)
   * Right now this is a "new leader" message.
   */
  private UserFeedback userFeedback;

  /**
   * Frame for which we return the metrics, by default.
   */
  private Frame testFrame;

  /** HIDEME! */
  private Leaderboard() {
    throw new UnsupportedOperationException("Do not call the default constructor Leaderboard().");
  }

  /**
   *
   */
  public Leaderboard(String project, UserFeedback userFeedback, Frame testFrame) {
    this._key = make(idForProject(project));
    Leaderboard old = DKV.getGet(this._key);

    if (null != old) {
      // pick up where we left off
      // note that if subsequent runs use a different test frame the models are re-scored
      this.models = old.models;
      this.test_set_metrics = old.test_set_metrics;
      this.sort_metrics = old.sort_metrics;
    }
    this.project = project;
    this.userFeedback = userFeedback;
    this.testFrame = testFrame;
    DKV.put(this);
  }

  // satisfy typing for job return type...
  public static class LeaderboardKeyV3 extends KeyV3<Iced, LeaderboardKeyV3, Leaderboard> {
    public LeaderboardKeyV3() {
    }

    public LeaderboardKeyV3(Key<Leaderboard> key) {
      super(key);
    }
  }

  public static String idForProject(String project) { return "AutoML_Leaderboard_" + project; }

  public String getProject() {
    return project;
  }

  public void setMetricAndDirection(String metric, boolean sortDecreasing){
    this.sort_metric = metric;
    this.sort_decreasing = sortDecreasing;
    DKV.put(this);
  }

  public void setDefaultMetricAndDirection(Model m) {
    if (m._output.isBinomialClassifier())
      setMetricAndDirection("auc", true);
    else if (m._output.isClassifier())
      setMetricAndDirection("mean_per_class_error", false);
    else if (m._output.isSupervised())
      setMetricAndDirection("mean_residual_deviance", false);
  }

  /**
   * Add the given models to the leaderboard.  Note that to make this easier to use from
   * Grid, which returns its models in random order, we allow the caller to add the same
   * model multiple times and we eliminate the duplicates here.
   * @param newModels
   */
  final public void addModels(final Key<Model>[] newModels) {
    if (null == this._key)
      throw new H2OIllegalArgumentException("Can't add models to a Leaderboard which isn't in the DKV.");

    if (this.sort_metric == null) {
      // lazily set to default for this model category
      setDefaultMetricAndDirection(newModels[0].get());
    }

    final Key<Model> newLeader[] = new Key[1]; // only set if there's a new leader

    new TAtomic<Leaderboard>() {
      @Override
      final public Leaderboard atomic(Leaderboard old) {
        if (old == null) old = new Leaderboard();

        final Key<Model>[] oldModels = old.models;
        final Key<Model> oldLeader = (oldModels == null || 0 == oldModels.length) ? null : oldModels[0];

        // eliminate duplicates
        Set<Key<Model>> uniques = new HashSet(oldModels.length + newModels.length);
        uniques.addAll(Arrays.asList(oldModels));
        uniques.addAll(Arrays.asList(newModels));
        old.models = uniques.toArray(new Key[0]);

        // TODO: remove from tatomic?
        // which models are really new?  we need to call score on them
        Set<Key<Model>> reallyNewModels = new HashSet<>(uniques);
        reallyNewModels.removeAll(Arrays.asList(oldModels));

        // Try fetching ModelMetrics for *all* models, not just reallyNewModels,
        // because the testFrame might have changed.
        old.test_set_metrics = new IcedHashMap<>();
        for (Key<Model> aKey : old.models) {
          Model aModel = aKey.get();
          if (null == aModel) {
            userFeedback.warn(UserFeedbackEvent.Stage.ModelTraining, "Model in the leaderboard has unexpectedly been deleted from H2O: " + aKey);
            continue;
          }

          ModelMetrics mm = ModelMetrics.getFromDKV(aModel, testFrame);
          if (mm == null) {
            Frame preds = aModel.score(testFrame);
            mm = ModelMetrics.getFromDKV(aModel, testFrame);
          }
          old.test_set_metrics.put(mm._key, mm);
        }

        // Sort by metric on the test set.
        // TODO TODO TODO this sorts by the metrics in Model._output
        try {
          List<Key<Model>> modelsSorted = ModelMetrics.sortModelsByMetric(testFrame, sort_metric, sort_decreasing, Arrays.asList(old.models));
          old.models = modelsSorted.toArray(new Key[0]);
        }
        catch (H2OIllegalArgumentException e) {
          Log.warn("ModelMetrics.sortModelsByMetric failed: " + e);
          throw e;
        }

        Model[] models = new Model[old.models.length];
        old.sort_metrics = old.getSortMetrics(old.sort_metric, old.test_set_metrics, testFrame, modelsForModelKeys(old.models, models));

        // If we're updated leader let this know so that it can notify the user
        // (outside the tatomic, since it can take a long time).
        if (oldLeader == null || ! oldLeader.equals(old.models[0]))
          newLeader[0] = old.models[0];

        return old;
      } // atomic
    }.invoke(this._key);

    // We've updated the DKV but not this instance, so:
    Leaderboard updated = DKV.getGet(this._key);
    this.models = updated.models;
    this.test_set_metrics = updated.test_set_metrics;
    this.sort_metrics = updated.sort_metrics;

    // always
    EckoClient.updateLeaderboard(this);
    if (null != newLeader[0]) {
      userFeedback.info(UserFeedbackEvent.Stage.ModelTraining, "New leader: " + newLeader[0]);
    }
  }


  public void addModel(final Key<Model> key) {
    Key<Model>keys[] = new Key[1];
    keys[0] = key;
    addModels(keys);
  }

  public void addModel(final Model model) {
    Key<Model>keys[] = new Key[1];
    keys[0] = model._key;
    addModels(keys);
  }

  private static Model[] modelsForModelKeys(Key<Model>[] modelKeys, Model[] models) {
    assert models.length >= modelKeys.length;
    int i = 0;
    for (Key<Model> modelKey : modelKeys)
      models[i++] = getGet(modelKey);
    return models;
  }

  /**
   * @return list of keys of models sorted by the default metric for the model category, fetched from the DKV
   */
  public Key<Model>[] getModelKeys() {
    return ((Leaderboard)DKV.getGet(this._key)).models;
  }

  /**
   * @return list of keys of models sorted by the given metric, fetched from the DKV
   */
  public Key<Model>[] modelKeys(String metric, boolean sortDecreasing) {
    Key<Model>[] models = getModelKeys();
    List<Key<Model>> newModelsSorted =
            ModelMetrics.sortModelsByMetric(metric, sortDecreasing, Arrays.asList(models));
    return newModelsSorted.toArray(new Key[0]);
  }

  /**
   * @return list of models sorted by the default metric for the model category
   */
  public Model[] getModels() {
    Key<Model>[] modelKeys = getModelKeys();

    if (modelKeys == null || 0 == modelKeys.length) return new Model[0];

    Model[] models = new Model[modelKeys.length];
    return modelsForModelKeys(modelKeys, models);
  }

  /**
   * @return list of models sorted by the given metric
   */
  public Model[] getModels(String metric, boolean sortDecreasing) {
    Key<Model>[] modelKeys = modelKeys(metric, sortDecreasing);

    if (modelKeys == null || 0 == modelKeys.length) return new Model[0];

    Model[] models = new Model[modelKeys.length];
    return modelsForModelKeys(modelKeys, models);
  }

  public Model getLeader() {
    Key<Model>[] modelKeys = getModelKeys();

    if (modelKeys == null || 0 == modelKeys.length) return null;

    return modelKeys[0].get();
  }

  /*
  public long[] getTimestamps(Model[] models) {
    long[] timestamps = new long[models.length];
    int i = 0;
    for (Model m : models)
      timestamps[i++] = m._output._end_time;
    return timestamps;
  }
  */

  public double[] getSortMetrics() {
    return getSortMetrics(this.sort_metric, this.test_set_metrics, this.testFrame, this.getModels());
  }

  public static double[] getSortMetrics(String sort_metric, IcedHashMap<Key<ModelMetrics>, ModelMetrics> test_set_metrics, Frame testFrame, Model[] models) {
    double[] sort_metrics = new double[models.length];
    int i = 0;
    for (Model m : models)
      sort_metrics[i++] = ModelMetrics.getMetricFromModelMetric(test_set_metrics.get(ModelMetrics.buildKey(m, testFrame)), sort_metric);
    return sort_metrics;
  }

  /**
   * Delete everything in the DKV that this points to.  We currently need to be able to call this after deleteWithChildren().
   */
  public void delete() {
    remove();
  }

  public void deleteWithChildren() {
    for (Model m : getModels())
      m.delete();
    delete();
  }

  public static double defaultMetricForModel(Model m) {
    ModelMetrics mm =
            m._output._cross_validation_metrics != null ?
                    m._output._cross_validation_metrics :
                    m._output._validation_metrics != null ?
                            m._output._validation_metrics :
                            m._output._training_metrics;
    return defaultMetricForModel(m, mm);
  }

  public static double defaultMetricForModel(Model m, ModelMetrics mm) {
    if (m._output.isBinomialClassifier()) {
      return(((ModelMetricsBinomial)mm).auc());
    } else if (m._output.isClassifier()) {
      return(((ModelMetricsMultinomial)mm).mean_per_class_error());
    } else if (m._output.isSupervised()) {
      return(((ModelMetricsRegression)mm).mean_residual_deviance());
    }
    Log.warn("Failed to find metric for model: " + m);
    return Double.NaN;
  }

  public static String defaultMetricNameForModel(Model m) {
    if (m._output.isBinomialClassifier()) {
      return "auc";
    } else if (m._output.isClassifier()) {
      return "mean per-class error";
    } else if (m._output.isSupervised()) {
      return "mean residual deviance";
    }
    return "unknown";
  }

  public String rankTsv() {
    String fieldSeparator = "\\t";
    String lineSeparator = "\\n";

    StringBuffer sb = new StringBuffer();
//  sb.append("Rank").append(fieldSeparator).append("Error").append(lineSeparator);
    sb.append("Error").append(lineSeparator);

    Model[] models = getModels();
    for (int i = models.length - 1; i >= 0; i--) {
      // TODO: allow the metric to be passed in.  Note that this assumes the validation (or training) frame is the same.
      Model m = models[i];
      sb.append(defaultMetricForModel(m));
      sb.append(lineSeparator);
    }
    return sb.toString();
  }

  public String timeTsv() {
    String fieldSeparator = "\\t";
    String lineSeparator = "\\n";

    StringBuffer sb = new StringBuffer();
    //sb.append("Time").append(fieldSeparator).append("Error").append(lineSeparator);
    sb.append("Error").append(lineSeparator);

    Model[] models = getModels();
    for (int i = models.length - 1; i >= 0; i--) {
      // TODO: allow the metric to be passed in.  Note that this assumes the validation (or training) frame is the same.
      Model m = models[i];
      //sb.append(timestampFormat.format(m._output._end_time));
      //sb.append(fieldSeparator);

      sb.append(defaultMetricForModel(m));
      sb.append(lineSeparator);
    }
    return sb.toString();
  }

  protected static final String[] colHeaders(String metric) {
    //return new String[] {"model ID", "timestamp", metric.toString()};
    return new String[] {"model_id", metric.toString()};
  }

  protected static final String[] colTypes= {
          "string",
          //"string",
          "string" };

  protected static final String[] colFormats= {
          "%s",
          //"%s",
          "%s" };

  public static final TwoDimTable makeTwoDimTable(String tableHeader, String sort_metric, int length) {
    String[] rowHeaders = new String[length];
    for (int i = 0; i < length; i++) rowHeaders[i] = "" + i;

    return new TwoDimTable(tableHeader,
            "models sorted in order of " + sort_metric + ", best first",
            rowHeaders,
            Leaderboard.colHeaders(sort_metric),
            Leaderboard.colTypes,
            Leaderboard.colFormats,
            "#");
  }


  //public void addTwoDimTableRow(TwoDimTable table, int row, String[] modelIDs, long[] timestamps, double[] errors) {
  public void addTwoDimTableRow(TwoDimTable table, int row, String[] modelIDs, double[] errors) {
    int col = 0;
    table.set(row, col++, modelIDs[row]);
    //table.set(row, col++, timestampFormat.format(new Date(timestamps[row])));
    table.set(row, col++, String.format("%.6f", errors[row]));
  }

  public TwoDimTable toTwoDimTable() {
    return toTwoDimTable("Leaderboard for project: " + project, false);
  }

  public TwoDimTable toTwoDimTable(String tableHeader, boolean leftJustifyModelIds) {
    Model[] models = this.getModels();
    //long[] timestamps = getTimestamps(models);
    String[] modelIDsFormatted = new String[models.length];

    TwoDimTable table = makeTwoDimTable(tableHeader, sort_metric, models.length);

    // %-s doesn't work in TwoDimTable.toString(), so fake it here:
    int maxModelIdLen = -1;
    for (Model m : models)
      maxModelIdLen = Math.max(maxModelIdLen, m._key.toString().length());
    for (int i = 0; i < models.length; i++)
      if (leftJustifyModelIds) {
        modelIDsFormatted[i] =
                (models[i]._key.toString() +
                        "                                                                                         ")
                        .substring(0, maxModelIdLen);
      } else {
        modelIDsFormatted[i] = models[i]._key.toString();
      }

    for (int i = 0; i < models.length; i++)
      //addTwoDimTableRow(table, i, modelIDsFormatted, timestamps, sort_metrics);
      addTwoDimTableRow(table, i, modelIDsFormatted, sort_metrics);
    return table;
  }

  //private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS");

  //public static String toString(String project, Model[] models, String fieldSeparator, String lineSeparator, boolean includeTitle, boolean includeHeader, boolean includeTimestamp) {
  public static String toString(String project, Model[] models, String fieldSeparator, String lineSeparator, boolean includeTitle, boolean includeHeader) {
    StringBuilder sb = new StringBuilder();
    if (includeTitle) {
      sb.append("Leaderboard for project \"")
              .append(project)
              .append("\": ");

      if (models.length == 0) {
        sb.append("<empty>");
        return sb.toString();
      }
      sb.append(lineSeparator);
    }

    boolean printedHeader = false;
    for (Model m : models) {
      // TODO: allow the metric to be passed in.  Note that this assumes the validation (or training) frame is the same.
      if (includeHeader && ! printedHeader) {
        sb.append("model_id");
        sb.append(fieldSeparator);

        sb.append(defaultMetricNameForModel(m));

        /*
        if (includeTimestamp) {
          sb.append(fieldSeparator);
          sb.append("timestamp");
        }
        */
        sb.append(lineSeparator);
        printedHeader = true;
      }

      sb.append(m._key.toString());
      sb.append(fieldSeparator);

      sb.append(defaultMetricForModel(m));

      /*
      if (includeTimestamp) {
        sb.append(fieldSeparator);
        sb.append(timestampFormat.format(m._output._end_time));
      }
      */

      sb.append(lineSeparator);
    }
    return sb.toString();
  }

  public String toString(String fieldSeparator, String lineSeparator) {
    //return toString(project, getModels(), fieldSeparator, lineSeparator, true, true, false);
    return toString(project, getModels(), fieldSeparator, lineSeparator, true, true);
  }

  @Override
  public String toString() {
    return toString(" ; ", " | ");
  }
}
