//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        File file = MavenTestingUtils.getTestResourceFile(filename);
        TextFile textFile = new TextFile(file);

        // Validate XMLs (order is important)
        List<String> expectedXmls = new ArrayList<>();
        for (String line : textFile)
        {
            if (line.startsWith("XML|"))
            {
                expectedXmls.add(getValue(line));
            }
        }
        List<String> actualXmls = new ArrayList<>();
        for (File xml : args.getXmlFiles())
        {
            actualXmls.add(baseHome.toShortForm(xml));
        }
        assertThat("XML Resolution Order " + actualXmls,actualXmls,contains(expectedXmls.toArray()));

        // Validate LIBs (order is not important)
        Set<String> expectedLibs = new HashSet<>();
        for (String line : textFile)
        {
            if (line.startsWith("LIB|"))
            {
                expectedLibs.add(getValue(line));
            }
        }
        Set<String> actualLibs = new HashSet<>();
        for (File path : args.getClasspath())
        {
            actualLibs.add(baseHome.toShortForm(path));
        }
        assertThat("Libs " + actualLibs,actualLibs,containsInAnyOrder(expectedLibs.toArray()));

        // Validate PROPERTIES (order is not important)
        Set<String> expectedProperties = new HashSet<>();
        for (String line : textFile)
        {
            if (line.startsWith("PROP|"))
            {
                expectedProperties.add(getValue(line));
            }
        }
        Set<String> actualProperties = new HashSet<>();
        @SuppressWarnings("unchecked")
        Enumeration<String> nameEnum = (Enumeration<String>)args.getProperties().propertyNames();
        while (nameEnum.hasMoreElements())
        {
            String name = nameEnum.nextElement();
            String value = args.getProperties().getProperty(name);
            actualProperties.add(name + "=" + value);
        }
        assertThat("Properties " + actualProperties,actualProperties,containsInAnyOrder(expectedProperties.toArray()));
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
