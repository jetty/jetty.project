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

package org.eclipse.jetty.osgi.boot.utils;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * ServerConnectorListener
 *
 * This is for test support, where we need jetty to run on a random port, and we need
 * a client to be able to find out which port was picked.
 */
public class ServerConnectorListener extends AbstractLifeCycleListener
{

    private Path _filePath;
    private String _sysPropertyName;

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener#lifeCycleStarted(org.eclipse.jetty.util.component.LifeCycle)
     */
    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        if (getFilePath() != null)
        {
            try (FileWriter writer = new FileWriter(getFilePath().toFile()))
            {
                Files.deleteIfExists(_filePath);
                writer.write(((ServerConnector)event).getLocalPort());
                writer.close();
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
        super.lifeCycleStarted(event);
    }

    /**
     * @return the filePath
     */
    public Path getFilePath()
    {
        return _filePath;
    }

    /**
     * @param filePath the filePath to set
     */
    public void setFilePath(Path filePath)
    {
        _filePath = filePath;
    }

    /**
     * @return the sysPropertyName
     */
    public String getSysPropertyName()
    {
        return _sysPropertyName;
    }

    /**
     * @param sysPropertyName the sysPropertyName to set
     */
    public void setSysPropertyName(String sysPropertyName)
    {
        _sysPropertyName = sysPropertyName;
    }
}
