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

package org.eclipse.jetty.osgi.util;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * This class is for test support, where we need Jetty to run on a random port,
 * and we need a client to be able to find out which port was picked.
 */
public class ServerConnectorListener implements LifeCycle.Listener
{
    private Path _filePath;
    private String _sysPropertyName;

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        if (getFilePath() != null)
        {
            try (FileWriter writer = new FileWriter(getFilePath().toFile()))
            {
                Files.deleteIfExists(_filePath);
                writer.write(((ServerConnector)event).getLocalPort());
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        if (getSysPropertyName() != null)
        {
            System.setProperty(_sysPropertyName, String.valueOf(((ServerConnector)event).getLocalPort()));
        }
    }

    /**
     * Get the filePath.
     * @return the filePath
     */
    public Path getFilePath()
    {
        return _filePath;
    }

    /**
     * Set the filePath to set.
     * @param filePath the filePath to set
     */
    public void setFilePath(Path filePath)
    {
        _filePath = filePath;
    }

    /**
     * Get the sysPropertyName.
     * @return the sysPropertyName
     */
    public String getSysPropertyName()
    {
        return _sysPropertyName;
    }

    /**
     * Set the sysPropertyName to set.
     * @param sysPropertyName the sysPropertyName to set
     */
    public void setSysPropertyName(String sysPropertyName)
    {
        _sysPropertyName = sysPropertyName;
    }
}
