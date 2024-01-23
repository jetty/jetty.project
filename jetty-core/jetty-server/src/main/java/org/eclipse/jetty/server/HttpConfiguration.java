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

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Configuration.
 * <p>This class is a holder of HTTP configuration.  Typically an HTTPConfiguration
 * instance is instantiated and passed to a {@link ConnectionFactory} that can
 * create HTTP channels (e.g. HTTP, AJP or FCGI).</p>
 * <p>The configuration held by this class is not for the wire protocol,
 * but for the interpretation and handling of HTTP requests that could
 * be transported by a variety of protocols.
 * </p>
 */
@ManagedObject("HTTP Configuration")
public class HttpConfiguration implements Dumpable
{
    public static final String SERVER_VERSION = "Jetty(" + Jetty.VERSION + ")";
    private final List<Customizer> _customizers = new CopyOnWriteArrayList<>();
    private final Index.Mutable<Boolean> _formEncodedMethods = new Index.Builder<Boolean>()
        .caseSensitive(false)
        .mutable()
        .build();
    private final List<ComplianceViolation.Listener> _complianceViolationListeners = new CopyOnWriteArrayList<>();
    private ComplianceViolation.Listener _combinedComplianceViolationListener;
    private int _outputBufferSize = 32 * 1024;
    private int _outputAggregationSize = _outputBufferSize / 4;
    private int _requestHeaderSize = 8 * 1024;
    private int _responseHeaderSize = 8 * 1024;
    private int _headerCacheSize = 1024;
    private boolean _headerCacheCaseSensitive = false;
    private int _securePort;
    private long _idleTimeout = -1;
    private String _secureScheme = HttpScheme.HTTPS.asString();
    private boolean _sendServerVersion = true;
    private boolean _sendXPoweredBy = false;
    private boolean _sendDateHeader = true;
    private boolean _delayDispatchUntilContent = true;
    private boolean _persistentConnectionsEnabled = true;
    private int _maxErrorDispatches = 10;
    private boolean _useInputDirectByteBuffers = true;
    private boolean _useOutputDirectByteBuffers = true;
    private long _minRequestDataRate;
    private long _minResponseDataRate;
    private HttpCompliance _httpCompliance = HttpCompliance.RFC7230;
    private UriCompliance _uriCompliance = UriCompliance.DEFAULT;
    private CookieCompliance _requestCookieCompliance = CookieCompliance.RFC6265;
    private CookieCompliance _responseCookieCompliance = CookieCompliance.RFC6265;
    private MultiPartCompliance _multiPartCompliance = MultiPartCompliance.RFC7578;
    private boolean _notifyRemoteAsyncErrors = true;
    private boolean _relativeRedirectAllowed = true;
    private HostPort _serverAuthority;
    private SocketAddress _localAddress;
    private int _maxUnconsumedRequestContentReads = 16;

    /**
     * <p>An interface that allows a request object to be customized
     * for a particular HTTP connector configuration.  Unlike Filters, customizer are
     * applied before the request is submitted for processing and can be specific to the
     * connector on which the request was received.</p>
     *
     * <p>Typically Customizers perform tasks such as:</p>
     * <ul>
     * <li>process header fields that may be injected by a proxy or load balancer.
     * <li>setup attributes that may come from the connection/connector such as SSL Session IDs
     * <li>Allow a request to be marked as secure or authenticated if those have been offloaded
     * and communicated by header, cookie or other out-of-band mechanism
     * <li>Set request attributes/fields that are determined by the connector on which the
     * request was received
     * </ul>
     */
    public interface Customizer
    {
        Request customize(Request request, HttpFields.Mutable responseHeaders);
    }

    public interface ConnectionFactory
    {
        HttpConfiguration getHttpConfiguration();
    }

    public HttpConfiguration()
    {
        _formEncodedMethods.put(HttpMethod.POST.asString(), Boolean.TRUE);
        _formEncodedMethods.put(HttpMethod.PUT.asString(), Boolean.TRUE);
    }

