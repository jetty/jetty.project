//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assert;

public class ConfigurationAssert
{
    /**
     * Given a provided StartArgs, assert that the configuration it has determined is valid based on values in a assert text file.
     * 
     * @param baseHome
     *            the BaseHome used. Access it via {@link Main#getBaseHome()}
     * @param args
     *            the StartArgs that has been processed via {@link Main#processCommandLine(String[])}
     * @param filename
     *            the filename of the assertion values
     * @throws IOException
     */
    public static void assertConfiguration(BaseHome baseHome, StartArgs args, String filename) throws FileNotFoundException, IOException
    {
        File testResourcesDir = MavenTestingUtils.getTestResourcesDir();
        File file = MavenTestingUtils.getTestResourceFile(filename);
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
            actualXmls.add(shorten(baseHome,xml,testResourcesDir));
        }
        assertOrdered("XML Resolution Order",expectedXmls,actualXmls);

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
            actualLibs.add(shorten(baseHome,path.toPath(),testResourcesDir));
        }
        assertContainsUnordered("Libs",expectedLibs,actualLibs);

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
                "user.dir".equals(name) || prop.origin.equals(Props.ORIGIN_SYSPROP))
            {
                // strip these out from assertion, to make assertions easier.
                continue;
            }
            actualProperties.add(prop.key + "=" + args.getProperties().expand(prop.value));
        }
        assertContainsUnordered("Properties",expectedProperties,actualProperties);

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
                actualDownloads.add(String.format("%s|%s",darg.uri,darg.location));
            }
        }
        assertContainsUnordered("Downloads",expectedDownloads,actualDownloads);
        
        // Validate Files/Dirs creation
        List<String> expectedFiles = new ArrayList<>();
        for(String line: textFile)
        {
            if(line.startsWith("FILE|"))
            {
                expectedFiles.add(getValue(line));
            }
        }
        List<String> actualFiles = new ArrayList<>();
        for(FileArg farg: args.getFiles())
        {
            if(farg.uri == null)
            {
                actualFiles.add(farg.location);
            }
        }
        assertContainsUnordered("Files/Dirs",expectedFiles,actualFiles);
    }

    private static String shorten(BaseHome baseHome, Path path, File testResourcesDir)
    {
        String value = baseHome.toShortForm(path);
        if (value.startsWith(testResourcesDir.getAbsolutePath()))
        {
            int len = testResourcesDir.getAbsolutePath().length();
            value = "${maven-test-resources}" + value.substring(len);
        }
        return value;
    }

    private static void assertContainsUnordered(String msg, Collection<String> expectedSet, Collection<String> actualSet)
    {
        // same size?
        boolean mismatch = expectedSet.size() != actualSet.size();

        // test content
        Set<String> missing = new HashSet<>();
        for (String expected : expectedSet)
        {
            if (!actualSet.contains(expected))
            {
                missing.add(expected);
            }
        }

        if (mismatch || missing.size() > 0)
        {
            // build up detailed error message
            StringWriter message = new StringWriter();
            PrintWriter err = new PrintWriter(message);

            err.printf("%s: Assert Contains (Unordered)",msg);
            if (mismatch)
            {
                err.print(" [size mismatch]");
            }
            if (missing.size() >= 0)
            {
                err.printf(" [%d entries missing]",missing.size());
            }
            err.println();
            err.printf("Actual Entries (size: %d)%n",actualSet.size());
            for (String actual : actualSet)
            {
                char indicator = expectedSet.contains(actual)?' ':'>';
                err.printf("%s| %s%n",indicator,actual);
            }
            err.printf("Expected Entries (size: %d)%n",expectedSet.size());
            for (String expected : expectedSet)
            {
                char indicator = actualSet.contains(expected)?' ':'>';
                err.printf("%s| %s%n",indicator,expected);
            }
            err.flush();
            Assert.fail(message.toString());
        }
    }

    public static void assertOrdered(String msg, List<String> expectedList, List<String> actualList)
    {
        // same size?
        boolean mismatch = expectedList.size() != actualList.size();

        // test content
        List<Integer> badEntries = new ArrayList<>();
        int min = Math.min(expectedList.size(),actualList.size());
        int max = Math.max(expectedList.size(),actualList.size());
        for (int i = 0; i < min; i++)
        {
            if (!expectedList.get(i).equals(actualList.get(i)))
            {
                badEntries.add(i);
            }
        }
        for (int i = min; i < max; i++)
        {
            badEntries.add(i);
        }

        if (mismatch || badEntries.size() > 0)
        {
            // build up detailed error message
            StringWriter message = new StringWriter();
            PrintWriter err = new PrintWriter(message);

            err.printf("%s: Assert Contains (Unordered)",msg);
            if (mismatch)
            {
                err.print(" [size mismatch]");
            }
            if (badEntries.size() >= 0)
            {
                err.printf(" [%d entries not matched]",badEntries.size());
            }
            err.println();
            err.printf("Actual Entries (size: %d)%n",actualList.size());
            for (int i = 0; i < actualList.size(); i++)
            {
                String actual = actualList.get(i);
                char indicator = badEntries.contains(i)?'>':' ';
                err.printf("%s[%d] %s%n",indicator,i,actual);
            }

            err.printf("Expected Entries (size: %d)%n",expectedList.size());
            for (int i = 0; i < expectedList.size(); i++)
            {
                String expected = expectedList.get(i);
                char indicator = badEntries.contains(i)?'>':' ';
                err.printf("%s[%d] %s%n",indicator,i,expected);
            }
            err.flush();
            Assert.fail(message.toString());
        }
    }

    private static String getValue(String arg)
    {
        int idx = arg.indexOf('|');
        Assert.assertThat("Expecting '|' sign in [" + arg + "]",idx,greaterThanOrEqualTo(0));
        String value = arg.substring(idx + 1).trim();
        Assert.assertThat("Expecting Value after '|' in [" + arg + "]",value.length(),greaterThan(0));
        return value;
    }
}
