//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.utility.DockerStatus;

public class AutobahnUtils
{
    private static final Logger LOG = LoggerFactory.getLogger(AutobahnUtils.class);

    public static void throwIfFailed(List<AutobahnCaseResult> results) throws Exception
    {
        StringBuilder message = new StringBuilder();
        for (AutobahnCaseResult result : results)
        {
            if (result.failed())
                message.append(result.caseName).append(", ");
        }

        if (message.length() > 0)
            throw new Exception("Failed Test Cases: " + message);
    }

    public static class FileSignalWaitStrategy extends StartupCheckStrategy
    {
        public static final String SIGNAL_FILE = "/signalComplete";
        public static final String END_COMMAND = " && touch " + SIGNAL_FILE + " && sleep infinity";

        Path _localDir;
        Path _containerDir;

        public FileSignalWaitStrategy(Path localDir, Path containerDir)
        {
            _localDir = localDir;
            _containerDir = containerDir;
            withTimeout(Duration.ofHours(2));
        }

        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId)
        {
            // If the container was stopped then we have failed to copy out the file.
            if (DockerStatus.isContainerStopped(getCurrentState(dockerClient, containerId)))
                return StartupStatus.FAILED;

            try
            {
                dockerClient.copyArchiveFromContainerCmd(containerId, SIGNAL_FILE).exec().close();
            }
            catch (FileNotFoundException | NotFoundException e)
            {
                return StartupStatus.NOT_YET_KNOWN;
            }
            catch (Throwable t)
            {
                LOG.warn("Unknown Error", t);
                return StartupStatus.FAILED;
            }

            try
            {
                copyFromContainer(dockerClient, containerId, _localDir, _containerDir);
                return StartupStatus.SUCCESSFUL;
            }
            catch (Throwable t)
            {
                LOG.warn("Error copying reports", t);
                return StartupStatus.FAILED;
            }
        }
    }

    public static void copyFromContainer(DockerClient dockerClient, String containerId, Path target, Path source) throws Exception
    {
        try (TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(dockerClient
            .copyArchiveFromContainerCmd(containerId, source.toString())
            .exec()))
        {
            ArchiveEntry archiveEntry;
            while ((archiveEntry = tarArchiveInputStream.getNextEntry()) != null)
            {
                Path filePath = target.resolve(archiveEntry.getName());
                if (archiveEntry.isDirectory())
                {
                    if (!Files.exists(filePath))
                        Files.createDirectory(filePath);
                    continue;
                }
                Files.copy(tarArchiveInputStream, filePath);
            }
        }
    }

    public static void writeJUnitXmlReport(List<AutobahnCaseResult> results, String surefireFileName, String testName) throws Exception
    {
        int failures = 0;
        long suiteDuration = 0;
        Xpp3Dom root = new Xpp3Dom("testsuite");
        root.setAttribute("name", testName);
        root.setAttribute("tests", Integer.toString(results.size()));
        root.setAttribute("errors", Integer.toString(0));
        root.setAttribute("skipped", Integer.toString(0));

        for (AutobahnCaseResult r: results)
        {
            Xpp3Dom testcase = new Xpp3Dom("testcase");
            testcase.setAttribute("classname", testName);
            testcase.setAttribute("name", r.caseName());

            long duration = r.duration();
            suiteDuration += duration;
            testcase.setAttribute("time", Double.toString(duration / 1000.0));

            if (r.failed())
            {
                addFailure(testcase, r);
                failures++;
            }
            root.addChild(testcase);
        }
        root.setAttribute("failures", Integer.toString(failures));
        root.setAttribute("time", Double.toString(suiteDuration / 1000.0));

        Path surefireReportsDir = Paths.get("target/surefire-reports");
        if (!Files.exists(surefireReportsDir))
            Files.createDirectories(surefireReportsDir);

        String filename = "TEST-" + surefireFileName + ".xml";
        try (Writer writer = Files.newBufferedWriter(surefireReportsDir.resolve(filename)))
        {
            Xpp3DomWriter.write(writer, root);
        }
    }

    public static void addFailure(Xpp3Dom testCase, AutobahnCaseResult result) throws IOException, ParseException
    {

        JSONParser parser = new JSONParser();

        try (Reader reader = Files.newBufferedReader(Paths.get(result.reportFile())))
        {
            JSONObject object = (JSONObject)parser.parse(reader);

            Xpp3Dom sysout = new Xpp3Dom("system-out");
            sysout.setValue(object.toJSONString());
            testCase.addChild(sysout);

            String description = object.get("description").toString();
            String resultText = object.get("result").toString();
            String expected = object.get("expected").toString();
            String received = object.get("received").toString();

            StringBuilder fail = new StringBuilder();
            fail.append(description).append("\n\n");
            fail.append("Case outcome").append("\n\n");
            fail.append(resultText).append("\n\n");
            fail.append("Expected").append("\n").append(expected).append("\n\n");
            fail.append("Received").append("\n").append(received).append("\n\n");

            Xpp3Dom failure = new Xpp3Dom("failure");
            failure.setAttribute("type", "behaviorMissmatch");
            failure.setValue(fail.toString());
            testCase.addChild(failure);
        }
    }

    public static List<AutobahnCaseResult> parseResults(Path jsonPath) throws Exception
    {
        List<AutobahnCaseResult> results = new ArrayList<>();
        JSONParser parser = new JSONParser();

        try (Reader reader = Files.newBufferedReader(jsonPath))
        {
            JSONObject object = (JSONObject)parser.parse(reader);
            JSONObject agent = (JSONObject)object.values().iterator().next();
            if (agent == null)
                throw new Exception("no agent");

            for (Object cases : agent.keySet())
            {
                JSONObject c = (JSONObject)agent.get(cases);
                String behavior = (String)c.get("behavior");
                String behaviorClose = (String)c.get("behaviorClose");
                Number duration = (Number)c.get("duration");
                Number remoteCloseCode = (Number)c.get("remoteCloseCode");

                Long code = (remoteCloseCode == null) ? null : remoteCloseCode.longValue();
                String reportfile = (String)c.get("reportfile");
                AutobahnCaseResult result = new AutobahnCaseResult(cases.toString(),
                    AutobahnCaseResult.Behavior.parse(behavior),
                    AutobahnCaseResult.Behavior.parse(behaviorClose),
                    duration.longValue(), code,
                    jsonPath.toFile().getParent() + File.separator + reportfile);

                results.add(result);
            }
        }
        catch (Exception e)
        {
            throw new Exception("Could not parse results", e);
        }
        return results;
    }

    public static class AutobahnCaseResult
    {
        enum Behavior
        {
            FAILED,
            OK,
            NON_STRICT,
            WRONG_CODE,
            UNCLEAN,
            FAILED_BY_CLIENT,
            INFORMATIONAL,
            UNIMPLEMENTED;

            static Behavior parse(String value)
            {
                switch (value)
                {
                    case "NON-STRICT":
                        return NON_STRICT;
                    case "WRONG CODE":
                        return WRONG_CODE;
                    case "FAILED BY CLIENT":
                        return FAILED_BY_CLIENT;
                    default:
                        return valueOf(value);
                }
            }
        }

        private final String caseName;
        private final Behavior behavior;
        private final Behavior behaviorClose;
        private final long duration;
        private final Long remoteCloseCode;
        private final String reportFile;

        AutobahnCaseResult(String caseName, Behavior behavior, Behavior behaviorClose, long duration, Long remoteCloseCode, String reportFile)
        {
            this.caseName = caseName;
            this.behavior = behavior;
            this.behaviorClose = behaviorClose;
            this.duration = duration;
            this.remoteCloseCode = remoteCloseCode;
            this.reportFile = reportFile;
        }

        public String caseName()
        {
            return caseName;
        }

        public Behavior behavior()
        {
            return behavior;
        }

        public boolean failed()
        {
            switch (behavior)
            {
                case OK:
                case INFORMATIONAL:
                case UNIMPLEMENTED:
                    return false;

                case NON_STRICT:
                default:
                    return true;
            }
        }

        public Behavior behaviorClose()
        {
            return behaviorClose;
        }

        public long duration()
        {
            return duration;
        }

        public Long remoteCloseCode()
        {
            return remoteCloseCode;
        }

        public String reportFile()
        {
            return reportFile;
        }

        @Override
        public String toString()
        {
            return "[" + caseName + "] behavior: " + behavior.name() + ", behaviorClose: " + behaviorClose.name() +
                ", duration: " + duration + "ms, remoteCloseCode: " + remoteCloseCode + ", reportFile: " + reportFile;
        }
    }
}
