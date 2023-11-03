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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import jakarta.servlet.AsyncListener;
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
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.SslSessionData;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionManager;
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
    public static final String JAKARTA_SERVLET_REQUEST_PREFIX = "jakarta.servlet.request.";
    public static final String MULTIPART_CONFIG_ELEMENT = "org.eclipse.jetty.multipartConfig";
    public static final String JAKARTA_SERVLET_REQUEST_CIPHER_SUITE = "jakarta.servlet.request.cipher_suite";
    public static final String JAKARTA_SERVLET_REQUEST_KEY_SIZE = "jakarta.servlet.request.key_size";
    public static final String JAKARTA_SERVLET_REQUEST_SSL_SESSION_ID = "jakarta.servlet.request.ssl_session_id";
    public static final String JAKARTA_SERVLET_REQUEST_X_509_CERTIFICATE = "jakarta.servlet.request.X509Certificate";

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
        _servletApiRequest = newServletApiRequest();
        _matchedResource = matchedResource;
        _httpInput = _servletChannel.getHttpInput();
        _decodedPathInContext = decodedPathInContext;
        _response =  newServletContextResponse(response);
        _sessionManager = sessionManager;
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
        servletChannel.associate(servletContextRequest);
        return servletContextRequest;
    }

    protected ServletApiRequest newServletApiRequest()
    {
        if (getHttpURI().hasViolations() && !getServletChannel().getServletContextHandler().getServletHandler().isDecodeAmbiguousURIs())
        {
            // TODO we should check if current compliance mode allows all the violations?

            for (UriCompliance.Violation violation : getHttpURI().getViolations())
            {
                if (UriCompliance.AMBIGUOUS_VIOLATIONS.contains(violation))
                    return new ServletApiRequest.AmbiguousURI(this);
            }
        }

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
        return (ServletContextHandler.ServletScopedContext)super.getContext();
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

    private static final Object REMOVED = new Object()
    {
        @Override
        public String toString()
        {
            return "REMOVED";
        }
    };

    private <S> Object getSyntheticAttribute(String name, S source, Function<S, Object> getter)
    {
        Object value = super.getAttribute(name);
        if (value == REMOVED)
            return null;
        if (value != null)
            return value;
        if (source == null)
            return null;
        return getter == null ? null : getter.apply(source);
    }

    private <S> Object removeSyntheticAttribute(String name, S source, Function<S, Object> getter)
    {
        Object o = source != null && getter.apply(source) != null ? super.setAttribute(name, REMOVED) : super.removeAttribute(name);
        return o == REMOVED ? null : o;
    }

    private <S> void updateSyntheticAttributeNameSet(Set<String> names, String name, S source, Function<S, Object> getter)
    {
        Object value = super.getAttribute(name);
        if (value == REMOVED)
            names.remove(name);
        else if (value != null || source != null && getter.apply(source) != null)
            names.add(name);
    }

    private <S> Object setSyntheticAttribute(String name, S source, Function<S, Object> getter, Object value)
    {
        Object o = value == null ? removeSyntheticAttribute(name, source, getter) : super.setAttribute(name, value);
        return o == REMOVED ? null : o;
    }

    @Override
    public Object getAttribute(String name)
    {
        SslSessionData sslSessionData = (name.startsWith(JAKARTA_SERVLET_REQUEST_PREFIX) && super.getAttribute(SslSessionData.ATTRIBUTE) instanceof SslSessionData data) ? data : null;

        return switch (name)
        {
            case JAKARTA_SERVLET_REQUEST_CIPHER_SUITE -> getSyntheticAttribute(name, sslSessionData, SslSessionData::cipherSuite);
            case JAKARTA_SERVLET_REQUEST_KEY_SIZE -> getSyntheticAttribute(name, sslSessionData, SslSessionData::keySize);
            case JAKARTA_SERVLET_REQUEST_SSL_SESSION_ID -> getSyntheticAttribute(name, sslSessionData, SslSessionData::sessionId);
            case JAKARTA_SERVLET_REQUEST_X_509_CERTIFICATE -> getSyntheticAttribute(name, sslSessionData, SslSessionData::peerCertificates);
            case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> getSyntheticAttribute(name, _matchedResource.getResource().getServletHolder(), ServletHolder::getMultipartConfigElement);
            case FormFields.MAX_FIELDS_ATTRIBUTE -> getSyntheticAttribute(name, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormKeys);
            case FormFields.MAX_LENGTH_ATTRIBUTE -> getSyntheticAttribute(name, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormContentSize);
            default -> super.getAttribute(name);
        };
    }

    @Override
    public Object removeAttribute(String name)
    {
        SslSessionData sslSessionData = (name.startsWith(JAKARTA_SERVLET_REQUEST_PREFIX) && super.getAttribute(SslSessionData.ATTRIBUTE) instanceof SslSessionData data) ? data : null;

        return switch (name)
        {
            case JAKARTA_SERVLET_REQUEST_CIPHER_SUITE -> removeSyntheticAttribute(name, sslSessionData, SslSessionData::cipherSuite);
            case JAKARTA_SERVLET_REQUEST_KEY_SIZE -> removeSyntheticAttribute(name, sslSessionData, SslSessionData::keySize);
            case JAKARTA_SERVLET_REQUEST_SSL_SESSION_ID -> removeSyntheticAttribute(name, sslSessionData, SslSessionData::sessionId);
            case JAKARTA_SERVLET_REQUEST_X_509_CERTIFICATE -> removeSyntheticAttribute(name, sslSessionData, SslSessionData::peerCertificates);
            case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> removeSyntheticAttribute(name, _matchedResource.getResource().getServletHolder(), ServletHolder::getMultipartConfigElement);
            case FormFields.MAX_FIELDS_ATTRIBUTE -> removeSyntheticAttribute(name, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormKeys);
            case FormFields.MAX_LENGTH_ATTRIBUTE -> removeSyntheticAttribute(name, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormContentSize);
            default -> super.removeAttribute(name);
        };
    }

    @Override
    public Object setAttribute(String name, Object value)
    {
        SslSessionData sslSessionData = (name.startsWith(JAKARTA_SERVLET_REQUEST_PREFIX) && super.getAttribute(SslSessionData.ATTRIBUTE) instanceof SslSessionData data) ? data : null;
        return switch (name)
        {
            case JAKARTA_SERVLET_REQUEST_CIPHER_SUITE -> setSyntheticAttribute(name, sslSessionData, SslSessionData::cipherSuite, value);
            case JAKARTA_SERVLET_REQUEST_KEY_SIZE -> setSyntheticAttribute(name, sslSessionData, SslSessionData::keySize, value);
            case JAKARTA_SERVLET_REQUEST_SSL_SESSION_ID -> setSyntheticAttribute(name, sslSessionData, SslSessionData::sessionId, value);
            case JAKARTA_SERVLET_REQUEST_X_509_CERTIFICATE -> setSyntheticAttribute(name, sslSessionData, SslSessionData::peerCertificates, value);
            case ServletContextRequest.MULTIPART_CONFIG_ELEMENT -> setSyntheticAttribute(name, _matchedResource.getResource().getServletHolder(), ServletHolder::getMultipartConfigElement, value);
            case FormFields.MAX_FIELDS_ATTRIBUTE -> setSyntheticAttribute(name, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormKeys, value);
            case FormFields.MAX_LENGTH_ATTRIBUTE -> setSyntheticAttribute(name, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormContentSize, value);
            default -> super.setAttribute(name, value);
        };
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        Set<String> names = new HashSet<>(super.getAttributeNameSet());
        SslSessionData sslSessionData = super.getAttribute(SslSessionData.ATTRIBUTE) instanceof SslSessionData data ? data : null;
        updateSyntheticAttributeNameSet(names, JAKARTA_SERVLET_REQUEST_CIPHER_SUITE, sslSessionData, SslSessionData::cipherSuite);
        updateSyntheticAttributeNameSet(names, JAKARTA_SERVLET_REQUEST_KEY_SIZE, sslSessionData, SslSessionData::keySize);
        updateSyntheticAttributeNameSet(names, JAKARTA_SERVLET_REQUEST_SSL_SESSION_ID, sslSessionData, SslSessionData::sessionId);
        updateSyntheticAttributeNameSet(names, JAKARTA_SERVLET_REQUEST_X_509_CERTIFICATE, sslSessionData, SslSessionData::peerCertificates);
        updateSyntheticAttributeNameSet(names, ServletContextRequest.MULTIPART_CONFIG_ELEMENT, _matchedResource.getResource().getServletHolder(), ServletHolder::getMultipartConfigElement);
        updateSyntheticAttributeNameSet(names, FormFields.MAX_FIELDS_ATTRIBUTE, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormKeys);
        updateSyntheticAttributeNameSet(names, FormFields.MAX_LENGTH_ATTRIBUTE, getServletContext().getServletContextHandler(), ServletContextHandler::getMaxFormContentSize);
        return names;
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
