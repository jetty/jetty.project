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

package org.eclipse.jetty.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * @deprecated use {@link org.eclipse.jetty.toolchain.test.ExtraMatchers#ordered(List)} instead
 */
@Deprecated
public class CollectionAssert
{
    public static void assertContainsUnordered(String msg, Collection<String> expectedSet, Collection<String> actualSet)
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

            err.printf("%s: Assert Contains (Unordered)", msg);
            if (mismatch)
            {
                err.print(" [size mismatch]");
            }
            if (missing.size() >= 0)
            {
                err.printf(" [%d entries missing]", missing.size());
            }
            err.println();
            err.printf("Actual Entries (size: %d)%n", actualSet.size());
            for (String actual : actualSet)
            {
                char indicator = expectedSet.contains(actual) ? ' ' : '>';
                err.printf("%s| %s%n", indicator, actual);
            }
            err.printf("Expected Entries (size: %d)%n", expectedSet.size());
            for (String expected : expectedSet)
            {
                char indicator = actualSet.contains(expected) ? ' ' : '>';
                err.printf("%s| %s%n", indicator, expected);
            }
            err.flush();
            fail(message.toString());
        }
    }

    public static void assertOrdered(String msg, List<String> expectedList, List<String> actualList)
    {
        // same size?
        boolean mismatch = expectedList.size() != actualList.size();

        // test content
        List<Integer> badEntries = new ArrayList<>();
        int min = Math.min(expectedList.size(), actualList.size());
        int max = Math.max(expectedList.size(), actualList.size());
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

            err.printf("%s: Assert Contains (Unordered)", msg);
            if (mismatch)
            {
                err.print(" [size mismatch]");
            }
            if (badEntries.size() >= 0)
            {
                err.printf(" [%d entries not matched]", badEntries.size());
            }
            err.println();
            err.printf("Actual Entries (size: %d)%n", actualList.size());
            for (int i = 0; i < actualList.size(); i++)
            {
                String actual = actualList.get(i);
                char indicator = badEntries.contains(i) ? '>' : ' ';
                err.printf("%s[%d] %s%n", indicator, i, actual);
            }

            err.printf("Expected Entries (size: %d)%n", expectedList.size());
            for (int i = 0; i < expectedList.size(); i++)
            {
                String expected = expectedList.get(i);
                char indicator = badEntries.contains(i) ? '>' : ' ';
                err.printf("%s[%d] %s%n", indicator, i, expected);
            }
            err.flush();
            fail(message.toString());
        }
    }
}
