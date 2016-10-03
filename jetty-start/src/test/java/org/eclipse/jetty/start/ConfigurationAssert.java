//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;
import org.hamcrest.Matchers;
import org.junit.Assert;

public class ConfigurationAssert
{
    /**
     * Given a provided StartArgs, assert that the configuration it has determined is valid based on values in a assert text file.
     *
     * @param baseHome the BaseHome used. Access it via {@link Main#getBaseHome()}
     * @param args the StartArgs that has been processed via {@link Main#processCommandLine(String[])}
     * @param filename the filename of the assertion values
     * @throws FileNotFoundException if unable to find the configuration
     * @throws IOException if unable to process the configuration
     */
    public static void assertConfiguration(BaseHome baseHome, StartArgs args, String filename) throws FileNotFoundException, IOException
    {
        assertConfiguration(baseHome, args, null, MavenTestingUtils.getTestResourceFile(filename));
    }
    
    /**
     * Given a provided StartArgs, assert that the configuration it has determined is valid based on values in a assert text file.
     *
     * @param baseHome the BaseHome used. Access it via {@link Main#getBaseHome()}
     * @param args the StartArgs that has been processed via {@link Main#processCommandLine(String[])}
     * @param output the captured output that you want to assert against
     * @param filename the filename of the assertion values
     * @throws FileNotFoundException if unable to find the configuration
     * @throws IOException if unable to process the configuration
     */
    public static void assertConfiguration(BaseHome baseHome, StartArgs args, String output, String filename) throws FileNotFoundException, IOException
    {
        assertConfiguration(baseHome, args, output, MavenTestingUtils.getTestResourceFile(filename));
    }
    
    /**
     * Given a provided StartArgs, assert that the configuration it has determined is valid based on values in a assert text file.
     *
     * @param baseHome the BaseHome used. Access it via {@link Main#getBaseHome()}
     * @param args the StartArgs that has been processed via {@link Main#processCommandLine(String[])}
     * @param file the file of the assertion values
     * @throws FileNotFoundException if unable to find the configuration
     * @throws IOException if unable to process the configuration
     */
    public static void assertConfiguration(BaseHome baseHome, StartArgs args, String output, File file) throws FileNotFoundException, IOException
    {
        if(output != null)
        {
            System.err.println(output);
        }
        Path testResourcesDir = MavenTestingUtils.getTestResourcesDir().toPath().toRealPath();
        TextFile textFile = new TextFile(file.toPath());
        
        // Validate XMLs (order is important)
        List<String> expectedXmls = new ArrayList<>();
        for (String line : textFile)
        {
            if (line.startsWith("XML|"))
            {
                expectedXmls.add(FS.separators(getValue(line)));
            }
        }
        List<String> actualXmls = new ArrayList<>();
        for (Path xml : args.getXmlFiles())
        {
            actualXmls.add(shorten(baseHome, xml, testResourcesDir));
        }
        assertOrdered("XML Resolution Order", expectedXmls, actualXmls);
        
        // Validate LIBs (order is not important)
        List<String> expectedLibs = new ArrayList<>();
        for (String line : textFile)
        {
            if (line.startsWith("LIB|"))
            {
                expectedLibs.add(FS.separators(getValue(line)));
            }
        }
        List<String> actualLibs = new ArrayList<>();
        for (File path : args.getClasspath())
        {
            actualLibs.add(shorten(baseHome, path.toPath(), testResourcesDir));
        }
        assertContainsUnordered("Libs", expectedLibs, actualLibs);
        
        // Validate PROPERTIES (order is not important)
        Set<String> expectedProperties = new HashSet<>();
        for (String line : textFile)
        {
            if (line.startsWith("PROP|"))
            {
                expectedProperties.add(getValue(line));
            }
        }
        List<String> actualProperties = new ArrayList<>();
        for (Prop prop : args.getProperties())
        {
            String name = prop.key;
            if ("jetty.home".equals(name) || "jetty.base".equals(name) ||
                    "user.dir".equals(name) || prop.origin.equals(Props.ORIGIN_SYSPROP) ||
                    name.startsWith("java."))
            {
                // strip these out from assertion, to make assertions easier.
                continue;
            }
            actualProperties.add(prop.key + "=" + args.getProperties().expand(prop.value));
        }
        assertContainsUnordered("Properties", expectedProperties, actualProperties);
        
        // Validate Downloads
        List<String> expectedDownloads = new ArrayList<>();
        for (String line : textFile)
        {
            if (line.startsWith("DOWNLOAD|"))
            {
                expectedDownloads.add(getValue(line));
            }
        }
        List<String> actualDownloads = new ArrayList<>();
        for (FileArg darg : args.getFiles())
        {
            if (darg.uri != null)
            {
                actualDownloads.add(String.format("%s|%s", darg.uri, darg.location));
            }
        }
        assertContainsUnordered("Downloads", expectedDownloads, actualDownloads);
        
        // File / Path Existence Checks
        streamOf(textFile, "EXISTS").forEach(f ->
        {
            Path path = baseHome.getPath(f);
            if (f.endsWith("/"))
            {
                PathAssert.assertDirExists("Required Directory", path);
            }
            else
            {
                PathAssert.assertFileExists("Required File", path);
            }
        });
        
        // Output Validation
        streamOf(textFile, "OUTPUT").forEach(regex ->
        {
            Pattern pat = Pattern.compile(regex);
            Matcher mat = pat.matcher(output);
            assertTrue("Output [\n" + output + "]\nContains Regex Match: " + pat.pattern(), mat.find());
        });
    }
    
    private static String shorten(BaseHome baseHome, Path path, Path testResourcesDir)
    {
        String value = baseHome.toShortForm(path);
        if (value.startsWith("${"))
        {
            return value;
        }
        
        if (path.startsWith(testResourcesDir))
        {
            int len = testResourcesDir.toString().length();
            value = "${maven-test-resources}" + value.substring(len);
        }
        return value;
    }
    
    public static void assertContainsUnordered(String msg, Collection<String> expectedSet, Collection<String> actualSet)
    {
        try
        {
            Assert.assertEquals(msg, expectedSet.size(), actualSet.size());
            if (!expectedSet.isEmpty())
                assertThat(msg, actualSet, Matchers.containsInAnyOrder(expectedSet.toArray()));
        }
        catch (AssertionError e)
        {
            System.err.println("Expected: " + expectedSet);
            System.err.println("Actual  : " + actualSet);
            throw e;
        }
        
    }
    
    public static void assertOrdered(String msg, List<String> expectedList, List<String> actualList)
    {
        try
        {
            Assert.assertEquals(msg, expectedList.size(), actualList.size());
            if (!expectedList.isEmpty())
                assertThat(msg, actualList, Matchers.contains(expectedList.toArray()));
        }
        catch (AssertionError e)
        {
            System.err.println("Expected: " + expectedList);
            System.err.println("Actual  : " + actualList);
            throw e;
        }
    }
    
    private static Stream<String> streamOf(TextFile textFile, String key)
    {
        return textFile.stream()
                .filter(s -> s.startsWith(key + "|")).map(f -> getValue(f));
    }
    
    private static String getValue(String arg)
    {
        int idx = arg.indexOf('|');
        assertThat("Expecting '|' sign in [" + arg + "]", idx, greaterThanOrEqualTo(0));
        String value = arg.substring(idx + 1).trim();
        assertThat("Expecting Value after '|' in [" + arg + "]", value.length(), greaterThan(0));
        return value;
    }
}
