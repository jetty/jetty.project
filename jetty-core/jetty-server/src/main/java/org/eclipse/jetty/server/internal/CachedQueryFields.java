//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.internal;

import java.nio.charset.Charset;

import org.eclipse.jetty.util.Fields;

public class CachedQueryFields extends Fields
{
    public static final String KEY = "_oejsi_CQF";
    private final String _query;
    private final Charset _charset;

    public CachedQueryFields(String query, Charset charset)
    {
        super(true);
        _query = query;
        _charset = charset;
    }

    public String getQuery()
    {
        return _query;
    }

    public Charset getCharset()
    {
        return _charset;
    }
}
