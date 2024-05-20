//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.hometester;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>A Java application that captures the output of starting the Jetty server with
 * a given set of parameters, filters the output, and prints it back to STDOUT.</p>
 * <p>This application is used in the Jetty documentation to show the output from starting
 * the Jetty server.</p>
 * <p>Example usage:</p>
 * <pre>
 * java -cp $RUN_JETTY_CLASSPATH org.eclipse.jetty.tests.hometester.RunJetty --jetty-home $JETTY_HOME
 * </pre>
 * <p>Available options are:</p>
 * <dl>
 *   <dt>jetty-home</dt>
 *   <dd>Required, the path to where Jetty is installed.</dd>
 *   <dt>jetty-base</dt>
 *   <dd>Optional, the path to the Jetty base directory where customizations are located.
 *   If not provided, will be automatically created.</dd>
 *   <dt>setup-args</dt>
 *   <dd>Optional, specifies the arguments to use in a Jetty server <em>setup</em> run.
 *   If missing, no Jetty server <em>setup</em> run will be executed.
 *   The output produced by this run is ignored.</dd>
 *   <dt>args</dt>
 *   <dd>Optional, specifies the arguments to use in a Jetty server run.
 *   If missing, a Jetty server run will be executed with no arguments.
 *   The output produced by this run is included in the Asciidoc document.</dd>
 *   <dt>replace</dt>
 *   <dd>Optional, specifies a comma-separated pair where the first element is a regular
 *   expression and the second is the string replacement.</dd>
 *   <dt>delete</dt>
 *   <dd>Optional, specifies a regular expression that when matched deletes the line</dd>
 *   <dt>highlight</dt>
 *   <dd>Optional, specifies a regular expression that matches lines that should be highlighted.
 *   If missing, no line will be highlighted.
 *   If the regular expression contains capturing groups, only the text matching
 *   the groups is highlighted, not the whole line.
 *   </dd>
 *   <dt>callouts</dt>
 *   <dd>Optional, specifies a comma-separated pair where the first element is a callout
 *   pattern, and the second element is a comma-separated list of regular expressions,
 *   each matching a single line, that get a callout added at the end of the line.</dd>
 * </dl>
 *
 * @see JettyHomeTester
 */
public class RunJetty
{
    public static void main(String[] args)
    {
        Map<String, String> config = new HashMap<String, String>();
        String name = null;
        for (String arg : args)
        {
            if (arg.startsWith("--") && arg.contains("="))
            {
                String[] nameAndValue = arg.split("=", 2);
                config.put(nameAndValue[0].substring(2), nameAndValue[1]);
            }
        }
        if (!config.containsKey("jetty-home"))
        {
            throw new RuntimeException("--jetty-home argument is required");
        }
        new RunJetty().run(config);
    }

    public RunJetty()
    {
    }

    public void run(Map<String, String> config)
    {
        try
        {
            Path jettyHome = Path.of(config.get("jetty-home"));
            Path jettyBase = Path.of(config.getOrDefault("jetty-base", jettyHome.toString() + "-base"));

            JettyHomeTester jetty = JettyHomeTester.Builder.newInstance()
                .jettyHome(jettyHome)
                .jettyBase(jettyBase)
                .mavenLocalRepository(config.get("maven-local-repo"))
                .build();

            String setupArgs = config.get("setup-args");
            if (setupArgs != null)
            {
                try (JettyHomeTester.Run setupRun = jetty.start(setupArgs.split(" ")))
                {
                    setupRun.awaitFor(15, TimeUnit.SECONDS);
                }
            }

            String args = config.get("args");
            args = args == null ? "" : args + " ";
            args += jettyHome.resolve("etc/jetty-halt.xml");
            try (JettyHomeTester.Run run = jetty.start(args.split(" ")))
            {
                run.awaitFor(15, TimeUnit.SECONDS);
                System.out.println(captureOutput(config, run));
            }
        }
        catch (Throwable x)
        {
            throw new RuntimeException(x);
        }
    }

