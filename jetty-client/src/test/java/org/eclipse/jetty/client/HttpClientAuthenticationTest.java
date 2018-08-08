//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import javax.servlet.ServletException;
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
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.DigestAuthentication;
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
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientAuthenticationTest extends AbstractHttpClientServerTest
{
    private String realm = "TestRealm";

    public HttpClientAuthenticationTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    public void startBasic(Handler handler) throws Exception
    {
        start(new BasicAuthenticator(), handler);
    }

    public void startDigest(Handler handler) throws Exception
    {
        start(new DigestAuthenticator(), handler);
    }

    private void start(Authenticator authenticator, Handler handler) throws Exception
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
        start(securityHandler);
    }

    @Test
    public void test_BasicAuthentication() throws Exception
    {
        startBasic(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @Test
    public void test_BasicEmptyRealm() throws Exception
    {
        realm = "";
        startBasic(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new BasicAuthentication(uri, realm, "basic", "basic"));
    }

    @Test
    public void test_BasicAnyRealm() throws Exception
    {
        startBasic(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new BasicAuthentication(uri, Authentication.ANY_REALM, "basic", "basic"));
    }

    @Test
    public void test_DigestAuthentication() throws Exception
    {
        startDigest(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new DigestAuthentication(uri, realm, "digest", "digest"));
    }

    @Test
    public void test_DigestAnyRealm() throws Exception
    {
        startDigest(new EmptyServerHandler());
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        test_Authentication(new DigestAuthentication(uri, Authentication.ANY_REALM, "digest", "digest"));
    }

    private void test_Authentication(Authentication authentication) throws Exception
    {
        AuthenticationStore authenticationStore = client.getAuthenticationStore();

        final AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(1));
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
        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
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
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
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
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @Test
    public void test_BasicAuthentication_ThenRedirect() throws Exception
    {
        startBasic(new AbstractHandler()
        {
            private final AtomicInteger requests = new AtomicInteger();

            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (requests.incrementAndGet() == 1)
                    response.sendRedirect(URIUtil.newURI(scheme, request.getServerName(), request.getServerPort(), request.getRequestURI(), null));
            }
        });

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        final CountDownLatch requests = new CountDownLatch(3);
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
                .scheme(scheme)
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @Test
    public void test_Redirect_ThenBasicAuthentication() throws Exception
    {
        startBasic(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().endsWith("/redirect"))
                    response.sendRedirect(URIUtil.newURI(scheme, request.getServerName(), request.getServerPort(), "/secure", null));
            }
        });

        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "basic", "basic"));

        final CountDownLatch requests = new CountDownLatch(3);
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
                .scheme(scheme)
                .path("/redirect")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.await(5, TimeUnit.SECONDS));
        client.getRequestListeners().remove(requestListener);
    }

    @Test
    public void test_BasicAuthentication_WithAuthenticationRemoved() throws Exception
    {
        startBasic(new EmptyServerHandler());

        final AtomicReference<CountDownLatch> requests = new AtomicReference<>(new CountDownLatch(2));
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
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "basic");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        authenticationStore.removeAuthentication(authentication);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));

        Authentication.Result result = authenticationStore.findAuthenticationResult(request.getURI());
        Assert.assertNotNull(result);
        authenticationStore.removeAuthenticationResult(result);

        requests.set(new CountDownLatch(1));
        request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.getStatus());
        Assert.assertTrue(requests.get().await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_BasicAuthentication_WithWrongPassword() throws Exception
    {
        startBasic(new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
        BasicAuthentication authentication = new BasicAuthentication(uri, realm, "basic", "wrong");
        authenticationStore.addAuthentication(authentication);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scheme).path("/secure");
        ContentResponse response = request.timeout(5, TimeUnit.SECONDS).send();
        Assert.assertNotNull(response);
        Assert.assertEquals(401, response.getStatus());
    }

    @Test
    public void test_Authentication_ThrowsException() throws Exception
    {
        startBasic(new EmptyServerHandler());

        // Request without Authentication would cause a 401,
        // but the client will throw an exception trying to
        // send the credentials to the server.
        final String cause = "thrown_explicitly_by_test";
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

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        Assert.assertEquals(cause, result.getFailure().getMessage());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_PreemptedAuthentication() throws Exception
    {
        startBasic(new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
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
                .scheme(scheme)
                .path("/secure")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(1, requests.get());
    }

    @Test
    public void test_NonReproducibleContent() throws Exception
    {
        startBasic(new EmptyServerHandler());

        AuthenticationStore authenticationStore = client.getAuthenticationStore();
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());
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

        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    
    @Test
    public void test_RequestFailsAfterResponse() throws Exception
    {        
        startBasic(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException, ServletException
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
        URI uri = URI.create(scheme + "://localhost:" + connector.getLocalPort());

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
                        catch(InterruptedException e)
                        {}
                        
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
                .scheme(scheme)
                .path("/secure")
                .content(content)
                .onResponseSuccess(r->authLatch.countDown())
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                        resultLatch.countDown();
                });

        Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
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
    
    @Test
    public void testTestHeaderInfoParsing() {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);
        
        HeaderInfo headerInfo = aph.getHeaderInfo("Digest realm=\"thermostat\", qop=\"auth\", nonce=\"1523430383\"").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfo.getParameter("qop").equals("auth"));
        Assert.assertTrue(headerInfo.getParameter("realm").equals("thermostat"));
        Assert.assertTrue(headerInfo.getParameter("nonce").equals("1523430383"));
        
        headerInfo = aph.getHeaderInfo("Digest qop=\"auth\", realm=\"thermostat\", nonce=\"1523430383\"").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfo.getParameter("qop").equals("auth"));
        Assert.assertTrue(headerInfo.getParameter("realm").equals("thermostat"));
        Assert.assertTrue(headerInfo.getParameter("nonce").equals("1523430383"));
        
        headerInfo = aph.getHeaderInfo("Digest qop=\"auth\", nonce=\"1523430383\", realm=\"thermostat\"").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfo.getParameter("qop").equals("auth"));
        Assert.assertTrue(headerInfo.getParameter("realm").equals("thermostat"));
        Assert.assertTrue(headerInfo.getParameter("nonce").equals("1523430383"));
        
        headerInfo = aph.getHeaderInfo("Digest qop=\"auth\", nonce=\"1523430383\"").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfo.getParameter("qop").equals("auth"));
        Assert.assertTrue(headerInfo.getParameter("realm") == null);
        Assert.assertTrue(headerInfo.getParameter("nonce").equals("1523430383"));
        
        
        // test multiple authentications
        List<HeaderInfo> headerInfoList = aph.getHeaderInfo("Digest qop=\"auth\", realm=\"thermostat\", nonce=\"1523430383\", "
                                                          + "Digest realm=\"thermostat2\", qop=\"auth2\", nonce=\"4522530354\", "
                                                          + "Digest qop=\"auth3\", nonce=\"9523570528\", realm=\"thermostat3\", "
                                                          + "Digest qop=\"auth4\", nonce=\"3526435321\"");
        
        Assert.assertTrue(headerInfoList.get(0).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(0).getParameter("qop").equals("auth"));
        Assert.assertTrue(headerInfoList.get(0).getParameter("realm").equals("thermostat"));
        Assert.assertTrue(headerInfoList.get(0).getParameter("nonce").equals("1523430383"));
        
        Assert.assertTrue(headerInfoList.get(1).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(1).getParameter("qop").equals("auth2"));
        Assert.assertTrue(headerInfoList.get(1).getParameter("realm").equals("thermostat2"));
        Assert.assertTrue(headerInfoList.get(1).getParameter("nonce").equals("4522530354"));
        
        Assert.assertTrue(headerInfoList.get(2).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(2).getParameter("qop").equals("auth3"));
        Assert.assertTrue(headerInfoList.get(2).getParameter("realm").equals("thermostat3"));
        Assert.assertTrue(headerInfoList.get(2).getParameter("nonce").equals("9523570528"));
        
        Assert.assertTrue(headerInfoList.get(3).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(3).getParameter("qop").equals("auth4"));
        Assert.assertTrue(headerInfoList.get(3).getParameter("realm") == null);
        Assert.assertTrue(headerInfoList.get(3).getParameter("nonce").equals("3526435321"));
        
        List<HeaderInfo> headerInfos = aph.getHeaderInfo("Newauth realm=\"apps\", type=1, title=\"Login to \\\"apps\\\"\", Basic realm=\"simple\"");
        Assert.assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Newauth"));
        Assert.assertTrue(headerInfos.get(0).getParameter("realm").equals("apps"));
        Assert.assertTrue(headerInfos.get(0).getParameter("type").equals("1"));
        Assert.assertThat(headerInfos.get(0).getParameter("title"), Matchers.equalTo("Login to \"apps\""));
        Assert.assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Basic"));
        Assert.assertTrue(headerInfos.get(1).getParameter("realm").equals("simple"));        
    }
    
    @Test
    public void testTestHeaderInfoParsingUnusualCases() {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);
        
        HeaderInfo headerInfo = aph.getHeaderInfo("Scheme").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Scheme"));
        Assert.assertTrue(headerInfo.getParameter("realm") == null);
        
        List<HeaderInfo> headerInfos = aph.getHeaderInfo("Scheme1    ,    Scheme2        ,      Scheme3");
        Assert.assertEquals(3, headerInfos.size());
        Assert.assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme1"));
        Assert.assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Scheme2"));
        Assert.assertTrue(headerInfos.get(2).getType().equalsIgnoreCase("Scheme3"));
        
        headerInfo = aph.getHeaderInfo("Scheme name=\"value\", other=\"value2\"").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Scheme"));
        Assert.assertTrue(headerInfo.getParameter("name").equals("value"));
        Assert.assertTrue(headerInfo.getParameter("other").equals("value2"));
        
        headerInfo = aph.getHeaderInfo("Scheme   name   = value   , other   =  \"value2\"    ").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Scheme"));
        Assert.assertTrue(headerInfo.getParameter("name").equals("value"));
        Assert.assertTrue(headerInfo.getParameter("other").equals("value2"));
        
        headerInfos = aph.getHeaderInfo(", , , ,  ,,,Scheme name=value, ,,Scheme2   name=value2,,  ,,");
        Assert.assertEquals(headerInfos.size(), 2);
        Assert.assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme"));
        Assert.assertTrue(headerInfos.get(0).getParameter("nAmE").equals("value"));
        Assert.assertThat(headerInfos.get(1).getType(), Matchers.equalToIgnoringCase("Scheme2"));
        Assert.assertTrue(headerInfos.get(1).getParameter("nAmE").equals("value2"));
        
        headerInfos = aph.getHeaderInfo("Scheme ,   ,, ,, name=value, Scheme2 name=value2");
        Assert.assertEquals(headerInfos.size(), 2);
        Assert.assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Scheme"));
        Assert.assertTrue(headerInfos.get(0).getParameter("name").equals("value"));
        Assert.assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Scheme2"));
        Assert.assertTrue(headerInfos.get(1).getParameter("name").equals("value2"));
        
        //Negotiate with base64 Content
        headerInfo = aph.getHeaderInfo("Negotiate TlRMTVNTUAABAAAAB4IIogAAAAAAAAAAAAAAAAAAAAAFAs4OAAAADw==").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Negotiate"));
        Assert.assertTrue(headerInfo.getBase64().equals("TlRMTVNTUAABAAAAB4IIogAAAAAAAAAAAAAAAAAAAAAFAs4OAAAADw=="));
        
        headerInfos = aph.getHeaderInfo("Negotiate TlRMTVNTUAABAAAAAAAAAFAs4OAAAADw==, "
                                    +  "Negotiate YIIJvwYGKwYBBQUCoIIJszCCCa+gJDAi=");
        Assert.assertTrue(headerInfos.get(0).getType().equalsIgnoreCase("Negotiate"));
        Assert.assertTrue(headerInfos.get(0).getBase64().equals("TlRMTVNTUAABAAAAAAAAAFAs4OAAAADw=="));
        
        Assert.assertTrue(headerInfos.get(1).getType().equalsIgnoreCase("Negotiate"));
        Assert.assertTrue(headerInfos.get(1).getBase64().equals("YIIJvwYGKwYBBQUCoIIJszCCCa+gJDAi="));
    }



    @Test
    public void testEqualsInParam()
    {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);
        HeaderInfo headerInfo;

        headerInfo = aph.getHeaderInfo("Digest realm=\"=the=rmo=stat=\", qop=\"=a=u=t=h=\", nonce=\"=1523430383=\"").get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfo.getParameter("qop").equals("=a=u=t=h="));
        Assert.assertTrue(headerInfo.getParameter("realm").equals("=the=rmo=stat="));
        Assert.assertTrue(headerInfo.getParameter("nonce").equals("=1523430383="));


        // test multiple authentications
        List<HeaderInfo> headerInfoList = aph.getHeaderInfo("Digest qop=\"=au=th=\", realm=\"=ther=mostat=\", nonce=\"=152343=0383=\", "
                + "Digest realm=\"=thermostat2\", qop=\"=auth2\", nonce=\"=4522530354\", "
                + "Digest qop=\"auth3=\", nonce=\"9523570528=\", realm=\"thermostat3=\", ");

        Assert.assertTrue(headerInfoList.get(0).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(0).getParameter("qop").equals("=au=th="));
        Assert.assertTrue(headerInfoList.get(0).getParameter("realm").equals("=ther=mostat="));
        Assert.assertTrue(headerInfoList.get(0).getParameter("nonce").equals("=152343=0383="));

        Assert.assertTrue(headerInfoList.get(1).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(1).getParameter("qop").equals("=auth2"));
        Assert.assertTrue(headerInfoList.get(1).getParameter("realm").equals("=thermostat2"));
        Assert.assertTrue(headerInfoList.get(1).getParameter("nonce").equals("=4522530354"));

        Assert.assertTrue(headerInfoList.get(2).getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfoList.get(2).getParameter("qop").equals("auth3="));
        Assert.assertTrue(headerInfoList.get(2).getParameter("realm").equals("thermostat3="));
        Assert.assertTrue(headerInfoList.get(2).getParameter("nonce").equals("9523570528="));
    }

    @Test
    public void testSingleChallangeLooksLikeMultipleChallenge()
    {
        AuthenticationProtocolHandler aph = new WWWAuthenticationProtocolHandler(client);
        List<HeaderInfo> headerInfoList = aph.getHeaderInfo("Digest param=\",f \"");
        Assert.assertEquals(1, headerInfoList.size());



        headerInfoList = aph.getHeaderInfo("Digest realm=\"thermostat\", qop=\",Digest realm=hello\", nonce=\"1523430383=\"");
        Assert.assertEquals(1, headerInfoList.size());

        HeaderInfo headerInfo = headerInfoList.get(0);
        Assert.assertTrue(headerInfo.getType().equalsIgnoreCase("Digest"));
        Assert.assertTrue(headerInfo.getParameter("qop").equals(",Digest realm=hello"));
        Assert.assertTrue(headerInfo.getParameter("realm").equals("thermostat"));
        Assert.assertThat(headerInfo.getParameter("nonce"), Matchers.is("1523430383="));
    }
}
