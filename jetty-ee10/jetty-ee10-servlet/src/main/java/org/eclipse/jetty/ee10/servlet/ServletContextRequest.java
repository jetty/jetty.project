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

package org.eclipse.jetty.ee10.servlet;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;
import java.util.Set;

import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.URIUtil;

/**
 * A core request wrapper that carries the servlet related request state,
 * which may be used directly by the associated {@link ServletApiRequest}.
 * Non-servlet related state, is used indirectly via {@link ServletChannel#getRequest()}
 * which may be a wrapper of this request.
 * <p>
 * This class is single use only.
 * </p>
 */
public class ServletContextRequest extends ContextRequest implements ServletContextHandler.ServletRequestInfo, Request.ServeAs
{
    public static final String MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";
    public static final String SSL_CIPHER_SUITE = "jakarta.servlet.request.cipher_suite";
    public static final String SSL_KEY_SIZE = "jakarta.servlet.request.key_size";
    public static final String SSL_SESSION_ID = "jakarta.servlet.request.ssl_session_id";
    public static final String PEER_CERTIFICATES = "jakarta.servlet.request.X509Certificate";

    private static final Set<String> ATTRIBUTES = Set.of(
        SSL_CIPHER_SUITE,
        SSL_KEY_SIZE,
        SSL_SESSION_ID,
        PEER_CERTIFICATES,
        MULTIPART_CONFIG_ELEMENT,
        FormFields.MAX_FIELDS_ATTRIBUTE,
        FormFields.MAX_LENGTH_ATTRIBUTE);

    static final int INPUT_NONE = 0;
    static final int INPUT_STREAM = 1;
    static final int INPUT_READER = 2;

    static final Fields NO_PARAMS = new Fields(Collections.emptyMap());
    static final Fields BAD_PARAMS = new Fields(Collections.emptyMap());

    public static ServletContextRequest getServletContextRequest(ServletRequest request)
    {
        if (request instanceof ServletApiRequest servletApiRequest &&
            servletApiRequest.getServletRequestInfo() instanceof ServletContextRequest servletContextRequest)
            return servletContextRequest;

        if (request.getAttribute(ServletChannel.class.getName()) instanceof ServletChannel servletChannel)
            return servletChannel.getServletContextRequest();

        while (request instanceof ServletRequestWrapper wrapper)
        {
            request = wrapper.getRequest();

            if (request instanceof ServletApiRequest servletApiRequest &&
                servletApiRequest.getServletRequestInfo() instanceof  ServletContextRequest servletContextRequest)
                return servletContextRequest;
        }

        throw new IllegalStateException("could not find %s for %s".formatted(ServletContextRequest.class.getSimpleName(), request));
    }

    private final ServletApiRequest _servletApiRequest;
    private final ServletContextResponse _response;
    private final MatchedResource<ServletHandler.MappedServlet> _matchedResource;
    private final HttpInput _httpInput;
    private final String _decodedPathInContext;
    private final ServletChannel _servletChannel;
    private final SessionManager _sessionManager;
    private final Attributes _attributes;

    private List<ServletRequestAttributeListener> _requestAttributeListeners;
    private Charset _queryEncoding;
    private HttpFields _trailers;
    private ManagedSession _managedSession;
    AbstractSessionManager.RequestedSession _requestedSession;

    protected ServletContextRequest(
        ServletContextHandler.ServletContextApi servletContextApi,
        ServletChannel servletChannel,
        Request request,
        Response response,
        String decodedPathInContext,
        MatchedResource<ServletHandler.MappedServlet> matchedResource,
        SessionManager sessionManager)
    {
        super(servletContextApi.getContext(), request);
        _servletChannel = servletChannel;
        _matchedResource = matchedResource;
        _httpInput = _servletChannel.getHttpInput();
        _decodedPathInContext = decodedPathInContext;
        _sessionManager = sessionManager;
        _servletApiRequest = newServletApiRequest();
        _response =  newServletContextResponse(response);
        _attributes = new Attributes.Synthetic(request)
        {
            @Override
            protected Object getSyntheticAttribute(String name)
            {
                return switch (name)
                {
                    case SSL_CIPHER_SUITE -> super.getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData data ? data.cipherSuite() : null;
                    case SSL_KEY_SIZE -> super.getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData data ? data.keySize() : null;
                    case SSL_SESSION_ID -> super.getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData data ? data.sslSessionId() : null;
                    case PEER_CERTIFICATES -> super.getAttribute(EndPoint.SslSessionData.ATTRIBUTE) instanceof EndPoint.SslSessionData data ? data.peerCertificates() : null;
                    case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> _matchedResource.getResource().getServletHolder().getMultipartConfigElement();
                    case FormFields.MAX_FIELDS_ATTRIBUTE -> getServletContext().getServletContextHandler().getMaxFormKeys();
                    case FormFields.MAX_LENGTH_ATTRIBUTE -> getServletContext().getServletContextHandler().getMaxFormContentSize();
                    default -> null;
                };
            }

            @Override
            protected Set<String> getSyntheticNameSet()
            {
                return ATTRIBUTES;
            }
        };

        addIdleTimeoutListener(_servletChannel.getServletRequestState()::onIdleTimeout);
    }

