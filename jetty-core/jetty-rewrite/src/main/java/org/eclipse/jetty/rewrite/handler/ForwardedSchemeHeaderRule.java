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

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.http.HttpURI;

/**
 * <p>Sets the request URI scheme, by default {@code https}.</p>
 */
public class ForwardedSchemeHeaderRule extends HeaderRule
{
    private String _scheme = "https";

    public String getScheme()
    {
        return _scheme;
    }

    public void setScheme(String scheme)
    {
        _scheme = scheme;
    }

    @Override
    protected Processor apply(Processor input, String value)
    {
        HttpURI newURI = HttpURI.build(input.getHttpURI()).scheme(getScheme());
        return new HttpURIProcessor(input, newURI);
    }
}
