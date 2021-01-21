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

package org.eclipse.jetty.maven.plugin;

import java.util.Collections;
import java.util.List;

/**
 * ScanPattern
 *
 * Ant-style pattern of includes and excludes.
 */
public class ScanPattern
{
    private List<String> _includes = Collections.emptyList();
    private List<String> _excludes = Collections.emptyList();

    public void setIncludes(List<String> includes)
    {
        _includes = includes;
    }

    public void setExcludes(List<String> excludes)
    {
        _excludes = excludes;
    }

    public List<String> getIncludes()
    {
        return _includes;
    }

    public List<String> getExcludes()
    {
        return _excludes;
    }
}
