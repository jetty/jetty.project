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

package org.eclipse.jetty.util.ssl;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SslKeyStoreScanner extends AbstractLifeCycle implements Scanner.DiscreteListener
{
    private static final Logger LOG = Log.getLogger(SslKeyStoreScanner.class);

    private final SslContextFactory sslContextFactory;
    private final File keystoreFile;
    private final List<File> files = new ArrayList<>();
    private Scanner _scanner;
    private int _scanInterval = 1;

    public SslKeyStoreScanner(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        this.keystoreFile = new File(URI.create(sslContextFactory.getKeyStorePath())); // getKeyStorePath is giving url instead of path?
        if (!keystoreFile.exists())
            throw new IllegalArgumentException("keystore file does not exist");
        if (keystoreFile.isDirectory())
            throw new IllegalArgumentException("expected keystore file not directory");

        File parentFile = keystoreFile.getParentFile();
        if (!parentFile.exists() || !parentFile.isDirectory())
            throw new IllegalArgumentException("error obtaining keystore dir");

        files.add(parentFile);
        if (LOG.isDebugEnabled())
            LOG.debug("created {}", this);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug(this.getClass().getSimpleName() + ".doStart()");

        _scanner = new Scanner();
        _scanner.setScanDirs(files);
        _scanner.setScanInterval(_scanInterval);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.setScanDepth(1);
        _scanner.addListener(this);
        _scanner.start();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (_scanner != null)
        {
            _scanner.stop();
            _scanner.removeListener(this);
            _scanner = null;
        }
    }

    @Override
    public void fileAdded(String filename) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("added {}", filename);

        if (keystoreFile.toPath().toString().equals(filename))
            reload();
    }

    @Override
    public void fileChanged(String filename) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("changed {}", filename);

        if (keystoreFile.toPath().toString().equals(filename))
            reload();
    }

    @Override
    public void fileRemoved(String filename) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("removed {}", filename);

        // TODO: do we want to do this?
        if (keystoreFile.toPath().toString().equals(filename))
            reload();
    }

    @ManagedOperation(value = "Reload the SSL Keystore", impact = "ACTION")
    public void reload() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("reloading keystore file {}", keystoreFile);

        try
        {
            sslContextFactory.reload(scf -> {});
        }
        catch (Throwable t)
        {
            LOG.warn("Keystore Reload Failed", t);
        }
    }

    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return _scanInterval;
    }

    public void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
    }
}