    private String captureOutput(Map<String, String> config, JettyHomeTester.Run run)
    {
        final String actualVersion = (String)config.get("jetty-version");
        final String stableVersion = actualVersion == null ? null : actualVersion.replace("-SNAPSHOT", "");
        Stream<String> lines = run.getLogs().stream()
            .map(line -> redact(line, System.getProperty("java.home"), "/path/to/java.home"))
            .map(line -> redact(line, run.getConfig().getMavenLocalRepository(), "/path/to/maven.repository"))
            .map(line -> redact(line, run.getConfig().getJettyHome().toString(), "/path/to/jetty.home"))
            .map(line -> redact(line, run.getConfig().getJettyBase().toString(), "/path/to/jetty.base"))
            .map(line -> redact(line, actualVersion, stableVersion))
            .map(line -> regexpRedact(line, "(^| )[^ ]+/etc/jetty-halt\\.xml", ""));
        lines = replace(lines, config.get("replace"));
        lines = delete(lines, config.get("delete"));
        lines = denoteLineStart(lines);
        lines = highlight(lines, config.get("highlight"));
        lines = callouts(lines, config.get("callouts"));
        return lines.collect(Collectors.joining(System.lineSeparator()));
    }

    private String redact(String line, String target, String replacement)
    {
        if (target != null && replacement != null)
            return line.replace(target, replacement);
        return line;
    }

    private String regexpRedact(String line, String regexp, String replacement)
    {
        if (regexp != null && replacement != null)
            return line.replaceAll(regexp, replacement);
        return line;
    }

    private Stream<String> replace(Stream<String> lines, String replace)
    {
        if (replace == null)
            return lines;

        // Format is: (regexp,replacement).
        String[] parts = replace.split(",");
        String regExp = parts[0];
        String replacement = parts[1].replace("\\n", "\n");

        return lines.flatMap(line -> Stream.of(line.replaceAll(regExp, replacement).split("\n")));
    }

    private Stream<String> delete(Stream<String> lines, String delete)
    {
        if (delete == null)
            return lines;
        Pattern regExp = Pattern.compile(delete);
        return lines.filter(line -> !regExp.matcher(line).find());
    }

    private Stream<String> denoteLineStart(Stream<String> lines)
    {
        // Matches lines that start with a date such as "2020-01-01 00:00:00.000:".
        Pattern regExp = Pattern.compile("(^\\d{4}[^:]+:[^:]+:[^:]+:)");
        return lines.map(line ->
        {
            Matcher matcher = regExp.matcher(line);
            if (!matcher.find())
                return line;
            return "**" + matcher.group(1) + "**" + line.substring(matcher.end(1));
        });
    }

    private Stream<String> highlight(Stream<String> lines, String highlight)
    {
        if (highlight == null)
            return lines;

        Pattern regExp = Pattern.compile(highlight);
        return lines.map(line ->
        {
            Matcher matcher = regExp.matcher(line);
            if (!matcher.find())
                return line;

            int groupCount = matcher.groupCount();

            // No capturing groups, highlight the whole line.
            if (groupCount == 0)
                return "##" + line + "##";

            // Highlight the capturing groups.
            StringBuilder result = new StringBuilder(line.length() + 4 * groupCount);
            int start = 0;
            for (int groupIndex = 1; groupIndex <= groupCount; ++groupIndex)
            {
                int matchBegin = matcher.start(groupIndex);
                result.append(line, start, matchBegin);
                result.append("##");
                int matchEnd = matcher.end(groupIndex);
                result.append(line, matchBegin, matchEnd);
                result.append("##");
                start = matchEnd;
            }
            result.append(line, start, line.length());
            return result.toString();
        });
    }

    private Stream<String> callouts(Stream<String> lines, String callouts)
    {
        if (callouts == null)
            return lines;

        // Format is (prefix$Nsuffix,regExp...).
        String[] parts = callouts.split(",");
        String calloutPattern = parts[0];
        List<Pattern> regExps = Stream.of(parts)
            .skip(1)
            .map(Pattern::compile)
            .collect(Collectors.toList());

        AtomicInteger index = new AtomicInteger();

        return lines.map(line ->
        {
            int regExpIndex = index.get();
            if (regExpIndex == regExps.size())
                return line;
            Pattern regExp = regExps.get(regExpIndex);
            if (!regExp.matcher(line).find())
                return line;
            int calloutIndex = index.incrementAndGet();
            return line + calloutPattern.replace("$N", String.valueOf(calloutIndex));
        });
    }
}
