//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.client;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Authentication.HeaderInfo;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.Listener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AbstractAuthentication;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.client.api.Authentication.ANY_REALM;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientAuthenticationTest extends AbstractHttpClientServerTest
{
    private String realm = "TestRealm";

    public void startBasic(Scenario scenario, Handler handler) throws Exception
    {
        startBasic(scenario, handler, null);
    }

    public void startBasic(Scenario scenario, Handler handler, Charset charset) throws Exception
    {
        BasicAuthenticator authenticator = new BasicAuthenticator();
        if (charset != null)
            authenticator.setCharset(charset);
        start(scenario, authenticator, handler);
    }

    public void startDigest(Scenario scenario, Handler handler) throws Exception
    {
        start(scenario, new DigestAuthenticator(), handler);
    }

    private void start(Scenario scenario, Authenticator authenticator, Handler handler) throws Exception
    {
        server = new Server();
        File realmFile = MavenTestingUtils.getTestResourceFile("realm.properties");
        LoginService loginService = new HashLoginService(realm, realmFile.getAbsolutePath());
        server.addBean(loginService);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();

        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"**"}); //allow any authenticated user
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secure");
        mapping.setConstraint(constraint);

        securityHandler.addConstraintMapping(mapping);
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(loginService);

        securityHandler.setHandler(handler);
        start(scenario, securityHandler);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicAuthentication(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        testAuthentication(scenario, new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicEmptyRealm(Scenario scenario) throws Exception
    {
        realm = "";
        startBasic(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        testAuthentication(scenario, new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicAnyRealm(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        testAuthentication(scenario, new BasicAuthentication(uri, ANY_REALM, "basic", "basic"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicWithUTF8Password(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler(), StandardCharsets.UTF_8);
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        testAuthentication(scenario, new BasicAuthentication(uri, realm, "basic_utf8", "\u20AC"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDigestAuthentication(Scenario scenario) throws Exception
    {
        startDigest(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        testAuthentication(scenario, new DigestAuthentication(uri, realm, "digest", "digest"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDigestAnyRealm(Scenario scenario) throws Exception
    {
        startDigest(scenario, new EmptyServerHandler());
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        testAuthentication(scenario, new DigestAuthentication(uri, ANY_REALM, "digest", "digest"));
    }

    private void testAuthentication(Scenario scenario, Authentication authentication) throws Exception
    {
        AuthenticationStore authenticationStore = client.getAuthenticationStore();

        AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(1));
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request without Authentication causes a 401
        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);

        authenticationStore.addAuthentication(authentication);

        requests.set(new CountDownLatch(2));
        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Request with authentication causes a 401 (no previous successful authentication) + 200
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);

        requests.set(new CountDownLatch(1));
        requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        // Further requests do not trigger 401 because there is a previous successful authentication
        // Remove existing header to be sure it's added by the implementation
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicAuthenticationThenRedirect(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler()
        {
            private final AtomicInteger requests = new AtomicInteger();

            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                int r = requests.incrementAndGet();
                if (r == 1)
                {
                    String path = request.getRequestURI() + "/" + r;
                    response.sendRedirect(URIUtil.newURI(scenario.getScheme(), request.getServerName(), request.getServerPort(), path, null));
                }
            }
        });

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        CountDownLatch requests = new CountDownLatch(3);
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/secure")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRedirectThenBasicAuthentication(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().endsWith("/redirect"))
                    response.sendRedirect(URIUtil.newURI(scenario.getScheme(), request.getServerName(), request.getServerPort(), "/secure", null));
            }
        });

        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        CountDownLatch requests = new CountDownLatch(3);
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/redirect")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicAuthenticationWithAuthenticationRemoved(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(2));
        Request.Listener.Adapter requestListener = new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.get().countDown();
            }
        };
        client.getRequestListeners().add(requestListener);

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        authenticationStore.removeAuthentication(authentication);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        Authentication.Result result = authenticationStore.findAuthenticationResult(request.getURI());
        assertNotNull(result);
        authenticationStore.removeAuthenticationResult(result);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());
        assertTrue(requests.get().await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testBasicAuthenticationWithWrongPassword(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "wrong");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme()).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        assertNotNull(response);
        assertEquals(401, response.getStatus());

        Authentication.Result authenticationResult = authenticationStore.findAuthenticationResult(uri);
        assertNull(authenticationResult);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAuthenticationThrowsException(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        // Request without Authentication would cause a 401,
        // but the client will throw an exception trying to
        // send the credentials to the server.
        String cause = "thrown_explicitly_by_test";
        client.getAuthenticationStore().addAuthentication(new Authentication()
        {
            @Override
            public boolean matches(String type, URI uri, String realm)
            {
                return true;
            }

            @Override
            public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
            {
                throw new RuntimeException(cause);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/secure")
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isFailed());
                assertEquals(cause, result.getFailure().getMessage());
                latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPreemptedAuthentication(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        authenticationStore.addAuthenticationResult(new BasicAuthentication.BasicResult(uri, "basic", "basic"));

        AtomicInteger requests = new AtomicInteger();
        client.getRequestListeners().add(new Request.Listener.Adapter()
        {
            @Override
            public void onSuccess(Request request)
            {
                requests.incrementAndGet();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/secure")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testNonReproducibleContent(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        CountDownLatch resultLatch = new CountDownLatch(1);
        byte[] data = new byte[]{'h', 'e', 'l', 'l', 'o'};
        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.wrap(data))
        {
            @Override
            public boolean isReproducible()
            {
                return false;
            }
        };
        Request request = client.newRequest(uri)
            .path("/secure")
            .content(content);
        request.send(result ->
        {
            if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.UNAUTHORIZED_401)
                resultLatch.countDown();
        });

        content.close();

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRequestFailsAfterResponse(Scenario scenario) throws Exception
    {
        startBasic(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                IO.readBytes(jettyRequest.getInputStream());
            }
        });

        CountDownLatch authLatch = new CountDownLatch(1);
        client.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
        client.getProtocolHandlers().put(new WWWAuthenticationProtocolHandler(client)
        {
            @Override
            public Listener getResponseListener()
            {
                Response.Listener listener = super.getResponseListener();
                return new Listener.Adapter()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        authLatch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        listener.onComplete(result);
                    }
                };
            }
        });

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());

        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        AtomicBoolean fail = new AtomicBoolean(true);
        GeneratingContentProvider content = new GeneratingContentProvider(index ->
        {
            switch (index)
            {
                case 0:
                    return ByteBuffer.wrap(new byte[]{'h', 'e', 'l', 'l', 'o'});
                case 1:
                    return ByteBuffer.wrap(new byte[]{'w', 'o', 'r', 'l', 'd'});
                case 2:
                    if (fail.compareAndSet(true, false))
                    {
                        // Wait for the 401 response to arrive
                        try
                        {
                            authLatch.await();
                        }
                        catch (InterruptedException ignored)
                        {
                        }

                        // Trigger request failure.
                        throw new RuntimeException();
                    }
                    else
                    {
                        return null;
                    }

                default:
                    throw new IllegalStateException();
            }
        });
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/secure")
            .content(content)
            .onResponseSuccess(r -> authLatch.countDown())
            .send(result ->
            {
                if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                    resultLatch.countDown();
            });

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testInfiniteAuthentication(Scenario scenario) throws Exception
    {
        String authType = "Authenticate";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                // Always reply with a 401 to see if the client
                // can handle an infinite authentication loop.
                response.setStatus(HttpStatus.UNAUTHORIZED_401);
                response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), authType);
            }
        });

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scenario.getScheme() + "://localhost:" + connector.getLocalPort());
        authenticationStore.addAuthentication(new AbstractAuthentication(uri, Authentication.ANY_REALM)
        {
            @Override
            public String getType()
            {
                return authType;
            }

            @Override
            public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
            {
                return new Result()
                {
                    @Override
                    public URI getURI()
                    {
                        return uri;
                    }

                    @Override
                    public void apply(Request request)
                    {
                    }
                };
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .send();

        assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());
    }

    @Test
    public void testTestHeaderInfoParsing()
    {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);

        HeaderInfo headerInfo = aph.getHeaderInfo("Digest realm=\"thermostat\", qop=\"auth\", nonce=\"1523430383\"").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        assertEquals("auth", headerInfo.getParameter("qop"));
        assertEquals("thermostat", headerInfo.getParameter("realm"));
        assertEquals("1523430383", headerInfo.getParameter("nonce"));

        headerInfo = aph.getHeaderInfo("Digest qop=\"auth\", realm=\"thermostat\", nonce=\"1523430383\"").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        assertEquals("auth", headerInfo.getParameter("qop"));
        assertEquals("thermostat", headerInfo.getParameter("realm"));
        assertEquals("1523430383", headerInfo.getParameter("nonce"));

        headerInfo = aph.getHeaderInfo("Digest qop=\"auth\", nonce=\"1523430383\", realm=\"thermostat\"").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        assertEquals("auth", headerInfo.getParameter("qop"));
        assertEquals("thermostat", headerInfo.getParameter("realm"));
        assertEquals("1523430383", headerInfo.getParameter("nonce"));

        headerInfo = aph.getHeaderInfo("Digest qop=\"auth\", nonce=\"1523430383\"").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        assertEquals("auth", headerInfo.getParameter("qop"));
        assertNull(headerInfo.getParameter("realm"));
        assertEquals("1523430383", headerInfo.getParameter("nonce"));

        // test multiple authentications
        List<HeaderInfo> headerInfoList = aph.getHeaderInfo("Digest qop=\"auth\", realm=\"thermostat\", nonce=\"1523430383\", " +
                "Digest realm=\"thermostat2\", qop=\"auth2\", nonce=\"4522530354\", " +
                "Digest qop=\"auth3\", nonce=\"9523570528\", realm=\"thermostat3\", " +
                "Digest qop=\"auth4\", nonce=\"3526435321\"");

        assertTrue(headerInfoList.get(0).getType().equalsIgnoreCase("Digest"));
        assertEquals("auth", headerInfoList.get(0).getParameter("qop"));
        assertEquals("thermostat", headerInfoList.get(0).getParameter("realm"));
        assertEquals("1523430383", headerInfoList.get(0).getParameter("nonce"));

        assertTrue(headerInfoList.get(1).getType().equalsIgnoreCase("Digest"));
        assertEquals("auth2", headerInfoList.get(1).getParameter("qop"));
        assertEquals("thermostat2", headerInfoList.get(1).getParameter("realm"));
        assertEquals("4522530354", headerInfoList.get(1).getParameter("nonce"));

        assertTrue(headerInfoList.get(2).getType().equalsIgnoreCase("Digest"));
        assertEquals("auth3", headerInfoList.get(2).getParameter("qop"));
        assertEquals("thermostat3", headerInfoList.get(2).getParameter("realm"));
        assertEquals("9523570528", headerInfoList.get(2).getParameter("nonce"));

        assertTrue(headerInfoList.get(3).getType().equalsIgnoreCase("Digest"));
        assertEquals("auth4", headerInfoList.get(3).getParameter("qop"));
        assertNull(headerInfoList.get(3).getParameter("realm"));
        assertEquals("3526435321", headerInfoList.get(3).getParameter("nonce"));

        List<HeaderInfo> headerInfos = aph.getHeaderInfo("Newauth realm=\"apps\", type=1, title=\"Login to \\\"apps\\\"\", Basic realm=\"simple\"");
        assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Newauth"));
        assertEquals("apps", headerInfos.get(0).getParameter("realm"));
        assertEquals("1", headerInfos.get(0).getParameter("type"));

        assertEquals(headerInfos.get(0).getParameter("title"), "Login to \"apps\"");

        assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Basic"));
        assertEquals("simple", headerInfos.get(1).getParameter("realm"));
    }

    @Test
    public void testTestHeaderInfoParsingUnusualCases()
    {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);

        HeaderInfo headerInfo = aph.getHeaderInfo("Scheme").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Scheme"));
        assertNull(headerInfo.getParameter("realm"));

        List<HeaderInfo> headerInfos = aph.getHeaderInfo("Scheme1    ,    Scheme2        ,      Scheme3");
        assertEquals(3, headerInfos.size());
        assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme1"));
        assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Scheme2"));
        assertTrue(headerInfos.get(2).getType().equalsIgnoreCase("Scheme3"));

        headerInfo = aph.getHeaderInfo("Scheme name=\"value\", other=\"value2\"").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Scheme"));
        assertEquals("value", headerInfo.getParameter("name"));
        assertEquals("value2", headerInfo.getParameter("other"));

        headerInfo = aph.getHeaderInfo("Scheme   name   = value   , other   =  \"value2\"    ").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Scheme"));
        assertEquals("value", headerInfo.getParameter("name"));
        assertEquals("value2", headerInfo.getParameter("other"));

        headerInfos = aph.getHeaderInfo(", , , ,  ,,,Scheme name=value, ,,Scheme2   name=value2,,  ,,");
        assertEquals(headerInfos.size(), 2);
        assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme"));
        assertEquals("value", headerInfos.get(0).getParameter("nAmE"));
        assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Scheme2"));

        headerInfos = aph.getHeaderInfo("Scheme name=value, Scheme2   name=value2");
        assertEquals(headerInfos.size(), 2);
        assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme"));
        assertEquals("value", headerInfos.get(0).getParameter("nAmE"));
        assertThat(headerInfos.get(1).getType(), equalToIgnoringCase("Scheme2"));

        assertEquals("value2", headerInfos.get(1).getParameter("nAmE"));

        headerInfos = aph.getHeaderInfo("Scheme ,   ,, ,, name=value, Scheme2 name=value2");
        assertEquals(headerInfos.size(), 2);
        assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme"));
        assertEquals("value", headerInfos.get(0).getParameter("name"));
        assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Scheme2"));
        assertEquals("value2", headerInfos.get(1).getParameter("name"));

        //Negotiate with base64 Content
        headerInfo = aph.getHeaderInfo("Negotiate TlRMTVNTUAABAAAAB4IIogAAAAAAAAAAAAAAAAAAAAAFAs4OAAAADw==").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Negotiate"));
        assertEquals("TlRMTVNTUAABAAAAB4IIogAAAAAAAAAAAAAAAAAAAAAFAs4OAAAADw==", headerInfo.getBase64());

        headerInfos = aph.getHeaderInfo("Negotiate TlRMTVNTUAABAAAAAAAAAFAs4OAAAADw==, " +
                "Negotiate YIIJvwYGKwYBBQUCoIIJszCCCa+gJDAi=");
        assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Negotiate"));
        assertEquals("TlRMTVNTUAABAAAAAAAAAFAs4OAAAADw==", headerInfos.get(0).getBase64());

        assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Negotiate"));
        assertEquals("YIIJvwYGKwYBBQUCoIIJszCCCa+gJDAi=", headerInfos.get(1).getBase64());
    }

    @Test
    public void testEqualsInParam()
    {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);
        HeaderInfo headerInfo;

        headerInfo = aph.getHeaderInfo("Digest realm=\"=the=rmo=stat=\", qop=\"=a=u=t=h=\", nonce=\"=1523430383=\"").get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        assertEquals("=a=u=t=h=", headerInfo.getParameter("qop"));
        assertEquals("=the=rmo=stat=", headerInfo.getParameter("realm"));
        assertEquals("=1523430383=", headerInfo.getParameter("nonce"));

        // test multiple authentications
        List<HeaderInfo> headerInfoList = aph.getHeaderInfo("Digest qop=\"=au=th=\", realm=\"=ther=mostat=\", nonce=\"=152343=0383=\", " +
                "Digest realm=\"=thermostat2\", qop=\"=auth2\", nonce=\"=4522530354\", " +
                "Digest qop=\"auth3=\", nonce=\"9523570528=\", realm=\"thermostat3=\", ");

        assertTrue(headerInfoList.get(0).getType().equalsIgnoreCase("Digest"));
        assertEquals("=au=th=", headerInfoList.get(0).getParameter("qop"));
        assertEquals("=ther=mostat=", headerInfoList.get(0).getParameter("realm"));
        assertEquals("=152343=0383=", headerInfoList.get(0).getParameter("nonce"));

        assertTrue(headerInfoList.get(1).getType().equalsIgnoreCase("Digest"));
        assertEquals("=auth2", headerInfoList.get(1).getParameter("qop"));
        assertEquals("=thermostat2", headerInfoList.get(1).getParameter("realm"));
        assertEquals("=4522530354", headerInfoList.get(1).getParameter("nonce"));

        assertTrue(headerInfoList.get(2).getType().equalsIgnoreCase("Digest"));
        assertEquals("auth3=", headerInfoList.get(2).getParameter("qop"));
        assertEquals("thermostat3=", headerInfoList.get(2).getParameter("realm"));
        assertEquals("9523570528=", headerInfoList.get(2).getParameter("nonce"));
    }

    @Test
    public void testSingleChallengeLooksLikeMultipleChallenges()
    {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);
        List<HeaderInfo> headerInfoList = aph.getHeaderInfo("Digest param=\",f \"");
        assertEquals(1, headerInfoList.size());

        headerInfoList = aph.getHeaderInfo("Digest realm=\"thermostat\", qop=\",Digest realm=hello\", nonce=\"1523430383=\"");
        assertEquals(1, headerInfoList.size());

        HeaderInfo headerInfo = headerInfoList.get(0);
        assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        assertEquals(",Digest realm=hello", headerInfo.getParameter("qop"));
        assertEquals("thermostat", headerInfo.getParameter("realm"));
        assertEquals(headerInfo.getParameter("nonce"), "1523430383=");
    }

    private static class GeneratingContentProvider implements ContentProvider
    {
        private static final ByteBuffer DONE = ByteBuffer.allocate(0);

        private final IntFunction<ByteBuffer> generator;

        private GeneratingContentProvider(IntFunction<ByteBuffer> generator)
        {
            this.generator = generator;
        }

        @Override
        public long getLength()
        {
            return -1;
        }

        @Override
        public boolean isReproducible()
        {
            return true;
        }

        @Override
        public Iterator<ByteBuffer> iterator()
        {
            return new Iterator<ByteBuffer>()
            {
                private int index;
                public ByteBuffer current;

                @Override
                @SuppressWarnings("ReferenceEquality")
                public boolean hasNext()
                {
                    if (current == null)
                    {
                        current = generator.apply(index++);
                        if (current == null)
                            current = DONE;
                    }
                    return current != DONE;
                }

                @Override
                public ByteBuffer next()
                {
                    ByteBuffer result = current;
                    current = null;
                    if (result == null)
                        throw new NoSuchElementException();
                    return result;
                }
            };
        }
    }
}
