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

package org.eclipse.jetty.util;

import java.net.URI;

// TODO: replace with UriPatternPredicate or ResourceUriPatternPredicate to avoid Resource -> URI -> Resource flows.
public abstract class PatternMatcher
{
    public abstract void matched(URI uri) throws Exception;

    public void match(String pattern, URI[] uris, boolean isNullInclusive)
        throws Exception
    {
        UriPatternPredicate predicate = new UriPatternPredicate(pattern, isNullInclusive);
        for (URI uri : uris)
        {
            if (predicate.test(uri))
                matched(uri);
        }
    }
}
