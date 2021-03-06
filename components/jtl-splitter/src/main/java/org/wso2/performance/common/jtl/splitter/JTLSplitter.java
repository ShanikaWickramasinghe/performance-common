/*
 * Copyright 2017 WSO2 Inc. (http://wso2.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.performance.common.jtl.splitter;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Split JTL results file into warmup and measurement.
 */
public final class JTLSplitter {

    @Parameter(names = {"-t", "--warmup-time"}, description = "Warmup Time", required = true,
            validateWith = WarmupTimeValidator.class)
    private int warmupTime;

    @Parameter(names = {"-u", "--time-unit"}, description = "Time Unit")
    private TimeUnit timeUnit = TimeUnit.MINUTES;

    @Parameter(names = {"-f", "--jtlfile"}, description = "JTL File", required = true,
            validateValueWith = JTLFileValidator.class)
    private File jtlFile;

    @Parameter(names = {"-d", "--delete-jtl-file-on-exit"}, description = "Delete JTL File on exit")
    private boolean deleteJTLFileOnExit;

    @Parameter(names = {"-p", "--progress"}, description = "Show progress")
    private boolean showProgress;

    @Parameter(names = {"-s", "--summarize"}, description = "Summarize results")
    private boolean summarize;

    @Parameter(names = {"-n", "--precision"}, description = "Precision to use in statistics")
    private int precision = 2;

    @Parameter(names = {"-h", "--help"}, description = "Display Help", help = true)
    private boolean help = false;

    private static PrintStream errorOutput = System.err;
    private static PrintStream standardOutput = System.out;

    public static void main(String[] args) {
        JTLSplitter jtlSplitter = new JTLSplitter();
        final JCommander jcmdr = new JCommander(jtlSplitter);
        jcmdr.setProgramName(JTLSplitter.class.getSimpleName());

        try {
            jcmdr.parse(args);
        } catch (Exception e) {
            errorOutput.println(e.getMessage());
            return;
        }

        if (jtlSplitter.help) {
            jcmdr.usage();
            return;
        }

        jtlSplitter.splitJTL();
    }

    private void splitJTL() {
        long startTime = System.nanoTime();
        Path jtlPath = jtlFile.toPath();
        String fileName = jtlPath.getFileName().toString();
        String outputFilePrefix = fileName.substring(0, fileName.length() - 4);
        Path warmupJTLFile = jtlPath.resolveSibling(outputFilePrefix + "-warmup.jtl");
        Path measurementJTLFile = jtlPath.resolveSibling(outputFilePrefix + "-measurement.jtl");

        StatCalculator warmupStatCalculator = null;
        StatCalculator measurementStatCalculator = null;
        Path warmupSummaryJsonFile = jtlPath.resolveSibling(outputFilePrefix + "-warmup-summary.json");
        Path measurementSummaryJsonFile = jtlPath.resolveSibling(outputFilePrefix + "-measurement-summary.json");
        if (summarize) {
            warmupStatCalculator = new StatCalculator(precision);
            measurementStatCalculator = new StatCalculator(precision);
        }

        standardOutput.format("Splitting %s file into %s and %s.%n", fileName, warmupJTLFile.getFileName(),
                measurementJTLFile.getFileName());
        standardOutput.format("Warmup Time: %d %s%n", warmupTime, timeUnit);
        if (summarize) {
            standardOutput.format("Summarization is enabled. Summary statistics will be written to %s and %s.%n",
                    warmupSummaryJsonFile.getFileName(), measurementSummaryJsonFile.getFileName());
        }

        long timeLimit = timeUnit.toMillis(warmupTime);

        try (BufferedReader br = new BufferedReader(new FileReader(jtlFile));
             BufferedWriter bwWarmup = new BufferedWriter(new FileWriter(warmupJTLFile.toFile()));
             BufferedWriter bwMeasurement = new BufferedWriter(new FileWriter(measurementJTLFile.toFile()))) {
            // Read header
            String line = br.readLine();
            if (line != null) {
                // Write Header
                bwWarmup.write(line);
                bwWarmup.newLine();
                bwMeasurement.write(line);
                bwMeasurement.newLine();
            }

            long startTimestamp = Long.MAX_VALUE;
            // Current Line Number
            long lineNumber = 1;

            if (showProgress) {
                standardOutput.print("Started splitting...\r");
            }

            final int columnLimit = 16;

            lineLoop:
            while ((line = br.readLine()) != null) {
                int i = 0;
                String[] values = new String[columnLimit];
                int pos = 0, end;
                while ((end = line.indexOf(',', pos)) >= 0) {
                    if (i < columnLimit - 1) {
                        values[i++] = line.substring(pos, end);
                        pos = end + 1;
                    } else {
                        // Validate line
                        // JTL file usually has 16 columns
                        errorOutput.format("Line %d has more columns than expected: %s%n", lineNumber, line);
                        continue lineLoop;
                    }
                }
                // Add remaining
                values[i] = line.substring(pos);
                if (showProgress && lineNumber % 10_000 == 0) {
                    standardOutput.print("Processed " + lineNumber + " lines.\r");
                }
                long timestamp = Long.parseLong(values[0]);
                if (startTimestamp > timestamp) {
                    startTimestamp = timestamp;
                }
                long diff = timestamp - startTimestamp;
                final StatCalculator statCalculator;
                if (diff <= timeLimit) {
                    statCalculator = warmupStatCalculator;
                    bwWarmup.write(line);
                    bwWarmup.newLine();
                } else {
                    statCalculator = measurementStatCalculator;
                    bwMeasurement.write(line);
                    bwMeasurement.newLine();
                }
                if (summarize) {
                    Objects.requireNonNull(statCalculator).addSample(timestamp,
                            // elapsed
                            Integer.parseInt(values[1]),
                            // success
                            Boolean.parseBoolean(values[7]),
                            // bytes
                            Integer.parseInt(values[9]),
                            // sentBytes
                            Integer.parseInt(values[10]));
                }
                lineNumber++;
            }

            // Delete only if splitting is successful
            if (deleteJTLFileOnExit) {
                jtlFile.deleteOnExit();
            }
        } catch (IOException e) {
            errorOutput.println(e.getMessage());
        }


        if (summarize) {
            try (BufferedWriter bwWarmupSummary =
                         new BufferedWriter(new FileWriter(warmupSummaryJsonFile.toFile()));
                 BufferedWriter bwMeasurementSummary =
                         new BufferedWriter(new FileWriter(measurementSummaryJsonFile.toFile()))) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(Objects.requireNonNull(warmupStatCalculator).calculate(), bwWarmupSummary);
                gson.toJson(Objects.requireNonNull(measurementStatCalculator).calculate(), bwMeasurementSummary);
            } catch (IOException e) {
                errorOutput.println(e.getMessage());
            }
        }

        long elapsed = System.nanoTime() - startTime;
        // Add whitespace to clear progress information
        standardOutput.format("Done in %d min, %d sec.                           %n",
                TimeUnit.NANOSECONDS.toMinutes(elapsed),
                TimeUnit.NANOSECONDS.toSeconds(elapsed) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.NANOSECONDS.toMinutes(elapsed)));

    }
}
