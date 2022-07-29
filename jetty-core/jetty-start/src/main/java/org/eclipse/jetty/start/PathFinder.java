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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PathFinder extends SimpleFileVisitor<Path>
{
    // internal tracking of prior notified paths (to avoid repeated notification of same ignored path)
    private static Set<Path> NOTIFIED_PATHS = new HashSet<>();

    private boolean includeDirsInResults = false;
    private Map<String, Path> hits = new HashMap<>();
    private Path basePath = null;
    private PathMatcher dirMatcher = PathMatchers.getNonHidden();
    private PathMatcher fileMatcher = PathMatchers.getNonHidden();

    private void addHit(Path path)
    {
        String relPath = basePath.relativize(path).toString();
        StartLog.debug("Found [" + relPath + "]  " + path);
        hits.put(relPath, path);
    }

    public PathMatcher getDirMatcher()
    {
        return dirMatcher;
    }

    public PathMatcher getFileMatcher()
    {
        return fileMatcher;
    }

    public List<Path> getHitList()
    {
        return new ArrayList<>(hits.values());
    }

    public Collection<Path> getHits()
    {
        return hits.values();
    }

    public boolean isIncludeDirsInResults()
    {
        return includeDirsInResults;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
    {
        if (dirMatcher.matches(dir))
        {
            StartLog.trace("Following dir: " + dir);
            if (includeDirsInResults && fileMatcher.matches(dir))
            {
                addHit(dir);
            }
            return FileVisitResult.CONTINUE;
        }
        else
        {
            StartLog.trace("Skipping dir: " + dir);
            return FileVisitResult.SKIP_SUBTREE;
        }
    }

    /**
     * Set the active basePath, used for resolving relative paths.
     * <p>
     * When a hit arrives for a subsequent find that has the same relative path as a prior hit, the new hit overrides the prior path as the active hit.
     *
     * @param basePath the basePath to tag all hits with
     */
    public void setBase(Path basePath)
    {
        this.basePath = basePath;
    }

    public void setDirMatcher(PathMatcher dirMatcher)
    {
        this.dirMatcher = dirMatcher;
    }

    public void setFileMatcher(PathMatcher fileMatcher)
    {
        this.fileMatcher = fileMatcher;
    }

    public void setFileMatcher(String pattern)
    {
        this.fileMatcher = PathMatchers.getMatcher(pattern);
    }

    public void setIncludeDirsInResults(boolean includeDirsInResults)
    {
        this.includeDirsInResults = includeDirsInResults;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
    {
        if (fileMatcher.matches(file))
        {
            addHit(file);
        }
        else
        {
            StartLog.trace("Ignoring file: " + file);
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
    {
        if (exc instanceof FileSystemLoopException)
        {
            if (!NOTIFIED_PATHS.contains(file))
            {
                StartLog.warn("skipping detected filesystem loop: " + file);
                NOTIFIED_PATHS.add(file);
            }
            return FileVisitResult.SKIP_SUBTREE;
        }
        else
        {
            StartLog.warn(exc);
            return super.visitFileFailed(file, exc);
        }
    }
}
