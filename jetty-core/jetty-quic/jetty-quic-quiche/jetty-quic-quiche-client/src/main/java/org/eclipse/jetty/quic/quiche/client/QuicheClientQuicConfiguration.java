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

package org.eclipse.jetty.quic.quiche.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.quic.quiche.PemPaths;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Quiche specific {@link ClientQuicConfiguration}.</p>
 * <p>The PEM working directory constructor argument is only necessary
 * when the client-side needs to send certificates to the server, or
 * when it needs a TrustStore, otherwise it may be {@code null}.</p>
 */
public class QuicheClientQuicConfiguration extends ClientQuicConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheClientQuicConfiguration.class);

    private Path pemWorkDirectory;
    private boolean disableActiveMigration;

    public QuicheClientQuicConfiguration()
    {
        this(null);
    }

    public QuicheClientQuicConfiguration(Path pemWorkDirectory)
    {
        this.pemWorkDirectory = pemWorkDirectory;
    }

    public Path getPemWorkDirectory()
    {
        return pemWorkDirectory;
    }

    public void setPemWorkDirectory(Path pemWorkDirectory)
    {
        if (isStarted())
            throw new IllegalStateException("cannot change PEM working directory after start");
        this.pemWorkDirectory = pemWorkDirectory;
    }

    public boolean isDisableActiveMigration()
    {
        return disableActiveMigration;
    }

    public void setDisableActiveMigration(boolean disableActiveMigration)
    {
        this.disableActiveMigration = disableActiveMigration;
    }

    public void configure(SslContextFactory.Client sslContextFactory) throws Exception
    {
        getImplementationConfiguration().computeIfAbsent(sslContextFactory, key ->
        {
            try
            {
                Path pemWorkDirectory = getPemWorkDirectory();

                Path privateKeyPemPath = null;
                Path certificateChainPemPath = null;
                Path trustedCertificatesPemPath = null;

                KeyStore trustStore = sslContextFactory.getTrustStore();
                if (trustStore != null)
                    trustedCertificatesPemPath = PemExporter.exportTrustStore(trustStore, pemWorkDirectory);

                String certAlias = sslContextFactory.getCertAlias();
                if (certAlias != null)
                {
                    KeyStore keyStore = sslContextFactory.getKeyStore();
                    String keyManagerPassword = sslContextFactory.getKeyManagerPassword();
                    char[] password = keyManagerPassword == null ? sslContextFactory.getKeyStorePassword().toCharArray() : keyManagerPassword.toCharArray();
                    Path[] keyPair = PemExporter.exportKeyPair(keyStore, certAlias, password, pemWorkDirectory);
                    privateKeyPemPath = keyPair[0];
                    certificateChainPemPath = keyPair[1];
                }

                return new PemPaths(privateKeyPemPath, certificateChainPemPath, trustedCertificatesPemPath);
            }
            catch (RuntimeException x)
            {
                throw x;
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        });
    }

    public void deconfigure(SslContextFactory.Client sslContextFactory)
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