    /**
     * Creates a configuration from another.
     *
     * @param config The configuration to copy.
     */
    public HttpConfiguration(HttpConfiguration config)
    {
        _customizers.addAll(config._customizers);
        _complianceViolationListeners.addAll(config._complianceViolationListeners);
        for (String s : config._formEncodedMethods.keySet())
        {
            _formEncodedMethods.put(s, Boolean.TRUE);
        }
        _outputBufferSize = config._outputBufferSize;
        _outputAggregationSize = config._outputAggregationSize;
        _requestHeaderSize = config._requestHeaderSize;
        _responseHeaderSize = config._responseHeaderSize;
        _headerCacheSize = config._headerCacheSize;
        _headerCacheCaseSensitive = config._headerCacheCaseSensitive;
        _secureScheme = config._secureScheme;
        _securePort = config._securePort;
        _idleTimeout = config._idleTimeout;
        _sendDateHeader = config._sendDateHeader;
        _sendServerVersion = config._sendServerVersion;
        _sendXPoweredBy = config._sendXPoweredBy;
        _delayDispatchUntilContent = config._delayDispatchUntilContent;
        _persistentConnectionsEnabled = config._persistentConnectionsEnabled;
        _maxErrorDispatches = config._maxErrorDispatches;
        _useInputDirectByteBuffers = config._useInputDirectByteBuffers;
        _useOutputDirectByteBuffers = config._useOutputDirectByteBuffers;
        _minRequestDataRate = config._minRequestDataRate;
        _minResponseDataRate = config._minResponseDataRate;
        _httpCompliance = config._httpCompliance;
        _requestCookieCompliance = config._requestCookieCompliance;
        _responseCookieCompliance = config._responseCookieCompliance;
        _multiPartCompliance = config._multiPartCompliance;
        _notifyRemoteAsyncErrors = config._notifyRemoteAsyncErrors;
        _relativeRedirectAllowed = config._relativeRedirectAllowed;
        _uriCompliance = config._uriCompliance;
        _serverAuthority = config._serverAuthority;
        _localAddress = config._localAddress;
    }

    /**
     * <p>Adds a {@link Customizer} that is invoked for every
     * request received.</p>
     * <p>Customizers are often used to interpret optional headers (eg {@link ForwardedRequestCustomizer}) or
     * optional protocol semantics (eg {@link SecureRequestCustomizer}).
     *
     * @param customizer A request customizer
     */
    public void addCustomizer(Customizer customizer)
    {
        _customizers.add(customizer);
    }

    public List<Customizer> getCustomizers()
    {
        return _customizers;
    }

    public <T> T getCustomizer(Class<T> type)
    {
        for (Customizer c : _customizers)
        {
            if (type.isAssignableFrom(c.getClass()))
                return (T)c;
        }
        return null;
    }

    public boolean removeCustomizer(Customizer customizer)
    {
        return _customizers.remove(customizer);
    }

    @ManagedAttribute("The size in bytes of the output buffer used to aggregate HTTP output")
    public int getOutputBufferSize()
    {
        return _outputBufferSize;
    }

    @ManagedAttribute("The maximum size in bytes for HTTP output to be aggregated")
    public int getOutputAggregationSize()
    {
        return _outputAggregationSize;
    }

    @ManagedAttribute("The maximum allowed size in bytes for an HTTP request header")
    public int getRequestHeaderSize()
    {
        return _requestHeaderSize;
    }

    @ManagedAttribute("The maximum allowed size in bytes for an HTTP response header")
    public int getResponseHeaderSize()
    {
        return _responseHeaderSize;
    }

    @ManagedAttribute("The maximum allowed size in Trie nodes for an HTTP header field cache")
    public int getHeaderCacheSize()
    {
        return _headerCacheSize;
    }

    @ManagedAttribute("True if the header field cache is case sensitive")
    public boolean isHeaderCacheCaseSensitive()
    {
        return _headerCacheCaseSensitive;
    }

    @ManagedAttribute("The port to which Integral or Confidential security constraints are redirected")
    public int getSecurePort()
    {
        return _securePort;
    }

    @ManagedAttribute("The scheme with which Integral or Confidential security constraints are redirected")
    public String getSecureScheme()
    {
        return _secureScheme;
    }

    @ManagedAttribute("Whether persistent connections are enabled")
    public boolean isPersistentConnectionsEnabled()
    {
        return _persistentConnectionsEnabled;
    }

