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


package org.eclipse.jetty.spdy.server.http;

import java.util.Collections;
import java.util.Set;

import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.util.Fields;

/**
 * <p>{@link PushStrategy} encapsulates the decisions about performing
 * SPDY pushes of secondary resources associated with a primary resource.</p>
 */
public interface PushStrategy
{
    /**
     * <p>Applies the SPDY push logic for the primary resource.</p>
     *
     * @param stream the primary resource stream
     * @param requestHeaders the primary resource request headers
     * @param responseHeaders the primary resource response headers
     * @return a list of secondary resource URIs to push
     */
    public Set<String> apply(Stream stream, Fields requestHeaders, Fields responseHeaders);

    /**
     * An implementation that returns an empty list of secondary resources
     */
    public static class None implements PushStrategy
    {
        @Override
        public Set<String> apply(Stream stream, Fields requestHeaders, Fields responseHeaders)
        {
            return Collections.emptySet();
        }
    }
}