    @Override
    public Request wrap(Request request, HttpURI uri)
    {
        String decodedPathInContext = URIUtil.decodePath(getContext().getPathInContext(uri.getCanonicalPath()));
        MatchedResource<ServletHandler.MappedServlet> matchedResource = getServletContextHandler()
            .getServletHandler()
            .getMatchedServlet(decodedPathInContext);

        if (matchedResource == null)
            return null;

        ServletHandler.MappedServlet mappedServlet = matchedResource.getResource();
        if (mappedServlet == null)
            return null;

        ServletChannel servletChannel = getServletChannel();
        ServletContextRequest servletContextRequest = getServletContextHandler().newServletContextRequest(
            servletChannel,
            new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return uri;
                }
            },
            _response,
            decodedPathInContext,
            matchedResource
        );
        ServletContextRequest originalServletContextRequest = Request.asInContext(request, ServletContextRequest.class);
        if (originalServletContextRequest != null)
        {
            servletContextRequest.setRequestedSession(originalServletContextRequest.getRequestedSession());
            servletContextRequest.setManagedSession(originalServletContextRequest.getManagedSession());
        }
        servletChannel.associate(servletContextRequest);
        return servletContextRequest;
    }

    protected ServletApiRequest newServletApiRequest()
    {
        if (getHttpURI().hasViolations() && !getServletChannel().getServletContextHandler().getServletHandler().isDecodeAmbiguousURIs())
        {
            // TODO we should check if current compliance mode allows all the violations?
            StringBuilder msg = null;
            for (UriCompliance.Violation violation : getHttpURI().getViolations())
            {
                if (UriCompliance.AMBIGUOUS_VIOLATIONS.contains(violation))
                {
                    if (msg == null)
                    {
                        msg = new StringBuilder();
                        msg.append("Ambiguous URI encoding: ");
                    }
                    else
                    {
                        msg.append(", ");
                    }

                    msg.append(violation.name());
                }
            }
            if (msg != null)
                return new ServletApiRequest.AmbiguousURI(this, msg.toString());
        }

        if (getServletContextHandler().isCrossContextDispatchSupported())
        {
            if (DispatcherType.INCLUDE.toString().equals(getContext().getCrossContextDispatchType(getWrapped())))
                return new ServletApiRequest.CrossContextIncluded(this);
            else if (DispatcherType.FORWARD.toString().equals(getContext().getCrossContextDispatchType(getWrapped())))
                return new ServletApiRequest.CrossContextForwarded(this);
            else
                return new ServletApiRequest(this);
        }
        else
           return new ServletApiRequest(this);
    }

    protected ServletContextResponse newServletContextResponse(Response response)
    {
        return new ServletContextResponse(_servletChannel, this, response);
    }

    @Override
    public ServletContextHandler getServletContextHandler()
    {
        return _servletChannel.getServletContextHandler();
    }

    @Override
    public String getDecodedPathInContext()
    {
        return _decodedPathInContext;
    }

    @Override
    public MatchedResource<ServletHandler.MappedServlet> getMatchedResource()
    {
        return _matchedResource;
    }

    @Override
    public HttpFields getTrailers()
    {
        return _trailers;
    }

    void setTrailers(HttpFields trailers)
    {
        _trailers = trailers;
    }

    @Override
    public ServletChannelState getState()
    {
        return _servletChannel.getServletRequestState();
    }

    public ServletContextResponse getServletContextResponse()
    {
        return _response;
    }

    @Override
    public ServletContextHandler.ServletScopedContext getServletContext()
    {
        if (super.getContext() instanceof ServletContextHandler.ServletScopedContext servletScopedContext)
            return servletScopedContext;
        return null;
    }

    @Override
    public HttpInput getHttpInput()
    {
        return _httpInput;
    }

    public HttpOutput getHttpOutput()
    {
        return _response.getHttpOutput();
    }

    public void errorClose()
    {
        // TODO Actually make the response status and headers immutable temporarily
        _response.getHttpOutput().softClose();
    }

    public boolean isHead()
    {
        return HttpMethod.HEAD.is(getMethod());
    }

    /**
     * Set the character encoding used for the query string. This call will effect the return of getQueryString and getParamaters. It must be called before any
     * getParameter methods.
     * <p>
     * The request attribute "org.eclipse.jetty.server.Request.queryEncoding" may be set as an alternate method of calling setQueryEncoding.
     *
     * @param queryEncoding the URI query character encoding
     */
    @Override
    public void setQueryEncoding(String queryEncoding)
    {
        _queryEncoding = Charset.forName(queryEncoding);
    }

    @Override
    public Charset getQueryEncoding()
    {
        return _queryEncoding;
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Object removeAttribute(String name)
    {
        return _attributes.removeAttribute(name);
    }

    @Override
    public Object setAttribute(String name, Object value)
    {
        return _attributes.setAttribute(name, value);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _attributes.getAttributeNameSet();
    }

    /**
     * @return The current {@link ContextHandler.ScopedContext context} used for this error handling for this request.
     * If the request is asynchronous, then it is the context that called async. Otherwise, it is the last non-null
     * context passed to #setContext
     */
    public ServletContextHandler.ServletScopedContext getErrorContext()
    {
        // TODO: review.
        return _servletChannel.getContext();
    }

    @Override
    public ServletChannelState getServletRequestState()
    {
        return _servletChannel.getServletRequestState();
    }

    @Override
    public ServletChannel getServletChannel()
    {
        return _servletChannel;
    }

    public ServletApiRequest getServletApiRequest()
    {
        return _servletApiRequest;
    }

    public HttpServletResponse getHttpServletResponse()
    {
        return _response.getServletApiResponse();
    }

    public String getServletName()
    {
        return getMatchedResource().getResource().getServletHolder().getName();
    }

    @Override
    public List<ServletRequestAttributeListener> getRequestAttributeListeners()
    {
        if (_requestAttributeListeners == null)
            _requestAttributeListeners = new ArrayList<>();
        return _requestAttributeListeners;
    }

    public void addEventListener(EventListener listener)
    {
        if (listener instanceof ServletRequestAttributeListener attributeListener)
        {
            if (_requestAttributeListeners == null)
                _requestAttributeListeners = new ArrayList<>();
            _requestAttributeListeners.add(attributeListener);
        }
        if (listener instanceof AsyncListener)
            throw new IllegalArgumentException(listener.getClass().toString());
    }

    public void removeEventListener(EventListener listener)
    {
        if (_requestAttributeListeners != null)
            _requestAttributeListeners.remove(listener);
    }

    /**
     * Compares fields to {@link #NO_PARAMS} by Reference
     *
     * @param fields The parameters to compare to {@link #NO_PARAMS}
     * @return {@code true} if the fields reference is equal to {@link #NO_PARAMS}, otherwise {@code false}
     */
    static boolean isNoParams(Fields fields)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isNoParams = (fields == NO_PARAMS);
        return isNoParams;
    }

    @Override
    public ManagedSession getManagedSession()
    {
        return _managedSession;
    }

    public void setManagedSession(ManagedSession managedSession)
    {
        _managedSession = managedSession;
    }

    @Override
    public SessionManager getSessionManager()
    {
        return _sessionManager;
    }

    public void setRequestedSession(AbstractSessionManager.RequestedSession requestedSession)
    {
        if (_requestedSession != null)
            throw new IllegalStateException();
        _requestedSession = requestedSession;
        _managedSession = requestedSession.session();
    }

    @Override
    public AbstractSessionManager.RequestedSession getRequestedSession()
    {
        return _requestedSession;
    }

    @Override
    public Session getSession(boolean create)
    {
        if (_managedSession != null)
        {
            if (_sessionManager != null && !_managedSession.isValid())
                _managedSession = null;
            else
                return _managedSession;
        }

        if (!create)
            return null;

        if (_response.isCommitted())
            throw new IllegalStateException("Response is committed");

        if (_sessionManager == null)
            throw new IllegalStateException("No SessionManager");

        _sessionManager.newSession(this, _requestedSession.sessionId(), this::setManagedSession);

        if (_managedSession == null)
            throw new IllegalStateException("Create session failed");

        HttpCookie cookie = _sessionManager.getSessionCookie(_managedSession, isSecure());
        if (cookie != null)
            Response.putCookie(_response, cookie);

        return _managedSession;
    }
}
