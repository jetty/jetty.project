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

package org.eclipse.jetty.quic.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Client-side {@link QuicConfiguration} with client-specific settings.</p>
 * <p>The PEM working directory constructor argument is only necessary
 * when the client-side needs to send certificates to the server, or
 * when it needs a TrustStore, otherwise it may be null.</p>
 */
public class ClientQuicConfiguration extends QuicConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicConfiguration.class);

    private final SslContextFactory.Client sslContextFactory;

    public ClientQuicConfiguration(SslContextFactory.Client sslContextFactory, Path pemWorkDirectory)
    {
        this.sslContextFactory = sslContextFactory;
        setPemWorkDirectory(pemWorkDirectory);
        setSessionRecvWindow(16 * 1024 * 1024);
        setBidirectionalStreamRecvWindow(8 * 1024 * 1024);
    }

    public SslContextFactory.Client getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        addBean(sslContextFactory);

        super.doStart();

        Path pemWorkDirectory = getPemWorkDirectory();
        KeyStore trustStore = sslContextFactory.getTrustStore();
        if (trustStore != null)
        {
            Path trustedCertificatesPemPath = PemExporter.exportTrustStore(trustStore, pemWorkDirectory);
            getImplementationConfiguration().put(TRUSTED_CERTIFICATES_PEM_PATH_KEY, trustedCertificatesPemPath);
        }

        String certAlias = sslContextFactory.getCertAlias();
        if (certAlias != null)
        {
            KeyStore keyStore = sslContextFactory.getKeyStore();
            String keyManagerPassword = sslContextFactory.getKeyManagerPassword();
            char[] password = keyManagerPassword == null ? sslContextFactory.getKeyStorePassword().toCharArray() : keyManagerPassword.toCharArray();
            Path[] keyPair = PemExporter.exportKeyPair(keyStore, certAlias, password, pemWorkDirectory);
            Path privateKeyPemPath = keyPair[0];
            getImplementationConfiguration().put(PRIVATE_KEY_PEM_PATH_KEY, privateKeyPemPath);
            Path certificateChainPemPath = keyPair[1];
            getImplementationConfiguration().put(CERTIFICATE_CHAIN_PEM_PATH_KEY, certificateChainPemPath);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        Path certificateChainPemPath = (Path)getImplementationConfiguration().remove(CERTIFICATE_CHAIN_PEM_PATH_KEY);
        deleteFile(certificateChainPemPath);
        Path privateKeyPemPath = (Path)getImplementationConfiguration().remove(PRIVATE_KEY_PEM_PATH_KEY);
        deleteFile(privateKeyPemPath);
        Path trustedCertificatesPemPath = (Path)getImplementationConfiguration().remove(TRUSTED_CERTIFICATES_PEM_PATH_KEY);
        deleteFile(trustedCertificatesPemPath);
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
