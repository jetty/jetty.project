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

package org.eclipse.jetty.ee11.servlet;

import jakarta.servlet.Filter;
import org.eclipse.jetty.ee.servlet.Source;

public class FilterHolder extends org.eclipse.jetty.ee.servlet.FilterHolder
{
    public FilterHolder()
    {
        super();
    }

    public FilterHolder(Source source)
    {
        super(source);
    }

    public FilterHolder(Class<? extends Filter> filter)
    {
        super(filter);
    }

    public FilterHolder(Filter filter)
    {
        super(filter);
    }
}
