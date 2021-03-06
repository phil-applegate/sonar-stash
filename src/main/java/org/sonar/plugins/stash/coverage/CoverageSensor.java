package org.sonar.plugins.stash.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.BatchComponent;
import org.sonar.api.batch.Phase;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issue;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.plugins.stash.StashPluginConfiguration;
import org.sonar.wsclient.Sonar;

import java.text.MessageFormat;

import static org.sonar.plugins.stash.StashPluginUtils.formatPercentage;
import static org.sonar.plugins.stash.StashPluginUtils.roundedPercentageGreaterThan;
import static org.sonar.plugins.stash.coverage.CoverageUtils.calculateCoverage;
import static org.sonar.plugins.stash.coverage.CoverageUtils.createSonarClient;
import static org.sonar.plugins.stash.coverage.CoverageUtils.getLineCoverage;

// We have to execute after all coverage sensors, otherwise we are not able to read their measurements
@Phase(name = Phase.Name.POST)
public class CoverageSensor implements Sensor, BatchComponent {
  private static final Logger LOGGER = LoggerFactory.getLogger(CoverageSensor.class);

  private final FileSystem fileSystem;
  private final ResourcePerspectives perspectives;
  private final StashPluginConfiguration config;
  private ActiveRules activeRules;
  private CoverageProjectStore coverageProjectStore;


  public CoverageSensor(FileSystem fileSystem,
                        ResourcePerspectives perspectives,
                        StashPluginConfiguration config,
                        ActiveRules activeRules,
                        CoverageProjectStore coverageProjectStore) {
    this.fileSystem = fileSystem;
    this.perspectives = perspectives;
    this.config = config;
    this.activeRules = activeRules;
    this.coverageProjectStore = coverageProjectStore;
  }

  @Override
  public void analyse(Project module, SensorContext context) {
    Sonar sonar = createSonarClient(config);

    for (InputFile f : fileSystem.inputFiles(fileSystem.predicates().all())) {
      Integer linesToCover = null;
      Integer uncoveredLines = null;

      Resource fileResource = context.getResource(f);
      Measure<Integer> linesToCoverMeasure = context.getMeasure(fileResource, CoreMetrics.LINES_TO_COVER);
      if (linesToCoverMeasure != null) {
        linesToCover = linesToCoverMeasure.value();
      }

      Measure<Integer> uncoveredLinesMeasure = context.getMeasure(fileResource, CoreMetrics.UNCOVERED_LINES);
      if (uncoveredLinesMeasure != null) {
        uncoveredLines = uncoveredLinesMeasure.value();
      }

      // get lines_to_cover, uncovered_lines
      if ((linesToCover != null) && (uncoveredLines != null)) {
        Double previousCoverage = getLineCoverage(sonar, fileResource.getEffectiveKey());

        double coverage = calculateCoverage(linesToCover, uncoveredLines);

        coverageProjectStore.updateMeasurements(linesToCover, uncoveredLines);

        if (previousCoverage == null) {
          continue;
        }

        // The API returns the coverage rounded.
        // So we can only report anything if the rounded value has changed,
        // otherwise we could report false positives.
        if (roundedPercentageGreaterThan(previousCoverage, coverage)) {
          addIssue(f, coverage, previousCoverage);
        }
      }
    }
  }

  private void addIssue(InputFile file, double coverage, double previousCoverage) {
    Issuable issuable = perspectives.as(Issuable.class, file);
    if (issuable == null) {
      LOGGER.warn("Could not get a perspective of Issuable to create an issue for {}, skipping", file);
      return;
    }

    String message = formatIssueMessage(file.relativePath(), coverage, previousCoverage);
    Issue issue = issuable.newIssueBuilder()
                          .ruleKey(CoverageRule.decreasingLineCoverageRule(file.language()))
                          .message(message)
                          .build();
    issuable.addIssue(issue);
  }

  static String formatIssueMessage(String path, double coverage, double previousCoverage) {
    return MessageFormat.format("Line coverage of file {0} lowered from {1}% to {2}%.",
                                path, formatPercentage(previousCoverage), formatPercentage(coverage));
  }

  @Override
  public String toString() {
    return "Stash Plugin Coverage Sensor";
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return CoverageUtils.shouldExecuteCoverage(config, activeRules);
  }
}
