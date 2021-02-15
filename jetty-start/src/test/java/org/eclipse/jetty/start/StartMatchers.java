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

package org.eclipse.jetty.start;

import java.nio.file.Files;
import java.nio.file.Path;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

public final class StartMatchers
{
    public static Matcher<Path> pathExists()
    {
        return new BaseMatcher<Path>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Path path = (Path)item;
                return Files.exists(path);
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("Path should exist");
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("Path did not exist ").appendValue(item);
            }
        };
    }

    public static Matcher<Path> notPathExists()
    {
        return new BaseMatcher<Path>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Path path = (Path)item;
                return !Files.exists(path);
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("Path should not exist");
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("Path exists ").appendValue(item);
            }
        };
    }

    public static Matcher<Path> fileExists()
    {
        return new BaseMatcher<Path>()
        {
            @Override
            public boolean matches(Object item)
            {
                final Path path = (Path)item;
                return Files.exists(path) && Files.isRegularFile(path);
            }

            @Override
            public void describeTo(Description description)
            {
                description.appendText("File should exist");
            }

            @Override
            public void describeMismatch(Object item, Description description)
            {
                description.appendText("File did not exist ").appendValue(item);
            }
        };
    }
}
