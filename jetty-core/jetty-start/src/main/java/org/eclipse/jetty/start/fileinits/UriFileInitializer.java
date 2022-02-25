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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FileInitializer;

public class UriFileInitializer extends FileInitializer
{
    public UriFileInitializer(BaseHome baseHome)
    {
        super(baseHome, "http", "https");
    }

    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        Path destination = getDestination(uri, location);

        if (Files.isDirectory(destination))
            destination = destination.resolve(uri.getSchemeSpecificPart().substring(uri.getRawSchemeSpecificPart().lastIndexOf('/') + 1));

        if (isFilePresent(destination))
            return false;

        download(uri, destination);

        return true;
    }
}
