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

package org.eclipse.jetty.quic.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Set;

import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Server-side {@link QuicConfiguration} with server-specific settings.</p>
 * <p>The PEM working directory constructor argument is mandatory, although
 * it may be set after construction via {@link #setPemWorkDirectory(Path)}
 * before starting this instance.</p>
 */
public class ServerQuicConfiguration extends QuicConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConfiguration.class);

    private final SslContextFactory.Server sslContextFactory;

    public ServerQuicConfiguration(SslContextFactory.Server sslContextFactory, Path pemWorkDirectory)
    {
        this.sslContextFactory = sslContextFactory;
        setPemWorkDirectory(pemWorkDirectory);
        setSessionRecvWindow(4 * 1024 * 1024);
        setBidirectionalStreamRecvWindow(2 * 1024 * 1024);
        // One bidirectional stream to simulate the TCP stream, and no unidirectional streams.
        setMaxBidirectionalRemoteStreams(1);
        setMaxUnidirectionalRemoteStreams(0);
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        addBean(sslContextFactory);

        super.doStart();

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
        getImplementationConfiguration().put(PRIVATE_KEY_PEM_PATH_KEY, privateKeyPemPath);
        Path certificateChainPemPath = keyPair[1];
        getImplementationConfiguration().put(CERTIFICATE_CHAIN_PEM_PATH_KEY, certificateChainPemPath);
        KeyStore trustStore = sslContextFactory.getTrustStore();
        if (trustStore != null)
        {
            Path trustedCertificatesPemPath = PemExporter.exportTrustStore(trustStore, pemWorkDirectory);
            getImplementationConfiguration().put(TRUSTED_CERTIFICATES_PEM_PATH_KEY, trustedCertificatesPemPath);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        Path trustedCertificatesPemPath = (Path)getImplementationConfiguration().remove(TRUSTED_CERTIFICATES_PEM_PATH_KEY);
        deleteFile(trustedCertificatesPemPath);
        Path certificateChainPemPath = (Path)getImplementationConfiguration().remove(CERTIFICATE_CHAIN_PEM_PATH_KEY);
        deleteFile(certificateChainPemPath);
        Path privateKeyPemPath = (Path)getImplementationConfiguration().remove(PRIVATE_KEY_PEM_PATH_KEY);
        deleteFile(privateKeyPemPath);
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
