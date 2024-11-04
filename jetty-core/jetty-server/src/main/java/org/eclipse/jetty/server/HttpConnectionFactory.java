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

package org.eclipse.jetty.server;

import java.util.Objects;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.annotation.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Connection Factory for HTTP Connections.
 * <p>Accepts connections either directly or via SSL and/or ALPN chained connection factories.  The accepted
 * {@link HttpConnection}s are configured by a {@link HttpConfiguration} instance that is either created by
 * default or passed in to the constructor.
 */
public class HttpConnectionFactory extends AbstractConnectionFactory implements HttpConfiguration.ConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectionFactory.class);
    private final HttpConfiguration _config;
    private boolean _useInputDirectByteBuffers;
    private boolean _useOutputDirectByteBuffers;

    public HttpConnectionFactory()
    {
        this(new HttpConfiguration());
    }

    public HttpConnectionFactory(@Name("config") HttpConfiguration config)
    {
        super(HttpVersion.HTTP_1_1.asString());
        _config = Objects.requireNonNull(config);
        installBean(_config);
        setUseInputDirectByteBuffers(_config.isUseInputDirectByteBuffers());
        setUseOutputDirectByteBuffers(_config.isUseOutputDirectByteBuffers());
        setInputBufferSize(_config.getInputBufferSize());
    }

    @Override
    public void setInputBufferSize(int size)
    {
        super.setInputBufferSize(size);
        _config.setInputBufferSize(size);
    }

    @Override
    public int getInputBufferSize()
    {
        return _config.getInputBufferSize();
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return _config;
    }

    /**
     * @deprecated use {@link HttpConfiguration#getComplianceViolationListeners()} instead to know if there
     * are any {@link ComplianceViolation.Listener} to notify.  this method will be removed in Jetty 12.1.0
     */
    @Deprecated(since = "12.0.6", forRemoval = true)
    public boolean isRecordHttpComplianceViolations()
    {
        return !_config.getComplianceViolationListeners().isEmpty();
    }

    /**
     * Does nothing.
     * @deprecated use {@link HttpConfiguration#addComplianceViolationListener(ComplianceViolation.Listener)} instead.
     * this method will be removed in Jetty 12.1.0
     */
    @Deprecated(since = "12.0.6", forRemoval = true)
    public void setRecordHttpComplianceViolations(boolean recordHttpComplianceViolations)
    {
        _config.addComplianceViolationListener(new ComplianceViolation.LoggingListener());
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return _useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        _useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return _useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        HttpConnection connection = new HttpConnection(_config, connector, endPoint);
        connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
        return configure(connection, connector, endPoint);
    }
}
