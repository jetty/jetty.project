//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler.AliasCheck;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

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
 */
public class SameFileAliasChecker implements AliasCheck
{
    private static final Logger LOG = Log.getLogger(SameFileAliasChecker.class);

    @Override
    public boolean check(String uri, Resource resource)
    {
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
            LOG.ignore(e);
        }
        return false;
    }
}