    /**
     * <p>The max idle time is applied to an HTTP request for IO operations and
     * delayed dispatch.</p>
     *
     * @return the max idle time in ms or if == 0 implies an infinite timeout, &lt;0
     * implies no HTTP channel timeout and the connection timeout is used instead.
     */
    @ManagedAttribute("The idle timeout in ms for I/O operations during the handling of an HTTP request")
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * <p>The max idle time is applied to an HTTP request for IO operations and
     * delayed dispatch.</p>
     *
     * @param timeoutMs the max idle time in ms or if == 0 implies an infinite timeout, &lt;0
     * implies no HTTP channel timeout and the connection timeout is used instead.
     */
    public void setIdleTimeout(long timeoutMs)
    {
        _idleTimeout = timeoutMs;
    }

    public void setPersistentConnectionsEnabled(boolean persistentConnectionsEnabled)
    {
        _persistentConnectionsEnabled = persistentConnectionsEnabled;
    }

    public void setSendServerVersion(boolean sendServerVersion)
    {
        _sendServerVersion = sendServerVersion;
    }

    @ManagedAttribute("Whether to send the Server header in responses")
    public boolean getSendServerVersion()
    {
        return _sendServerVersion;
    }

    public void writePoweredBy(Appendable out, String preamble, String postamble) throws IOException
    {
        if (getSendServerVersion())
        {
            if (preamble != null)
                out.append(preamble);
            out.append(Jetty.POWERED_BY);
            if (postamble != null)
                out.append(postamble);
        }
    }

    public void setSendXPoweredBy(boolean sendXPoweredBy)
    {
        _sendXPoweredBy = sendXPoweredBy;
    }

    @ManagedAttribute("Whether to send the X-Powered-By header in responses")
    public boolean getSendXPoweredBy()
    {
        return _sendXPoweredBy;
    }

    /**
     * Indicates if the {@code Date} header should be sent in responses.
     *
     * @param sendDateHeader true if the {@code Date} header should be sent in responses
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.1.2">HTTP/1.1 Standard Header: Date</a>
     * @see #getSendDateHeader()
     */
    public void setSendDateHeader(boolean sendDateHeader)
    {
        _sendDateHeader = sendDateHeader;
    }

    /**
     * Indicates if the {@code Date} header will be sent in responses.
     *
     * @return true by default
     */
    @ManagedAttribute("Whether to send the Date header in responses")
    public boolean getSendDateHeader()
    {
        return _sendDateHeader;
    }

    /**
     * Set if true, delays the application dispatch until content is available (defaults to true).
     * @param delay if true, delays the application dispatch until content is available (defaults to true)
     */
    public void setDelayDispatchUntilContent(boolean delay)
    {
        _delayDispatchUntilContent = delay;
    }

    @ManagedAttribute("Whether to delay the application dispatch until content is available")
    public boolean isDelayDispatchUntilContent()
    {
        return _delayDispatchUntilContent;
    }

    /**
     * Set whether to use direct ByteBuffers for reading.
     * @param useInputDirectByteBuffers whether to use direct ByteBuffers for reading
     */
    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        _useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for reading")
    public boolean isUseInputDirectByteBuffers()
    {
        return _useInputDirectByteBuffers;
    }

    /**
     * Set whether to use direct ByteBuffers for writing.
     * @param useOutputDirectByteBuffers whether to use direct ByteBuffers for writing
     */
    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        _useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for writing")
    public boolean isUseOutputDirectByteBuffers()
    {
        return _useOutputDirectByteBuffers;
    }

    /**
     * <p>Sets the {@link Customizer}s that are invoked for every
     * request received.</p>
     * <p>Customizers are often used to interpret optional headers (eg {@link ForwardedRequestCustomizer}) or
     * optional protocol semantics (eg {@link SecureRequestCustomizer}).</p>
     *
     * @param customizers the list of customizers
     */
    public void setCustomizers(List<Customizer> customizers)
    {
        _customizers.clear();
        _customizers.addAll(customizers);
    }

    /**
     * Set the size of the buffer into which response content is aggregated
     * before being sent to the client.  A larger buffer can improve performance by allowing
     * a content producer to run without blocking, however larger buffers consume more memory and
     * may induce some latency before a client starts processing the content.
     *
     * @param outputBufferSize buffer size in bytes.
     */
    public void setOutputBufferSize(int outputBufferSize)
    {
        _outputBufferSize = outputBufferSize;
        setOutputAggregationSize(outputBufferSize / 4);
    }

