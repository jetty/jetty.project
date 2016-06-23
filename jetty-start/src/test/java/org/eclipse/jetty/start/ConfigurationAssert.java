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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
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
     * @throws FileNotFoundException if unable to find the configuration
     * @throws IOException if unable to process the configuration
     */
    public static void assertConfiguration(BaseHome baseHome, StartArgs args, String filename) throws FileNotFoundException, IOException
    {
        assertConfiguration(baseHome,args,MavenTestingUtils.getTestResourceFile(filename));
    }
    
    /**
     * Given a provided StartArgs, assert that the configuration it has determined is valid based on values in a assert text file.
     * 
     * @param baseHome
     *            the BaseHome used. Access it via {@link Main#getBaseHome()}
     * @param args
     *            the StartArgs that has been processed via {@link Main#processCommandLine(String[])}
     * @param file
     *            the file of the assertion values
     * @throws FileNotFoundException if unable to find the configuration
     * @throws IOException if unable to process the configuration
     */
    public static void assertConfiguration(BaseHome baseHome, StartArgs args, File file) throws FileNotFoundException, IOException
    {
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
                "user.dir".equals(name) || prop.origin.equals(Props.ORIGIN_SYSPROP) ||
                name.startsWith("java."))
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
                
        textFile.stream()
        .filter(s->s.startsWith("EXISTS|")).map(f->f.substring(7)).forEach(f->
        {
            Path path=baseHome.getBasePath().resolve(f);
            assertTrue(baseHome.toShortForm(path)+" exists?",Files.exists(path));
            assertEquals(baseHome.toShortForm(path)+" isDir?",f.endsWith("/"),Files.isDirectory(path)); 
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
            Assert.assertEquals(msg,expectedSet.size(),actualSet.size());        
            if (!expectedSet.isEmpty())
                Assert.assertThat(msg,actualSet,Matchers.containsInAnyOrder(expectedSet.toArray()));
        }
        catch(AssertionError e)
        {
            System.err.println("Expected: "+expectedSet);
            System.err.println("Actual  : "+actualSet);
            throw e;
        }
        
    }

    public static void assertOrdered(String msg, List<String> expectedList, List<String> actualList)
    {
        try
        {
            Assert.assertEquals(msg,expectedList.size(),actualList.size());        
            if (!expectedList.isEmpty())
                Assert.assertThat(msg,actualList,Matchers.contains(expectedList.toArray()));
        }
        catch(AssertionError e)
        {
            System.err.println("Expected: "+expectedList);
            System.err.println("Actual  : "+actualList);
            throw e;
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
