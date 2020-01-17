//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.pathmap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PathSpecAssert
{
    public static void assertMatch(PathSpec spec, String path)
    {
        boolean match = spec.matches(path);
        assertThat(spec.getClass().getSimpleName() + " '" + spec + "' should match path '" + path + "'", match, is(true));
    }

    public static void assertNotMatch(PathSpec spec, String path)
    {
        boolean match = spec.matches(path);
        assertThat(spec.getClass().getSimpleName() + " '" + spec + "' should not match path '" + path + "'", match, is(false));
    }
}
