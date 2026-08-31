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

package org.eclipse.jetty.quic.quiche.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Set;

import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.quic.quiche.PemPaths;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Quiche specific {@link ServerQuicConfiguration}.</p>
 * <p>The Quiche native library cannot read a Java {@link KeyStore} directly.
 * Before the server starts, the private key and certificate chain (and the
 * trust store, if present) are exported as PEM files into a working directory.
 * That directory is mandatory: pass it to the constructor, or call
 * {@link #setPemWorkDirectory(Path)} before starting this instance.</p>
 */
public class QuicheServerQuicConfiguration extends ServerQuicConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheServerQuicConfiguration.class);

    private Path pemWorkDirectory;

    public QuicheServerQuicConfiguration()
    {
        this(null);
    }

    public QuicheServerQuicConfiguration(Path pemWorkDirectory)
    {
        this.pemWorkDirectory = pemWorkDirectory;
    }

    /**
     * @return the directory where KeyStore material is exported as PEM files for Quiche
     * @see #setPemWorkDirectory(Path)
     */
    public Path getPemWorkDirectory()
    {
        return pemWorkDirectory;
    }

    /**
     * <p>Sets the directory used to export KeyStore material as PEM files for Quiche.</p>
     * <p>Those files include the private key, so the directory must already exist
     * and should be writable only by the process owner. Do not use a world-writable
     * location such as {@code /tmp}.</p>
     * <p>The directory cannot be changed after this instance has started.</p>
     *
     * @param pemWorkDirectory the directory for exported PEM files
     */
    public void setPemWorkDirectory(Path pemWorkDirectory)
    {
        if (isStarted())
            throw new IllegalStateException("cannot change PEM working directory after start");
        this.pemWorkDirectory = pemWorkDirectory;
    }

    public void configure(SslContextFactory.Server sslContextFactory) throws Exception
    {
        Path pemWorkDirectory = getPemWorkDirectory();
        Set<String> aliases = sslContextFactory.getAliases();
        if (aliases.isEmpty())
            throw new IllegalStateException("Missing or invalid KeyStore: a SslContextFactory configured with a valid, non-empty KeyStore is required");
        String alias = sslContextFactory.getCertAlias();
        if (alias == null)
            alias = aliases.stream().findFirst().orElseThrow();
        String keyManagerPassword = sslContextFactory.getKeyManagerPassword();
        char[] password = keyManagerPassword == null ? sslContextFactory.getKeyStorePassword().toCharArray() : keyManagerPassword.toCharArray();
        KeyStore keyStore = sslContextFactory.getKeyStore();
        Path[] keyPair = PemExporter.exportKeyPair(keyStore, alias, password, pemWorkDirectory);
        Path privateKeyPemPath = keyPair[0];
        Path certificateChainPemPath = keyPair[1];
        Path trustedCertificatesPemPath = null;
        KeyStore trustStore = sslContextFactory.getTrustStore();
        if (trustStore != null)
            trustedCertificatesPemPath = PemExporter.exportTrustStore(trustStore, pemWorkDirectory);
        getImplementationConfiguration().put(sslContextFactory, new PemPaths(privateKeyPemPath, certificateChainPemPath, trustedCertificatesPemPath));
    }

    public void deconfigure(SslContextFactory.Server sslContextFactory)
    {
        PemPaths pemPaths = (PemPaths)getImplementationConfiguration().remove(sslContextFactory);
        if (pemPaths != null)
        {
            deleteFile(pemPaths.privateKeyPemPath());
            deleteFile(pemPaths.certificateChainPemPath());
            deleteFile(pemPaths.trustedCertificatesPemPath());
        }
    }

    private void deleteFile(Path path)
    {
        try
        {
            if (path != null)
                Files.delete(path);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not delete {}", path, x);
        }
    }
}
