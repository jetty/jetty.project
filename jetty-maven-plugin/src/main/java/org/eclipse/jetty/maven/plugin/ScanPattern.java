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
