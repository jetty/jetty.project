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

package org.eclipse.jetty.util.resource;

import java.net.URI;
import java.util.Objects;
import java.util.function.Predicate;

import org.eclipse.jetty.util.UriPatternPredicate;

/**
 * Specialized {@link UriPatternPredicate} to allow filtering {@link Resource} entries by their URI.
 */
public class ResourceUriPatternPredicate implements Predicate<Resource>
{
    private final Predicate<URI> uriPredicate;

    public ResourceUriPatternPredicate(String regex, boolean isNullInclusive)
    {
        this(new UriPatternPredicate(regex, isNullInclusive));
    }

    public ResourceUriPatternPredicate(UriPatternPredicate uriPredicate)
    {
        this.uriPredicate = Objects.requireNonNull(uriPredicate, "UriPatternPredicate cannot be null");
    }

    @Override
    public boolean test(Resource resource)
    {
        if (resource == null)
            return false;
        URI uri = resource.getURI();
        return uriPredicate.test(uri);
    }
}