    /**
     * Set the max size of the response content write that is copied into the aggregate buffer.
     * Writes that are smaller of this size are copied into the aggregate buffer, while
     * writes that are larger of this size will cause the aggregate buffer to be flushed
     * and the write to be executed without being copied.
     *
     * @param outputAggregationSize the max write size that is aggregated
     */
    public void setOutputAggregationSize(int outputAggregationSize)
    {
        _outputAggregationSize = outputAggregationSize;
    }

    /**
     * <p>Larger headers will allow for more and/or larger cookies plus larger form content encoded
     * in a URL. However, larger headers consume more memory and can make a server more vulnerable to denial of service
     * attacks.</p>
     *
     * @param requestHeaderSize the maximum size in bytes of the request header
     */
    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _requestHeaderSize = requestHeaderSize;
    }

    /**
     * <p>Larger headers will allow for more and/or larger cookies and longer HTTP headers (eg for redirection).
     * However, larger headers will also consume more memory.</p>
     *
     * @param responseHeaderSize the maximum size in bytes of the response header
     */
    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _responseHeaderSize = responseHeaderSize;
    }

    /**
     * @param headerCacheSize The size of the header field cache, in terms of unique characters branches
     * in the lookup {@link Index.Mutable} and associated data structures.
     */
    public void setHeaderCacheSize(int headerCacheSize)
    {
        _headerCacheSize = headerCacheSize;
    }

    public void setHeaderCacheCaseSensitive(boolean headerCacheCaseSensitive)
    {
        this._headerCacheCaseSensitive = headerCacheCaseSensitive;
    }

    /**
     * <p>Sets the TCP/IP port used for CONFIDENTIAL and INTEGRAL redirections.</p>
     *
     * @param securePort the secure port to redirect to.
     */
    public void setSecurePort(int securePort)
    {
        _securePort = securePort;
    }

    /**
     * <p>Set the  URI scheme used for CONFIDENTIAL and INTEGRAL redirections.</p>
     *
     * @param secureScheme A scheme string like "https"
     */
    public void setSecureScheme(String secureScheme)
    {
        _secureScheme = secureScheme;
    }

    /**
     * Sets the form encoded HTTP methods.
     *
     * @param methods the HTTP methods of requests that can be decoded as
     * {@code x-www-form-urlencoded} content.
     */
    public void setFormEncodedMethods(String... methods)
    {
        _formEncodedMethods.clear();
        for (String method : methods)
        {
            addFormEncodedMethod(method);
        }
    }

    /**
     * @return the set of HTTP methods of requests that can be decoded as
     * {@code x-www-form-urlencoded} content.
     */
    public Set<String> getFormEncodedMethods()
    {
        return _formEncodedMethods.keySet();
    }

    /**
     * Adds a form encoded HTTP Method
     *
     * @param method the HTTP method of requests that can be decoded as
     * {@code x-www-form-urlencoded} content.
     */
    public void addFormEncodedMethod(String method)
    {
        _formEncodedMethods.put(method, Boolean.TRUE);
    }

    /**
     * Tests whether the HTTP method supports {@code x-www-form-urlencoded} content
     *
     * @param method the HTTP method
     * @return true if requests with this method can be
     * decoded as {@code x-www-form-urlencoded} content.
     */
    public boolean isFormEncodedMethod(String method)
    {
        return _formEncodedMethods.get(method) != null;
    }

    /**
     * @return The maximum error dispatches for a request to prevent looping on an error
     */
    @ManagedAttribute("The maximum ERROR dispatches for a request for loop prevention (default 10)")
    public int getMaxErrorDispatches()
    {
        return _maxErrorDispatches;
    }

    /**
     * @param max The maximum error dispatches for a request to prevent looping on an error
     */
    public void setMaxErrorDispatches(int max)
    {
        _maxErrorDispatches = max;
    }

    /**
     * @return The minimum request data rate in bytes per second; or &lt;=0 for no limit
     */
    @ManagedAttribute("The minimum request content data rate in bytes per second")
    public long getMinRequestDataRate()
    {
        return _minRequestDataRate;
    }

    /**
     * @param bytesPerSecond The minimum request data rate in bytes per second; or &lt;=0 for no limit
     */
    public void setMinRequestDataRate(long bytesPerSecond)
    {
        _minRequestDataRate = bytesPerSecond;
    }

    /**
     * @return The minimum response data rate in bytes per second; or &lt;=0 for no limit
     */
    @ManagedAttribute("The minimum response content data rate in bytes per second")
    public long getMinResponseDataRate()
    {
        return _minResponseDataRate;
    }

    /**
     * <p>Sets an minimum response content data rate.</p>
     * <p>The value is enforced only approximately - not precisely - due to the fact that
     * for efficiency reasons buffer writes may be comprised of both response headers and
     * response content.</p>
     *
     * @param bytesPerSecond The minimum response data rate in bytes per second; or &lt;=0 for no limit
     */
    public void setMinResponseDataRate(long bytesPerSecond)
    {
        _minResponseDataRate = bytesPerSecond;
    }

    public HttpCompliance getHttpCompliance()
    {
        return _httpCompliance;
    }

    public void setHttpCompliance(HttpCompliance httpCompliance)
    {
        _httpCompliance = httpCompliance;
    }

    public UriCompliance getUriCompliance()
    {
        return _uriCompliance;
    }

    public void setUriCompliance(UriCompliance uriCompliance)
    {
        _uriCompliance = uriCompliance;
    }

    /**
     * @return The CookieCompliance used for parsing request {@code Cookie} headers.
     * @see #getResponseCookieCompliance()
     */
    public CookieCompliance getRequestCookieCompliance()
    {
        return _requestCookieCompliance;
    }

    /**
     * @param cookieCompliance The CookieCompliance to use for parsing request {@code Cookie} headers.
     */
    public void setRequestCookieCompliance(CookieCompliance cookieCompliance)
    {
        _requestCookieCompliance = cookieCompliance == null ? CookieCompliance.RFC6265 : cookieCompliance;
    }

    /**
     * @return The CookieCompliance used for generating response {@code Set-Cookie} headers
     * @see #getRequestCookieCompliance()
     */
    public CookieCompliance getResponseCookieCompliance()
    {
        return _responseCookieCompliance;
    }

    /**
     * @param cookieCompliance The CookieCompliance to use for generating response {@code Set-Cookie} headers
     */
    public void setResponseCookieCompliance(CookieCompliance cookieCompliance)
    {
        _responseCookieCompliance = cookieCompliance == null ? CookieCompliance.RFC6265 : cookieCompliance;
    }

    /**
     * @return the {@link MultiPartCompliance} used for validating multipart form syntax.
     */
    public MultiPartCompliance getMultiPartCompliance()
    {
        return _multiPartCompliance;
    }

    /**
     * @param multiPartCompliance the {@link MultiPartCompliance} used for validating multipart form syntax.
     */
    public void setMultiPartCompliance(MultiPartCompliance multiPartCompliance)
    {
        this._multiPartCompliance = multiPartCompliance;
    }

    /**
     * Set whether compliance violations (from {@link HttpCompliance}, {@link CookieCompliance}, {@link UriCompliance}, and {@link MultiPartCompliance})
     * should be reported to the registered ComplianceViolation.Listeners.
     *
     * @return true if Compliance Violations should be notified via the ComplianceViolation.Listener mechanism.
     */
    public boolean isNotifyComplianceViolations()
    {
        return _complianceViolationListeners.stream().anyMatch(ComplianceViolation.LoggingListener.class::isInstance);
    }

    /**
     * @param notifyComplianceViolations true
     */
    public void setNotifyComplianceViolations(boolean notifyComplianceViolations)
    {
        if (notifyComplianceViolations == isNotifyComplianceViolations())
            return;
        if (notifyComplianceViolations)
            _complianceViolationListeners.add(new ComplianceViolation.LoggingListener());
        else
            _complianceViolationListeners.stream()
                .filter(ComplianceViolation.LoggingListener.class::isInstance)
                .forEach(_complianceViolationListeners::remove);
        _combinedComplianceViolationListener = null;
    }

    /**
     * Set whether remote errors, when detected, are notified to async applications.
     * @param notifyRemoteAsyncErrors whether remote errors, when detected, are notified to async applications
     */
    public void setNotifyRemoteAsyncErrors(boolean notifyRemoteAsyncErrors)
    {
        this._notifyRemoteAsyncErrors = notifyRemoteAsyncErrors;
    }

    /**
     * @return whether remote errors, when detected, are notified to async applications
     */
    @ManagedAttribute("Whether remote errors, when detected, are notified to async applications")
    public boolean isNotifyRemoteAsyncErrors()
    {
        return _notifyRemoteAsyncErrors;
    }

    /**
     * @param allowed True if relative redirection locations are allowed
     */
    public void setRelativeRedirectAllowed(boolean allowed)
    {
        _relativeRedirectAllowed = allowed;
    }

    /**
     * @return True if relative redirection locations are allowed
     */
    @ManagedAttribute("Whether relative redirection locations are allowed")
    public boolean isRelativeRedirectAllowed()
    {
        return _relativeRedirectAllowed;
    }

    /**
     * Get the SocketAddress override to be reported as the local address of all connections
     *
     * @return Returns the connection local address override or null.
     */
    @ManagedAttribute("Local SocketAddress override")
    public SocketAddress getLocalAddress()
    {
        return _localAddress;
    }

    /**
     * <p>
     * Specify the connection local address used within application API layer
     * when identifying the local host name/port of a connected endpoint.
     * </p>
     * <p>
     * This allows an override of higher level APIs, such as
     * {@code ServletRequest.getLocalName()}, {@code ServletRequest.getLocalAddr()},
     * and {@code ServletRequest.getLocalPort()}.
     * </p>
     *
     * @param localAddress the address to use for host/addr/port, or null to reset to default behavior
     */
    public void setLocalAddress(SocketAddress localAddress)
    {
        _localAddress = localAddress;
    }

    /**
     * Get the Server authority override to be used if no authority is provided by a request.
     *
     * @return Returns the connection server authority (name/port) or null
     */
    @ManagedAttribute("The server authority if none provided by requests")
    public HostPort getServerAuthority()
    {
        return _serverAuthority;
    }

    /**
     * <p>
     * Specify the connection server authority (name/port) used within application API layer
     * when identifying the server host name/port of a connected endpoint.
     * </p>
     *
     * <p>
     * This allows an override of higher level APIs, such as
     * {@code ServletRequest.getServerName()}, and {@code ServletRequest.getServerPort()}.
     * </p>
     *
     * @param authority the authority host (and optional port), or null to reset to default behavior
     */
    public void setServerAuthority(HostPort authority)
    {
        if (authority == null)
            _serverAuthority = null;
        else if (!authority.hasHost())
            throw new IllegalStateException("Server Authority must have host declared");
        else
            _serverAuthority = authority;
    }

    /**
     * Sets the maximum amount of {@link HttpStream#read()}s that can be done by the {@link HttpStream} if the content is not
     * fully consumed by the application. If this is unable to consume to EOF then the connection will be made non-persistent.
     *
     * @param maxUnconsumedRequestContentReads the maximum amount of reads for unconsumed content or -1 for unlimited.
     */
    public void setMaxUnconsumedRequestContentReads(int maxUnconsumedRequestContentReads)
    {
        _maxUnconsumedRequestContentReads = maxUnconsumedRequestContentReads;
    }

    /**
     * Gets the maximum amount of {@link HttpStream#read()}s that can be done by the {@link HttpStream} if the content is not
     * fully consumed by the application. If this is unable to consume to EOF then the connection will be made non-persistent.
     *
     * @return the maximum amount of reads for unconsumed content or -1 for unlimited.
     */
    public int getMaxUnconsumedRequestContentReads()
    {
        return _maxUnconsumedRequestContentReads;
    }

    public void addComplianceViolationListener(ComplianceViolation.Listener listener)
    {
        _complianceViolationListeners.add(listener);
        _combinedComplianceViolationListener = null;
    }

    public void removeComplianceViolationListener(ComplianceViolation.Listener listener)
    {
        _complianceViolationListeners.remove(listener);
        _combinedComplianceViolationListener = null;
    }

    public ComplianceViolation.Listener getCombinedComplianceViolationListener()
    {
        if (_combinedComplianceViolationListener == null)
        {
            _combinedComplianceViolationListener = switch (_complianceViolationListeners.size())
            {
                case 0 -> ComplianceViolation.Listener.NOOP;
                case 1 -> _complianceViolationListeners.get(0);
                default -> new ComplianceViolationListenerCollection(_complianceViolationListeners);
            };
        }
        return _combinedComplianceViolationListener;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new DumpableCollection("customizers", _customizers),
            new DumpableCollection("formEncodedMethods", _formEncodedMethods.keySet()),
            "outputBufferSize=" + _outputBufferSize,
            "outputAggregationSize=" + _outputAggregationSize,
            "requestHeaderSize=" + _requestHeaderSize,
            "responseHeaderSize=" + _responseHeaderSize,
            "headerCacheSize=" + _headerCacheSize,
            "secureScheme=" + _secureScheme,
            "securePort=" + _securePort,
            "idleTimeout=" + _idleTimeout,
            "sendDateHeader=" + _sendDateHeader,
            "sendServerVersion=" + _sendServerVersion,
            "sendXPoweredBy=" + _sendXPoweredBy,
            "delayDispatchUntilContent=" + _delayDispatchUntilContent,
            "persistentConnectionsEnabled=" + _persistentConnectionsEnabled,
            "maxErrorDispatches=" + _maxErrorDispatches,
            "minRequestDataRate=" + _minRequestDataRate,
            "minResponseDataRate=" + _minResponseDataRate,
            "requestCookieCompliance=" + _requestCookieCompliance,
            "responseCookieCompliance=" + _responseCookieCompliance,
            "notifyRemoteAsyncErrors=" + _notifyRemoteAsyncErrors,
            "relativeRedirectAllowed=" + _relativeRedirectAllowed
        );
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%d/%d,%d/%d,%s://:%d,%s}",
            this.getClass().getSimpleName(),
            hashCode(),
            _outputBufferSize,
            _outputAggregationSize,
            _requestHeaderSize,
            _responseHeaderSize,
            _secureScheme,
            _securePort,
            _customizers);
    }

    /**
     * A Listener that represents multiple user {@link ComplianceViolation.Listener} instances
     */
    private static class ComplianceViolationListenerCollection implements ComplianceViolation.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ComplianceViolationListenerCollection.class);
        private final List<ComplianceViolation.Listener> _listeners;

        /**
         * Construct a new ComplianceViolations that will notify user listeners.
         *
         * @param listeners the user listeners to notify, null or empty is allowed.
         */
        public ComplianceViolationListenerCollection(List<ComplianceViolation.Listener> listeners)
        {
            _listeners = listeners;
        }

        @Override
        public ComplianceViolation.Listener initialize()
        {
            List<ComplianceViolation.Listener> initialized = null;
            for (ComplianceViolation.Listener listener : _listeners)
            {
                ComplianceViolation.Listener listening = listener.initialize();
                if (listening != listener)
                {
                    initialized = new ArrayList<>(_listeners.size());
                    for (ComplianceViolation.Listener l : _listeners)
                    {
                        if (l == listener)
                            break;
                        initialized.add(l);
                    }
                }
                if (initialized != null)
                    initialized.add(listening);
            }
            if (initialized == null)
                return this;
            return new ComplianceViolationListenerCollection(initialized);
        }

        @Override
        public void onRequestBegin(Attributes request)
        {
            for (ComplianceViolation.Listener listener : _listeners)
            {
                try
                {
                    listener.onRequestBegin(request);
                }
                catch (Throwable t)
                {
                    LOG.warn("Unable to begin request on {}", listener, t);
                }
            }
        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            for (ComplianceViolation.Listener listener : _listeners)
            {
                try
                {
                    listener.onRequestEnd(request);
                }
                catch (Throwable t)
                {
                    LOG.warn("Unable to end request on {}", listener, t);
                }
            }
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            assert event != null;
            notifyUserListeners(event);
        }

        private void notifyUserListeners(ComplianceViolation.Event event)
        {
            if (_listeners == null || _listeners.isEmpty())
                return;

            for (ComplianceViolation.Listener listener : _listeners)
            {
                try
                {
                    listener.onComplianceViolation(event);
                }
                catch (Throwable t)
                {
                    LOG.warn("Unable to notify ComplianceViolation.Listener implementation at {} of event {}", listener, event, t);
                }
            }
        }
    }
}
