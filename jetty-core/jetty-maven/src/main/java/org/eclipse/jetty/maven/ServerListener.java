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

package org.eclipse.jetty.maven;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * ServerListener
 *
 * Listener to create a file that signals that the startup is completed.
 * Used by the JettyRunHome maven goal to determine that the child
 * process is started, and that jetty is ready.
 */
public class ServerListener implements LifeCycle.Listener
{
    private String _tokenFile;

    public void setTokenFile(String file)
    {
        _tokenFile = file;
    }

    public String getTokenFile()
    {
        return _tokenFile;
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        if (_tokenFile != null)
        {
            try
            {
                // Using Path, as we need to reliably create/write a file.
                Path path = Path.of(_tokenFile);
                Files.createFile(path);
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        }
    }
}
