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
