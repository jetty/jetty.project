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
