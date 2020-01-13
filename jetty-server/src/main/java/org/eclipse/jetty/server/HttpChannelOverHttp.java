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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpComplianceSection;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An HttpChannel customized to be transported over the HTTP/1 protocol
 */
public class HttpChannelOverHttp extends HttpChannel implements HttpParser.RequestHandler, HttpParser.ComplianceHandler
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverHttp.class);
    private static final HttpField PREAMBLE_UPGRADE_H2C = new HttpField(HttpHeader.UPGRADE, "h2c");
    private final HttpFields _fields = new HttpFields();
    private final MetaData.Request _metadata = new MetaData.Request(_fields);
    private final HttpConnection _httpConnection;
    private HttpField _connection;
    private HttpField _upgrade = null;
    private boolean _delayedForContent;
    private boolean _unknownExpectation = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private List<String> _complianceViolations;
    private HttpFields _trailers;

    public HttpChannelOverHttp(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport)
    {
        super(connector, config, endPoint, transport);
        _httpConnection = httpConnection;
        _metadata.setURI(new HttpURI());
    }

    @Override
    protected HttpInput newHttpInput(HttpChannelState state)
    {
        return new HttpInputOverHTTP(state);
    }

    @Override
    public void recycle()
    {
        super.recycle();
        _unknownExpectation = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _metadata.recycle();
        _connection = null;
        _fields.clear();
        _upgrade = null;
        _trailers = null;
    }

    @Override
    public boolean isExpecting100Continue()
    {
        return _expect100Continue;
    }

    @Override
    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    @Override
    public boolean startRequest(String method, String uri, HttpVersion version)
    {
        _metadata.setMethod(method);
        _metadata.getURI().parseRequestTarget(method, uri);
        _metadata.setHttpVersion(version);
        _unknownExpectation = false;
        _expect100Continue = false;
        _expect102Processing = false;
        return false;
    }

    @Override
    public void parsedHeader(HttpField field)
    {
        HttpHeader header = field.getHeader();
        String value = field.getValue();
        if (header != null)
        {
            switch (header)
            {
                case CONNECTION:
                    _connection = field;
                    break;

                case HOST:
                    if (!_metadata.getURI().isAbsolute() && field instanceof HostPortHttpField)
                    {
                        HostPortHttpField hp = (HostPortHttpField)field;
                        _metadata.getURI().setAuthority(hp.getHost(), hp.getPort());
                    }
                    break;

                case EXPECT:
                {
                    if (_metadata.getHttpVersion() == HttpVersion.HTTP_1_1)
                    {
                        HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                        switch (expect == null ? HttpHeaderValue.UNKNOWN : expect)
                        {
                            case CONTINUE:
                                _expect100Continue = true;
                                break;

                            case PROCESSING:
                                _expect102Processing = true;
                                break;

                            default:
                                String[] values = field.getValues();
                                for (int i = 0; values != null && i < values.length; i++)
                                {
                                    expect = HttpHeaderValue.CACHE.get(values[i].trim());
                                    if (expect == null)
                                        _unknownExpectation = true;
                                    else
                                    {
                                        switch (expect)
                                        {
                                            case CONTINUE:
                                                _expect100Continue = true;
                                                break;
                                            case PROCESSING:
                                                _expect102Processing = true;
                                                break;
                                            default:
                                                _unknownExpectation = true;
                                        }
                                    }
                                }
                        }
                    }
                    break;
                }

                case UPGRADE:
                    _upgrade = field;
                    break;

                default:
                    break;
            }
        }
        _fields.add(field);
    }

    @Override
    public void parsedTrailer(HttpField field)
    {
        if (_trailers == null)
            _trailers = new HttpFields();
        _trailers.add(field);
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @throws IOException if the InputStream cannot be created
     */
    @Override
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        // TODO: consider using an AtomicBoolean ?
        if (isExpecting100Continue())
        {
            _expect100Continue = false;

            // is content missing?
            if (available == 0)
            {
                if (getResponse().isCommitted())
                    throw new IOException("Committed before 100 Continues");

                boolean committed = sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue");
            }
        }
    }

    @Override
    public void earlyEOF()
    {
        _httpConnection.getGenerator().setPersistent(false);
        // If we have no request yet, just close
        if (_metadata.getMethod() == null)
            _httpConnection.close();
        else if (onEarlyEOF() || _delayedForContent)
        {
            _delayedForContent = false;
            handle();
        }
    }

    @Override
    public boolean content(ByteBuffer content)
    {
        HttpInput.Content c = _httpConnection.newContent(content);
        boolean handle = onContent(c) || _delayedForContent;
        _delayedForContent = false;
        return handle;
    }

    @Override
    public void onAsyncWaitForContent()
    {
        _httpConnection.asyncReadFillInterested();
    }

    @Override
    public void onBlockWaitForContent()
    {
        _httpConnection.blockingReadFillInterested();
    }

    @Override
    public void onBlockWaitForContentFailure(Throwable failure)
    {
        _httpConnection.blockingReadFailure(failure);
    }

    @Override
    public void badMessage(BadMessageException failure)
    {
        _httpConnection.getGenerator().setPersistent(false);
        try
        {
            // Need to call onRequest, so RequestLog can reports as much as possible
            onRequest(_metadata);
            getRequest().getHttpInput().earlyEOF();
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }

        onBadMessage(failure);
    }

    @Override
    public boolean headerComplete()
    {
        if (_complianceViolations != null && !_complianceViolations.isEmpty())
        {
            this.getRequest().setAttribute(HttpCompliance.VIOLATIONS_ATTR, _complianceViolations);
            _complianceViolations = null;
        }

        boolean persistent;

        switch (_metadata.getHttpVersion())
        {
            case HTTP_0_9:
            {
                persistent = false;
                break;
            }
            case HTTP_1_0:
            {
                if (getHttpConfiguration().isPersistentConnectionsEnabled())
                {
                    if (_connection != null)
                    {
                        if (_connection.contains(HttpHeaderValue.KEEP_ALIVE.asString()))
                            persistent = true;
                        else
                            persistent = _fields.contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                    }
                    else
                        persistent = false;
                }
                else
                    persistent = false;

                if (!persistent)
                    persistent = HttpMethod.CONNECT.is(_metadata.getMethod());
                if (persistent)
                    getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);

                break;
            }

            case HTTP_1_1:
            {
                if (_unknownExpectation)
                {
                    badMessage(new BadMessageException(HttpStatus.EXPECTATION_FAILED_417));
                    return false;
                }

                if (getHttpConfiguration().isPersistentConnectionsEnabled())
                {
                    if (_connection != null)
                    {
                        if (_connection.contains(HttpHeaderValue.CLOSE.asString()))
                            persistent = false;
                        else
                            persistent = !_fields.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()); // handle multiple connection fields
                    }
                    else
                        persistent = true;
                }
                else
                    persistent = false;

                if (!persistent)
                    persistent = HttpMethod.CONNECT.is(_metadata.getMethod());
                if (!persistent)
                    getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

                if (_upgrade != null && upgrade())
                    return true;

                break;
            }

            case HTTP_2:
            {
                // Allow direct "upgrade" to HTTP_2_0 only if the connector supports h2c.
                _upgrade = PREAMBLE_UPGRADE_H2C;

                if (HttpMethod.PRI.is(_metadata.getMethod()) &&
                    "*".equals(_metadata.getURI().toString()) &&
                    _fields.size() == 0 &&
                    upgrade())
                    return true;

                badMessage(new BadMessageException(HttpStatus.UPGRADE_REQUIRED_426));
                _httpConnection.getParser().close();
                return false;
            }

            default:
            {
                throw new IllegalStateException("unsupported version " + _metadata.getHttpVersion());
            }
        }

        if (!persistent)
            _httpConnection.getGenerator().setPersistent(false);

        onRequest(_metadata);

        // Should we delay dispatch until we have some content?
        // We should not delay if there is no content expect or client is expecting 100 or the response is already committed or the request buffer already has something in it to parse
        _delayedForContent = (getHttpConfiguration().isDelayDispatchUntilContent() &&
            (_httpConnection.getParser().getContentLength() > 0 || _httpConnection.getParser().isChunking()) &&
            !isExpecting100Continue() &&
            !isCommitted() &&
            _httpConnection.isRequestBufferEmpty());

        return !_delayedForContent;
    }

    boolean onIdleTimeout(Throwable timeout)
    {
        if (_delayedForContent)
        {
            _delayedForContent = false;
            getRequest().getHttpInput().onIdleTimeout(timeout);
            execute(this);
            return false;
        }
        return true;
    }

    /**
     * <p>Attempts to perform an HTTP/1.1 upgrade.</p>
     * <p>The upgrade looks up a {@link ConnectionFactory.Upgrading} from the connector
     * matching the protocol specified in the {@code Upgrade} header.</p>
     * <p>The upgrade may succeed, be ignored (which can allow a later handler to implement)
     * or fail with a {@link BadMessageException}.</p>
     *
     * @return true if the upgrade was performed, false if it was ignored
     * @throws BadMessageException if the upgrade failed
     */
    private boolean upgrade() throws BadMessageException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("upgrade {} {}", this, _upgrade);

        @SuppressWarnings("ReferenceEquality")
        boolean isUpgradedH2C = (_upgrade == PREAMBLE_UPGRADE_H2C);

        if (!isUpgradedH2C && (_connection == null || !_connection.contains("upgrade")))
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400);

        // Find the upgrade factory
        ConnectionFactory.Upgrading factory = null;
        for (ConnectionFactory f : getConnector().getConnectionFactories())
        {
            if (f instanceof ConnectionFactory.Upgrading)
            {
                if (f.getProtocols().contains(_upgrade.getValue()))
                {
                    factory = (ConnectionFactory.Upgrading)f;
                    break;
                }
            }
        }

        if (factory == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No factory for {} in {}", _upgrade, getConnector());
            return false;
        }

        // Create new connection
        HttpFields response101 = new HttpFields();
        Connection upgradeConnection = factory.upgradeConnection(getConnector(), getEndPoint(), _metadata, response101);
        if (upgradeConnection == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Upgrade ignored for {} by {}", _upgrade, factory);
            return false;
        }

        // Send 101 if needed
        try
        {
            if (!isUpgradedH2C)
                sendResponse(new MetaData.Response(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS_101, response101, 0), null, true);
        }
        catch (IOException e)
        {
            throw new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, e);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Upgrade from {} to {}", getEndPoint().getConnection(), upgradeConnection);
        getRequest().setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE, upgradeConnection);
        getResponse().setStatus(101);
        getHttpTransport().onCompleted();
        return true;
    }

    @Override
    protected void handleException(Throwable x)
    {
        _httpConnection.getGenerator().setPersistent(false);
        super.handleException(x);
    }

    @Override
    public void abort(Throwable failure)
    {
        super.abort(failure);
        _httpConnection.getGenerator().setPersistent(false);
    }

    @Override
    public boolean contentComplete()
    {
        boolean handle = onContentComplete() || _delayedForContent;
        _delayedForContent = false;
        return handle;
    }

    @Override
    public boolean messageComplete()
    {
        if (_trailers != null)
            onTrailers(_trailers);
        return onRequestComplete();
    }

    @Override
    public int getHeaderCacheSize()
    {
        return getHttpConfiguration().getHeaderCacheSize();
    }

    @Override
    public void onComplianceViolation(HttpCompliance compliance, HttpComplianceSection violation, String reason)
    {
        if (_httpConnection.isRecordHttpComplianceViolations())
        {
            if (_complianceViolations == null)
            {
                _complianceViolations = new ArrayList<>();
            }
            String record = String.format("%s (see %s) in mode %s for %s in %s",
                violation.getDescription(), violation.getURL(), compliance, reason, getHttpTransport());
            _complianceViolations.add(record);
            if (LOG.isDebugEnabled())
                LOG.debug(record);
        }
    }
}
