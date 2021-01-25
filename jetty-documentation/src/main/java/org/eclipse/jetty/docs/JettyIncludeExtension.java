//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.ast.Document;
import org.asciidoctor.extension.IncludeProcessor;
import org.asciidoctor.extension.PreprocessorReader;
import org.asciidoctor.jruby.extension.spi.ExtensionRegistry;
import org.eclipse.jetty.tests.distribution.JettyHomeTester;

/**
 * <p>Asciidoctor <em>include</em> extension that includes into
 * the document the output produced by starting a Jetty server.</p>
 * <p>Example usage in an Asciidoc page:</p>
 * <pre>
 * include::jetty[setupArgs="--add-modules=http,deploy,demo-simple",highlight="WebAppContext"]
 * </pre>
 * <p>Available configuration parameters are:</p>
 * <dl>
 *   <dt>setupArgs</dt>
 *   <dd>Optional, specifies the arguments to use in a Jetty server <em>setup</em> run.
 *   If missing, no Jetty server <em>setup</em> run will be executed.
 *   The output produced by this run is ignored.</dd>
 *   <dt>args</dt>
 *   <dd>Optional, specifies the arguments to use in a Jetty server run.
 *   If missing, a Jetty server run will be executed with no arguments.
 *   The output produced by this run is included in the Asciidoc document.</dd>
 *   <dt>highlight</dt>
 *   <dd>Optional, specifies a regular expression that matches lines that should be highlighted.
 *   If missing, no line will be highlighted.
 *   If the regular expression contains capturing groups, only the text matching
 *   the groups is highlighted, not the whole line.
 *   </dd>
 * </dl>
 *
 * @see JettyHomeTester
 */
public class JettyIncludeExtension implements ExtensionRegistry
{
    public void register(Asciidoctor asciidoctor)
    {
        asciidoctor.javaExtensionRegistry().includeProcessor(JettyIncludeProcessor.class);
    }

    public static class JettyIncludeProcessor extends IncludeProcessor
    {
        @Override
        public boolean handles(String target)
        {
            return "jetty".equals(target);
        }

        @Override
        public void process(Document document, PreprocessorReader reader, String target, Map<String, Object> attributes)
        {
            try
            {
                Path projectPath = Path.of((String)document.getAttribute("projectdir"));
                Path jettyHome = projectPath.resolve("jetty-home/target/jetty-home").normalize();

                JettyHomeTester jetty = JettyHomeTester.Builder.newInstance()
                    .jettyHome(jettyHome)
                    .mavenLocalRepository((String)document.getAttribute("mavenrepository"))
                    .build();

                String setupArgs = (String)attributes.get("setupArgs");
                String args = (String)attributes.get("args");
                args = args == null ? "" : args + " ";
                args += jettyHome.resolve("etc/jetty-halt.xml");

                // Run first the setup arguments, then the normal arguments.
                if (setupArgs != null)
                {
                    try (JettyHomeTester.Run runSetup = jetty.start(setupArgs.split(" ")))
                    {
                        run0.awaitFor(15, TimeUnit.SECONDS);
                    }
                }
                if (args != null)
                {
                    try (JettyHomeTester.Run run = jetty.start(args.split(" ")))
                    {
                        run.awaitFor(15, TimeUnit.SECONDS);
                        String output = captureOutput(attributes, jetty, run);
                        reader.push_include(output, "jettyHome_run", target, 1, attributes);
                    }
                }
            }
            catch (Throwable x)
            {
                reader.push_include(x.toString(), "jettyHome_run", target, 1, attributes);
                x.printStackTrace();
            }
        }

        private String captureOutput(Map<String, Object> attributes, JettyHomeTester jetty, JettyHomeTester.Run run)
        {
            String highlight = (String)attributes.get("highlight");
            return run.getLogs().stream()
                .map(line -> redactPath(line, jetty.getJettyHome(), "/path/to/jetty.home"))
                .map(line -> redactPath(line, jetty.getJettyBase(), "/path/to/jetty.base"))
                .map(this::denoteLineStart)
                .map(line -> highlight(line, highlight))
                .collect(Collectors.joining(System.lineSeparator()));
        }

        private String redactPath(String line, Path path, String replacement)
        {
            return line.replaceAll(path.toString(), replacement);
        }

        private String denoteLineStart(String line)
        {
            // Matches lines that start with a date such as "2020-01-01 00:00:00.000:".
            Pattern lineStart = Pattern.compile("(^[^:]+:[^:]+:[^:]+:)");
            Matcher matcher = lineStart.matcher(line);
            if (!matcher.find())
                return line;
            return "**" + matcher.group(1) + "**" + line.substring(matcher.end(1));
        }

        private String highlight(String line, String regExp)
        {
            if (regExp == null)
                return line;

            Matcher matcher = Pattern.compile(regExp).matcher(line);
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
        }
    }
}
