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

package org.eclipse.jetty.servlet;

import java.util.Objects;

import org.eclipse.jetty.http.pathmap.PathSpec;

/**
 * An Abstrace PathSpec Mapping with a source
 */
public abstract class Mapping extends PathSpec.Mapping
{
    private final Source _source;

    public Mapping(Source source)
    {
        Objects.requireNonNull(source);
        _source = source;
    }

    public Source getSource()
    {
        return _source;
    }
}
