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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A QUIC specific {@link ClientConnector.Configurator}.</p>
 * <p>Since QUIC is based on UDP, this class creates {@link DatagramChannel}s instead of
 * {@link SocketChannel}s, and {@link DatagramChannelEndPoint}s instead of
 * {@link SocketChannelEndPoint}s.</p>
 *
 * @see QuicConfiguration
 */
public class QuicClientConnectorConfigurator extends ClientConnector.Configurator
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicClientConnectorConfigurator.class);

    static final String PRIVATE_KEY_PEM_PATH_KEY = QuicClientConnectorConfigurator.class.getName() + ".privateKeyPemPath";
    static final String CERTIFICATE_CHAIN_PEM_PATH_KEY = QuicClientConnectorConfigurator.class.getName() + ".certificateChainPemPath";
    static final String TRUSTED_CERTIFICATES_PEM_PATH_KEY = QuicClientConnectorConfigurator.class.getName() + ".trustedCertificatesPemPath";

    private final QuicConfiguration configuration = new QuicConfiguration();
    private final UnaryOperator<Connection> configurator;
    private Path privateKeyPemPath;
    private Path certificateChainPemPath;
    private Path trustedCertificatesPemPath;

    public QuicClientConnectorConfigurator()
    {
        this(UnaryOperator.identity());
    }

    public QuicClientConnectorConfigurator(UnaryOperator<Connection> configurator)
    {
        this.configurator = Objects.requireNonNull(configurator);
        // Initialize to sane defaults for a client.
        configuration.setSessionRecvWindow(16 * 1024 * 1024);
        configuration.setBidirectionalStreamRecvWindow(8 * 1024 * 1024);
        configuration.setDisableActiveMigration(true);
    }

    public QuicConfiguration getQuicConfiguration()
    {
        return configuration;
    }

    @Override
    protected void doStart() throws Exception
    {
        Path pemWorkDirectory = configuration.getPemWorkDirectory();
        ClientConnector clientConnector = getBean(ClientConnector.class);
        SslContextFactory.Client sslContextFactory = clientConnector.getSslContextFactory();
        KeyStore trustStore = sslContextFactory.getTrustStore();
        if (trustStore != null)
        {
            trustedCertificatesPemPath = PemExporter.exportTrustStore(trustStore, pemWorkDirectory != null ? pemWorkDirectory : Path.of(System.getProperty("java.io.tmpdir")));
            configuration.getImplementationConfiguration().put(TRUSTED_CERTIFICATES_PEM_PATH_KEY, trustedCertificatesPemPath.toString());
        }
        String certAlias = sslContextFactory.getCertAlias();
        if (certAlias != null)
        {
            if (pemWorkDirectory == null)
                throw new IllegalStateException("No PEM work directory configured");
            KeyStore keyStore = sslContextFactory.getKeyStore();
            String keyManagerPassword = sslContextFactory.getKeyManagerPassword();
            char[] password = keyManagerPassword == null ? sslContextFactory.getKeyStorePassword().toCharArray() : keyManagerPassword.toCharArray();
            Path[] keyPair = PemExporter.exportKeyPair(keyStore, certAlias, password, pemWorkDirectory);
            privateKeyPemPath = keyPair[0];
            certificateChainPemPath = keyPair[1];
            configuration.getImplementationConfiguration().put(PRIVATE_KEY_PEM_PATH_KEY, privateKeyPemPath.toString());
            configuration.getImplementationConfiguration().put(CERTIFICATE_CHAIN_PEM_PATH_KEY, certificateChainPemPath.toString());
        }
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        deleteFile(privateKeyPemPath);
        privateKeyPemPath = null;
        configuration.getImplementationConfiguration().remove(PRIVATE_KEY_PEM_PATH_KEY);
        deleteFile(certificateChainPemPath);
        certificateChainPemPath = null;
        configuration.getImplementationConfiguration().remove(CERTIFICATE_CHAIN_PEM_PATH_KEY);
        deleteFile(trustedCertificatesPemPath);
        trustedCertificatesPemPath = null;
        configuration.getImplementationConfiguration().remove(TRUSTED_CERTIFICATES_PEM_PATH_KEY);
    }

    private void deleteFile(Path file)
    {
        try
        {
            if (file != null)
                Files.delete(file);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not delete {}", file, x);
        }
    }

    @Override
    public boolean isIntrinsicallySecure(ClientConnector clientConnector, SocketAddress address)
    {
        return true;
    }

    @Override
    public ChannelWithAddress newChannelWithAddress(ClientConnector clientConnector, SocketAddress address, Map<String, Object> context) throws IOException
    {
        context.put(QuicConfiguration.CONTEXT_KEY, configuration);

        DatagramChannel channel = DatagramChannel.open();
        if (clientConnector.getBindAddress() == null)
        {
            // QUIC must know the local address for connection migration, so we must always bind early.
            channel.bind(null);
        }
        return new ChannelWithAddress(channel, address);
    }

    @Override
    public EndPoint newEndPoint(ClientConnector clientConnector, SocketAddress address, SelectableChannel selectable, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new DatagramChannelEndPoint((DatagramChannel)selectable, selector, selectionKey, clientConnector.getScheduler());
    }

    @Override
    public Connection newConnection(ClientConnector clientConnector, SocketAddress address, EndPoint endPoint, Map<String, Object> context)
    {
        return configurator.apply(new ClientQuicConnection(clientConnector, endPoint, context));
    }
}
