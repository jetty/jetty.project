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

package org.eclipse.jetty.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler.AliasCheck;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alias checking for working with FileSystems that normalize access to the
 * File System.
 * <p>
 * The Java {@link Files#isSameFile(Path, Path)} method is used to determine
 * if the requested file is the same as the alias file.
 * </p>
 * <p>
 * For File Systems that are case insensitive (eg: Microsoft Windows FAT32 and NTFS),
 * the access to the file can be in any combination or style of upper and lowercase.
 * </p>
 * <p>
 * For File Systems that normalize UTF-8 access (eg: Mac OSX on HFS+ or APFS,
 * or Linux on XFS) the the actual file could be stored using UTF-16,
 * but be accessed using NFD UTF-8 or NFC UTF-8 for the same file.
 * </p>
 * @deprecated use {@link org.eclipse.jetty.server.AllowedResourceAliasChecker} instead.
 */
@Deprecated
public class SameFileAliasChecker implements AliasCheck
{
    private static final Logger LOG = LoggerFactory.getLogger(SameFileAliasChecker.class);

    public SameFileAliasChecker()
    {
        LOG.warn("SameFileAliasChecker is deprecated");
    }

    @Override
    public boolean check(String pathInContext, Resource resource)
    {
        // Do not allow any file separation characters in the URI.
        if (File.separatorChar != '/' && pathInContext.indexOf(File.separatorChar) >= 0)
            return false;

        // Only support PathResource alias checking
        if (!(resource instanceof PathResource))
            return false;

        try
        {
            PathResource pathResource = (PathResource)resource;
            Path path = pathResource.getPath();
            Path alias = pathResource.getAliasPath();

            if (Files.isSameFile(path, alias))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Allow alias to same file {} --> {}", path, alias);
                return true;
            }
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
        }
        return false;
    }
}
