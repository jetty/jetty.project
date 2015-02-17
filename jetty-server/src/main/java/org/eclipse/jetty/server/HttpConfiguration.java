//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;


/* ------------------------------------------------------------ */
/** HTTP Configuration.
 * <p>This class is a holder of HTTP configuration for use by the 
 * {@link HttpChannel} class.  Typically a HTTPConfiguration instance
 * is instantiated and passed to a {@link ConnectionFactory} that can 
 * create HTTP channels (e.g. HTTP, AJP or FCGI).</p>
 * <p>The configuration held by this class is not for the wire protocol,
 * but for the interpretation and handling of HTTP requests that could
 * be transported by a variety of protocols.
 * </p>
 */
@ManagedObject("HTTP Configuration")
public class HttpConfiguration
{
    public static final String SERVER_VERSION = "Jetty(" + Jetty.VERSION + ")";

    private List<Customizer> _customizers=new CopyOnWriteArrayList<>();
    private int _outputBufferSize=32*1024;
    private int _outputAggregationSize=_outputBufferSize/4;
    private int _requestHeaderSize=8*1024;
    private int _responseHeaderSize=8*1024;
    private int _headerCacheSize=512;
    private int _securePort;
    private String _secureScheme = HttpScheme.HTTPS.asString();
    private boolean _sendServerVersion = true;
    private boolean _sendXPoweredBy = false;
    private boolean _sendDateHeader = true;
    private boolean _delayDispatchUntilContent = true;

    /* ------------------------------------------------------------ */
    /** 
     * <p>An interface that allows a request object to be customized 
     * for a particular HTTP connector configuration.  Unlike Filters, customizer are
     * applied before the request is submitted for processing and can be specific to the 
     * connector on which the request was received.
     * 
     * <p>Typically Customizers perform tasks such as: <ul>
     *  <li>process header fields that may be injected by a proxy or load balancer.
     *  <li>setup attributes that may come from the connection/connector such as SSL Session IDs
     *  <li>Allow a request to be marked as secure or authenticated if those have been offloaded
     *  and communicated by header, cookie or other out-of-band mechanism
     *  <li>Set request attributes/fields that are determined by the connector on which the
     *  request was received
     *  </ul>
     */
    public interface Customizer
    {
        public void customize(Connector connector, HttpConfiguration channelConfig, Request request);
    }
    
    public interface ConnectionFactory
    {
        HttpConfiguration getHttpConfiguration();
    }
    
    public HttpConfiguration()
    {
    }
    
