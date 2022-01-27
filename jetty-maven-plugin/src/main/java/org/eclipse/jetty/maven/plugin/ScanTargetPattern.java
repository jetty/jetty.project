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

import java.io.File;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.util.IncludeExcludeSet;

/**
 * ScanTargetPattern
 *
 * Utility class to provide the ability for the mvn jetty:run
 * mojo to be able to specify filesets of extra files to
 * regularly scan for changes in order to redeploy the webapp.
 *
 * For example:
 *
 * &lt;scanTargetPattern&gt;
 * &lt;directory&gt;/some/place&lt;/directory&gt;
 * &lt;includes&gt;
 * &lt;include&gt;some ant pattern here &lt;/include&gt;
 * &lt;include&gt;some ant pattern here &lt;/include&gt;
 * &lt;/includes&gt;
 * &lt;excludes&gt;
 * &lt;exclude&gt;some ant pattern here &lt;/exclude&gt;
 * &lt;exclude&gt;some ant pattern here &lt;/exclude&gt;
 * &lt;/excludes&gt;
 * &lt;/scanTargetPattern&gt;
 */
public class ScanTargetPattern
{
    private File _directory;
    private ScanPattern _pattern;

    /**
     * @return the _directory
     */
    public File getDirectory()
    {
        return _directory;
    }

    /**
     * @param directory the directory to set
     */
    public void setDirectory(File directory)
    {
        this._directory = directory;
    }

    public void setIncludes(List<String> includes)
    {
        if (_pattern == null)
            _pattern = new ScanPattern();
        _pattern.setIncludes(includes);
    }

    public void setExcludes(List<String> excludes)
    {
        if (_pattern == null)
            _pattern = new ScanPattern();
        _pattern.setExcludes(excludes);
    }

    public List<String> getIncludes()
    {
        return (_pattern == null ? Collections.emptyList() : _pattern.getIncludes());
    }

    public List<String> getExcludes()
    {
        return (_pattern == null ? Collections.emptyList() : _pattern.getExcludes());
    }

    public void configureIncludesExcludeSet(IncludeExcludeSet<PathMatcher, Path> includesExcludes)
    {
        for (String include:getIncludes())
        {
            if (!include.startsWith("glob:"))
                include = "glob:" + include;
            includesExcludes.include(_directory.toPath().getFileSystem().getPathMatcher(include));
        }

        for (String exclude:getExcludes())
        {
            if (!exclude.startsWith("glob:"))
                exclude = "glob:" + exclude;
            includesExcludes.exclude(_directory.toPath().getFileSystem().getPathMatcher(exclude));
        }
    }
}
