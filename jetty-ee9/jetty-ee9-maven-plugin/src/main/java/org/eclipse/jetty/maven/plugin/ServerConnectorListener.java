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

import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

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
    private String _fileName;
    private String _sysPropertyName;

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        if (getFileName() != null)
        {
            try
            {
                Path tmp = Files.createTempFile("jettyport", ".tmp");
                try (Writer writer = Files.newBufferedWriter(tmp))
                {
                    writer.write(String.valueOf(((ServerConnector)event).getLocalPort()));
                }

                Path path = Paths.get(getFileName());
                Files.deleteIfExists(path);
                try
                {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
                }
                catch (AtomicMoveNotSupportedException e) // can append on some os (windows).. so try again without the option
                {
                    Files.move(tmp, path);
                }
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
     * @return the file name
     */
    public String getFileName()
    {
        return _fileName;
    }

    /**
     * @param name the file name to set
     */
    public void setFileName(String name)
    {

        _fileName = name;
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
