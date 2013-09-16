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


package org.eclipse.jetty.spdy.http;

import java.util.Collections;
import java.util.Set;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.Stream;

/**
 *
 */
public interface PushStrategy
{
    public Set<String> apply(Stream stream, Headers requestHeaders, Headers responseHeaders);

    public static class None implements PushStrategy
    {
        @Override
        public Set<String> apply(Stream stream, Headers requestHeaders, Headers responseHeaders)
        {
            return Collections.emptySet();
        }
    }
}
