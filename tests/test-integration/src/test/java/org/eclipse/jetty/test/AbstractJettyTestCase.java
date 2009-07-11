// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test;

import java.io.File;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;

public abstract class AbstractJettyTestCase extends TestCase
{
    public static final boolean IS_ON_WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private File baseDir;

    public File getBaseDir()
    {
        if (baseDir == null)
        {
            String baseDirPath = System.getProperty("basedir");
            if (baseDirPath == null)
            {
                baseDirPath = System.getProperty("user.dir",".");
            }
            baseDir = new File(baseDirPath);
        }

        return baseDir;
    }
    
    public File getTargetDir()
    {
        File path = new File(getBaseDir(),"target");
        assertDirExists("target dir",path);
        return path;
    }

    public File getTestResourcesDir()
    {
        File path = new File(getBaseDir(),"src/test/resources");
        assertDirExists("test resources dir",path);
        return path;
    }

    public File getDocRootBase()
    {
        File path = new File(getTestResourcesDir(),"docroots");
        assertDirExists("docroot base dir",path);
        return path;
    }

    public void assertDirExists(String msg, File path)
    {
        assertNotNull(msg + " should not be null",path);
        assertTrue(msg + " should exist",path.exists());
        assertTrue(msg + " should be a directory",path.isDirectory());
    }

    /**
     * Return the port that the server is listening on.
     * 
     * Assumes 1 connector, and that server is started already.
     * 
     * @param server
     *            the server port.
     * @return the port that the server is listening on.
     */
    public int findServerPort(Server server)
    {
        Connector connectors[] = server.getConnectors();
        for (int i = 0; i < connectors.length; i++)
        {
            Connector connector = connectors[i];
            if (connector.getLocalPort() > 0)
            {
                return connector.getLocalPort();
            }
        }

        throw new AssertionFailedError("No valid connector port found.");
    }
}
