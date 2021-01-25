//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.start.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.PathFinder;
import org.eclipse.jetty.start.PathMatchers;
import org.eclipse.jetty.start.Utils;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

/**
 * Simple utility to scan all of the mod files and correct references
 * to maven central URL in [files] sections to the new maven:// syntax
 */
public class CorrectMavenCentralRefs
{
    public static void main(String[] args)
    {
        Path buildRoot = MavenTestingUtils.getProjectDir("..").toPath();
        buildRoot = buildRoot.normalize().toAbsolutePath();

        // Test to make sure we are in right directory
        Path rootPomXml = buildRoot.resolve("pom.xml");
        Path distPomXml = buildRoot.resolve("jetty-distribution/pom.xml");
        if (!Files.exists(rootPomXml) || !Files.exists(distPomXml))
        {
            System.err.println("Not build root directory: " + buildRoot);
            System.exit(-1);
        }

        try
        {
            new CorrectMavenCentralRefs().fix(buildRoot);
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }

    public void fix(Path buildRoot) throws IOException
    {
        // Find all of the *.mod files
        PathFinder finder = new PathFinder();
        finder.setFileMatcher("glob:**/*.mod");
        finder.setBase(buildRoot);

        // Matcher for target directories
        PathMatcher targetMatcher = PathMatchers.getMatcher("glob:**/target/**");
        PathMatcher testMatcher = PathMatchers.getMatcher("glob:**/test/**");

        System.out.printf("Walking path: %s%n", buildRoot);
        Set<FileVisitOption> options = Collections.emptySet();
        Files.walkFileTree(buildRoot, options, 30, finder);

        System.out.printf("Found: %d hits%n", finder.getHits().size());
        int count = 0;
        for (Path path : finder.getHits())
        {
            if (Files.isDirectory(path))
            {
                // skip
                continue;
            }

            if (targetMatcher.matches(path))
            {
                // skip
                continue;
            }

            if (testMatcher.matches(path))
            {
                // skip
                continue;
            }

            if (processModFile(path))
            {
                count++;
            }
        }

        System.out.printf("Processed %,d modules", count);
    }

    private boolean processFileRefs(List<String> lines)
    {
        Pattern section = Pattern.compile("\\s*\\[([^]]*)\\]\\s*");
        int filesStart = -1;
        int filesEnd = -1;

        // Find [files] section
        String sectionId = null;
        int lineCount = lines.size();
        for (int i = 0; i < lineCount; i++)
        {
            String line = lines.get(i).trim();

            Matcher sectionMatcher = section.matcher(line);

            if (sectionMatcher.matches())
            {
                sectionId = sectionMatcher.group(1).trim().toUpperCase(Locale.ENGLISH);
            }
            else
            {
                if ("FILES".equals(sectionId))
                {
                    if (filesStart < 0)
                    {
                        filesStart = i;
                    }
                    filesEnd = i;
                }
            }
        }

        if (filesStart == (-1))
        {
            // no [files] section
            return false;
        }

        // process lines, only in files section
        int updated = 0;
        for (int i = filesStart; i <= filesEnd; i++)
        {
            String line = lines.get(i);
            String keyword = "maven.org/maven2/";
            int idx = line.indexOf(keyword);
            if (idx > 0)
            {
                int pipe = line.indexOf('|');
                String rawpath = line.substring(idx + keyword.length(), pipe);
                String destpath = line.substring(pipe + 1);

                String[] parts = rawpath.split("/");
                int rev = parts.length;
                String filename = parts[--rev];

                String type = "jar";
                int ext = filename.lastIndexOf('.');
                if (ext > 0)
                {
                    type = filename.substring(ext + 1);
                }
                String version = parts[--rev];
                String artifactId = parts[--rev];
                String groupId = Utils.join(parts, 0, rev, ".");

                String classifier = filename.replaceFirst(artifactId + '-' + version, "");
                classifier = classifier.replaceFirst('.' + type + '$', "");
                if (Utils.isNotBlank(classifier) && (classifier.charAt(0) == '-'))
                {
                    classifier = classifier.substring(1);
                }

                StringBuilder murl = new StringBuilder();
                murl.append("maven://");
                murl.append(groupId).append('/');
                murl.append(artifactId).append('/');
                murl.append(version);
                if (!"jar".equals(type) || Utils.isNotBlank(classifier))
                {
                    murl.append('/').append(type);
                    if (Utils.isNotBlank(classifier))
                    {
                        murl.append('/').append(classifier);
                    }
                }

                lines.set(i, murl.toString() + '|' + destpath);

                updated++;
            }
        }

        return (updated > 0);
    }

    private boolean processModFile(Path path) throws IOException
    {
        List<String> lines = readLines(path);
        if (processFileRefs(lines))
        {
            // the lines are now dirty, save them.
            System.out.printf("Updating: %s%n", path);
            saveLines(path, lines);
            return true;
        }

        // no update performed
        return false;
    }

    private List<String> readLines(Path path) throws IOException
    {
        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }
        }

        return lines;
    }

    private void saveLines(Path path, List<String> lines) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING))
        {
            for (String line : lines)
            {
                writer.write(line);
                writer.write(System.lineSeparator());
            }
        }
    }
}
