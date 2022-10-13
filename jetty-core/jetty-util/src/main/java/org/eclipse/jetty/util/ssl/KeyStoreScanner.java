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

package org.eclipse.jetty.util.ssl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The {@link KeyStoreScanner} is used to monitor the KeyStore file used by the {@link SslContextFactory}.
 * It will reload the {@link SslContextFactory} if it detects that the KeyStore file has been modified.</p>
 * <p>If the TrustStore file needs to be changed, then this should be done before touching the KeyStore file,
 * the {@link SslContextFactory#reload(Consumer)} will only occur after the KeyStore file has been modified.</p>
 */
public class KeyStoreScanner extends ContainerLifeCycle implements Scanner.DiscreteListener
{
    private static final Logger LOG = LoggerFactory.getLogger(KeyStoreScanner.class);

    private final SslContextFactory sslContextFactory;
    private final Path keystoreFile;
    private final Scanner _scanner;

    public KeyStoreScanner(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        Resource keystoreResource = sslContextFactory.getKeyStoreResource();
        if (!keystoreResource.exists())
            throw new IllegalArgumentException("keystore file does not exist");
        if (keystoreResource.isDirectory())
            throw new IllegalArgumentException("expected keystore file not directory");

        // Use real location of keystore (if different), so that change monitoring can work properly
        Path monitoredFile = keystoreResource.getPath();
        if (keystoreResource.isAlias())
            monitoredFile = Paths.get(keystoreResource.getTargetURI());

        keystoreFile = monitoredFile;
        if (LOG.isDebugEnabled())
            LOG.debug("Monitored Keystore File: {}", monitoredFile);

        Path parentFile = keystoreFile.getParent();
        if (!Files.exists(parentFile) || !Files.isDirectory(parentFile))
            throw new IllegalArgumentException("error obtaining keystore dir");

        _scanner = new Scanner();
        _scanner.addDirectory(parentFile);
        _scanner.setScanInterval(1);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.setScanDepth(1);
        _scanner.addListener(this);
        addBean(_scanner);
    }

    @Override
    public void fileAdded(String filename)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("added {}", filename);

        if (keystoreFile.toString().equals(filename))
            reload();
    }

    @Override
    public void fileChanged(String filename)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("changed {}", filename);

        if (keystoreFile.toString().equals(filename))
            reload();
    }

    @Override
    public void fileRemoved(String filename)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("removed {}", filename);

        if (keystoreFile.toString().equals(filename))
            reload();
    }

    @ManagedOperation(value = "Scan for changes in the SSL Keystore", impact = "ACTION")
    public boolean scan(long waitMillis)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("scanning");

        CompletableFuture<Boolean> cf = new CompletableFuture<>();
        try
        {
            // Perform 2 scans to be sure that the scan is stable.
            _scanner.scan(Callback.from(() ->
                _scanner.scan(Callback.from(() -> cf.complete(true), cf::completeExceptionally)), cf::completeExceptionally));
            return cf.get(waitMillis, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @ManagedOperation(value = "Reload the SSL Keystore", impact = "ACTION")
    public void reload()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("reloading keystore file {}", keystoreFile);

        try
        {
            sslContextFactory.reload(scf ->
            {
            });
        }
        catch (Throwable t)
        {
            LOG.warn("Keystore Reload Failed", t);
        }
    }

    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return _scanner.getScanInterval();
    }

    public void setScanInterval(int scanInterval)
    {
        _scanner.setScanInterval(scanInterval);
    }
}
