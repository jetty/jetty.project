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

package org.eclipse.jetty.util.resource.nfd;

import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link FileSystem} that forwards to a backing filesystem (the default, real one) but hands out
 * {@link NfdPath} instances so URIs can carry the macOS NFD form. See {@link NfdPath} for the why.
 *
 * <p>
 * This fixture exists to mimic the macOS/APFS behavior where a non-ASCII name that is NFD on disk is
 * requested in Form C, which the alias handling in {@code PathResource} has to tolerate. A plain Linux
 * filesystem never produces it, so without this fixture the behavior could only be tested on a Mac. It
 * lets those tests run on Linux and guards against regressions during day-to-day development on Linux.
 * </p>
 *
 * <p>
 * The JDK reports the real path two different ways depending on JDK-8289689, and the {@link Mode}
 * selects which one {@link NfdPath#toRealPath} reproduces. See {@link Mode} and {@link NfdPath}.
 * </p>
 *
 * <p>
 * Everything is delegated to the backing filesystem; only the {@link Path} representation differs.
 * {@link #close()} is a no-op so the shared default filesystem is never closed.
 * </p>
 */
public class NfdFileSystem extends FileSystem
{
    /**
     * Which macOS JDK behavior {@link NfdPath#toRealPath} reproduces for an NFD-on-disk name requested
     * in Form C.
     */
    public enum Mode
    {
        /**
         * JDK 19 and earlier (before JDK-8289689): the JDK decodes the native NFD name to an NFC
         * String, so {@code toRealPath().toString()} is Form C (the name still matches the request) but
         * {@code toRealPath().toUri()} percent-encodes the raw NFD bytes, so the real URI is Form D.
         */
        DECODE_TO_NFC,

        /**
         * JDK 20 and later (JDK-8289689): the JDK leaves the native name in NFD, so both
         * {@code toRealPath().toString()} and {@code toRealPath().toUri()} are Form D, and the name no
         * longer matches the Form C request.
         */
        RAW_NFD
    }

    private final NfdFileSystemProvider provider;
    private final FileSystem backing;
    private final Mode mode;

    public NfdFileSystem(NfdFileSystemProvider provider, FileSystem backing, Mode mode)
    {
        this.provider = provider;
        this.backing = backing;
        this.mode = mode;
    }

    Mode mode()
    {
        return mode;
    }

    NfdPath wrap(Path p)
    {
        return p == null ? null : new NfdPath(this, p, null);
    }

    @Override
    public NfdFileSystemProvider provider()
    {
        return provider;
    }

    @Override
    public void close()
    {
        // No-op: never close the shared backing (default) filesystem.
    }

    @Override
    public boolean isOpen()
    {
        return true;
    }

    @Override
    public boolean isReadOnly()
    {
        return backing.isReadOnly();
    }

    @Override
    public String getSeparator()
    {
        return backing.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories()
    {
        List<Path> roots = new ArrayList<>();
        for (Path root : backing.getRootDirectories())
            roots.add(wrap(root));
        return roots;
    }

    @Override
    public Iterable<FileStore> getFileStores()
    {
        return backing.getFileStores();
    }

    @Override
    public java.util.Set<String> supportedFileAttributeViews()
    {
        return backing.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more)
    {
        return wrap(backing.getPath(first, more));
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern)
    {
        return backing.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService()
    {
        return backing.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws java.io.IOException
    {
        return backing.newWatchService();
    }
}