    /* ------------------------------------------------------------ */
    /** Create a configuration from another.
     * @param config The configuration to copy.
     */
    public HttpConfiguration(HttpConfiguration config)
    {
        _customizers.addAll(config._customizers);
        _outputBufferSize=config._outputBufferSize;
        _outputAggregationSize=config._outputAggregationSize;
        _requestHeaderSize=config._requestHeaderSize;
        _responseHeaderSize=config._responseHeaderSize;
        _securePort=config._securePort;
        _secureScheme=config._secureScheme;
        _sendDateHeader=config._sendDateHeader;
        _sendServerVersion=config._sendServerVersion;
        _headerCacheSize=config._headerCacheSize;
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * <p>Add a {@link Customizer} that is invoked for every 
     * request received.</p>
     * <p>Customiser are often used to interpret optional headers (eg {@link ForwardedRequestCustomizer}) or 
     * optional protocol semantics (eg {@link SecureRequestCustomizer}). 
     * @param customizer A request customizer
     */
    public void addCustomizer(Customizer customizer)
    {
        _customizers.add(customizer);
    }
    
    /* ------------------------------------------------------------ */
    public List<Customizer> getCustomizers()
    {
        return _customizers;
    }

    /* ------------------------------------------------------------ */
    public <T> T getCustomizer(Class<T> type)
    {
        for (Customizer c : _customizers)
            if (type.isAssignableFrom(c.getClass()))
                return (T)c;
        return null;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The size in bytes of the output buffer used to aggregate HTTP output")
    public int getOutputBufferSize()
    {
        return _outputBufferSize;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The maximum size in bytes for HTTP output to be aggregated")
    public int getOutputAggregationSize()
    {
        return _outputAggregationSize;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The maximum allowed size in bytes for a HTTP request header")
    public int getRequestHeaderSize()
    {
        return _requestHeaderSize;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The maximum allowed size in bytes for a HTTP response header")
    public int getResponseHeaderSize()
    {
        return _responseHeaderSize;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The maximum allowed size in bytes for a HTTP header field cache")
    public int getHeaderCacheSize()
    {
        return _headerCacheSize;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The port to which Integral or Confidential security constraints are redirected")
    public int getSecurePort()
    {
        return _securePort;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("The scheme with which Integral or Confidential security constraints are redirected")
    public String getSecureScheme()
    {
        return _secureScheme;
    }

    /* ------------------------------------------------------------ */
    public void setSendServerVersion (boolean sendServerVersion)
    {
        _sendServerVersion = sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("if true, send the Server header in responses")
    public boolean getSendServerVersion()
    {
        return _sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    public void setSendXPoweredBy (boolean sendXPoweredBy)
    {
        _sendXPoweredBy=sendXPoweredBy;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("if true, send the X-Powered-By header in responses")
    public boolean getSendXPoweredBy()
    {
        return _sendXPoweredBy;
    }

    /* ------------------------------------------------------------ */
    public void setSendDateHeader(boolean sendDateHeader)
    {
        _sendDateHeader = sendDateHeader;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("if true, include the date in HTTP headers")
    public boolean getSendDateHeader()
    {
        return _sendDateHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param delay if true, delay the application dispatch until content is available
     */
    public void setDelayDispatchUntilContent(boolean delay)
    {
        _delayDispatchUntilContent = delay;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("if true, delay the application dispatch until content is available")
    public boolean isDelayDispatchUntilContent()
    {
        return _delayDispatchUntilContent;
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Set the {@link Customizer}s that are invoked for every 
     * request received.</p>
     * <p>Customizers are often used to interpret optional headers (eg {@link ForwardedRequestCustomizer}) or
     * optional protocol semantics (eg {@link SecureRequestCustomizer}). 
     * @param customizers the list of customizers
     */
    public void setCustomizers(List<Customizer> customizers)
    {
        _customizers.clear();
        _customizers.addAll(customizers);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the size of the buffer into which response content is aggregated
     * before being sent to the client.  A larger buffer can improve performance by allowing
     * a content producer to run without blocking, however larger buffers consume more memory and
     * may induce some latency before a client starts processing the content.
     * @param outputBufferSize buffer size in bytes.
     */
    public void setOutputBufferSize(int outputBufferSize)
    {
        _outputBufferSize = outputBufferSize;
        setOutputAggregationSize(outputBufferSize / 4);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Set the max size of the response content write that is copied into the aggregate buffer.
     * Writes that are smaller of this size are copied into the aggregate buffer, while
     * writes that are larger of this size will cause the aggregate buffer to be flushed
     * and the write to be executed without being copied.
     * @param outputAggregationSize the max write size that is aggregated
     */
    public void setOutputAggregationSize(int outputAggregationSize)
    {
        _outputAggregationSize = outputAggregationSize;
    }

    /* ------------------------------------------------------------ */
    /** Set the maximum size of a request header.
     * <p>Larger headers will allow for more and/or larger cookies plus larger form content encoded 
     * in a URL. However, larger headers consume more memory and can make a server more vulnerable to denial of service
     * attacks.</p>
     * @param requestHeaderSize Max header size in bytes
     */
    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _requestHeaderSize = requestHeaderSize;
    }

    /* ------------------------------------------------------------ */
    /** Set the maximum size of a response header.
     * 
     * <p>Larger headers will allow for more and/or larger cookies and longer HTTP headers (eg for redirection). 
     * However, larger headers will also consume more memory.</p>
     * @param responseHeaderSize Response header size in bytes.
     */
    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _responseHeaderSize = responseHeaderSize;
    }

    /* ------------------------------------------------------------ */
    /** Set the header field cache size.
     * @param headerCacheSize The size in bytes of the header field cache.
     */
    public void setHeaderCacheSize(int headerCacheSize)
    {
        _headerCacheSize = headerCacheSize;
    }

    /* ------------------------------------------------------------ */
    /** Set the TCP/IP port used for CONFIDENTIAL and INTEGRAL redirections.
     * @param securePort the secure port to redirect to.
     */
    public void setSecurePort(int securePort)
    {
        _securePort = securePort;
    }

    /* ------------------------------------------------------------ */
    /** Set the  URI scheme used for CONFIDENTIAL and INTEGRAL redirections.
     * @param secureScheme A scheme string like "https"
     */
    public void setSecureScheme(String secureScheme)
    {
        _secureScheme = secureScheme;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%d/%d,%d/%d,%s://:%d,%s}",
                this.getClass().getSimpleName(),
                hashCode(),
                _outputBufferSize, _outputAggregationSize,
                _requestHeaderSize,_responseHeaderSize,
                _secureScheme,_securePort,
                _customizers);
    }
}
