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

package org.eclipse.jetty.ee11.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.DigestAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.PathRequestContent;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee11.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DigestPostTest
{
    private static final String MESSAGE = """
        0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789
        9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 9876543210
        1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 1234567890
        0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 0987654321
        abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz
        ABCDEFGHIJKLMNOPQRSTUVWXYZ ABCDEFGHIJKLMNOPQRSTUVWXYZ ABCDEFGHIJKLMNOPQRSTUVWXYZ
        Now is the time for all good men to come to the aid of the party.
        How now brown cow.
        The quick brown fox jumped over the lazy dog.
        """;

    private final String _user = "testuser";
    private final String _password = "password";
    private final String _realm = "testrealm";
    private final String nc = "00000001";
    private final String cnonce = "CLIENT_NONCE";
    private Server _server;
    private ServerConnector _connector;
    private PostServlet _servlet;
    private DigestAuthenticator _authenticator;

    @BeforeEach
    public void startServer() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SECURITY);
        context.setContextPath("/test");
        _servlet = new PostServlet();
        context.addServlet(_servlet, "/");

        TestLoginService realm = new TestLoginService(_realm);
        realm.putUser(_user, new Password(_password), new String[]{"testRole"});
        _server.addBean(realm);

        ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
        _authenticator = new DigestAuthenticator();
        security.setAuthenticator(_authenticator);
        security.setLoginService(realm);

        Constraint constraint = new Constraint.Builder()
            .name("SecureTest")
            .roles("testRole")
            .build();
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        security.setConstraintMappings(Collections.singletonList(mapping));

        _server.setHandler(new Handler.Sequence(context, new DefaultHandler()));

        _server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testServerDirectlyHTTP10() throws Exception
    {
        try (SocketChannel socket1 = SocketChannel.open(new InetSocketAddress("localhost", _connector.getLocalPort())))
        {
            _servlet._received = null;
            String request = """
                POST /test/ HTTP/1.0
                Host: localhost
                Content-Length: %d
                
                %s\
                """.formatted(MESSAGE.length(), MESSAGE);
            socket1.write(UTF_8.encode(request));

            HttpTester.Response response = HttpTester.parseResponse(socket1);

            assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());
            assertNull(_servlet._received);

            String authenticate = response.get(HttpHeader.WWW_AUTHENTICATE);
            String nonce = nonceFrom(authenticate);
            assertNotNull(nonce);

            String rsp = newResponse("POST", "/test/", nonce);
            String digest = """
                Digest username="%s", realm="%s", nonce="%s", uri="/test/", algorithm=%s, response="%s", qop=auth, nc=%s, cnonce="%s"\
                """.formatted(_user, _realm, nonce, _authenticator.getAlgorithm(), rsp, nc, cnonce);
            
            try (SocketChannel socket2 = SocketChannel.open(new InetSocketAddress("localhost", _connector.getLocalPort())))
            {
                _servlet._received = null;
                request = """
                    POST /test/ HTTP/1.0
                    Host: localhost
                    Content-Length: %d
                    Authorization: %s
                    
                    %s\
                    """.formatted(MESSAGE.length(), digest, MESSAGE);
                socket2.write(UTF_8.encode(request));

                response = HttpTester.parseResponse(socket2);

                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals(MESSAGE, _servlet._received);
            }
        }
    }

    @Test
    public void testServerDirectlyHTTP11() throws Exception
    {
        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", _connector.getLocalPort())))
        {
            _servlet._received = null;
            String request = """
                POST /test/ HTTP/1.1
                Host: localhost
                Content-Length: %d
                
                %s\
                """.formatted(MESSAGE.length(), MESSAGE);
            socket.write(UTF_8.encode(request));

            HttpTester.Response response = HttpTester.parseResponse(socket);

            assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());
            assertNull(_servlet._received);

            String authenticate = response.get(HttpHeader.WWW_AUTHENTICATE);
            String nonce = nonceFrom(authenticate);
            assertNotNull(nonce);

            String rsp = newResponse("POST", "/test/", nonce);
            String digest = """
                Digest username="%s", realm="%s", nonce="%s", uri="/test/", algorithm=%s, response="%s", qop=auth, nc=%s, cnonce="%s"\
                """.formatted(_user, _realm, nonce, _authenticator.getAlgorithm(), rsp, nc, cnonce);

            _servlet._received = null;
            request = """
                POST /test/ HTTP/1.1
                Host: localhost
                Content-Length: %d
                Authorization: %s
                
                %s\
                """.formatted(MESSAGE.length(), digest, MESSAGE);
            socket.write(UTF_8.encode(request));

            response = HttpTester.parseResponse(socket);

            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals(MESSAGE, _servlet._received);
        }
    }

    @Test
    public void testUserStar() throws Exception
    {
        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", _connector.getLocalPort())))
        {
            _servlet._received = null;
            String request = """
                POST /test/ HTTP/1.1
                Host: localhost
                Content-Length: %d
                
                %s\
                """.formatted(MESSAGE.length(), MESSAGE);
            socket.write(UTF_8.encode(request));

            HttpTester.Response response = HttpTester.parseResponse(socket);

            assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());
            assertNull(_servlet._received);

            String authenticate = response.get(HttpHeader.WWW_AUTHENTICATE);
            String nonce = nonceFrom(authenticate);
            assertNotNull(nonce);

            String encodedUser = "UTF-8''" + _user.replace("e", "%65");
            String rsp = newResponse("POST", "/test/", nonce);
            String digest = """
                Digest username*="%s", realm="%s", nonce="%s", uri="/test/", algorithm=%s, response="%s", qop=auth, nc=%s, cnonce="%s"\
                """.formatted(encodedUser, _realm, nonce, _authenticator.getAlgorithm(), rsp, nc, cnonce);

            _servlet._received = null;
            request = """
                POST /test/ HTTP/1.1
                Host: localhost
                Content-Length: %d
                Authorization: %s
                
                %s\
                """.formatted(MESSAGE.length(), digest, MESSAGE);
            socket.write(UTF_8.encode(request));

            response = HttpTester.parseResponse(socket);

            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals(MESSAGE, _servlet._received);
        }
    }

    @Test
    public void testServerWithHttpClientStringContent() throws Exception
    {
        try (HttpClient client = new HttpClient())
        {
            String uri = "http://localhost:" + _connector.getLocalPort() + "/test/";
            AuthenticationStore authStore = client.getAuthenticationStore();
            authStore.addAuthentication(new DigestAuthentication(URI.create(uri), _realm, _user, _password));
            client.start();

            _servlet._received = null;
            ContentResponse response = client.newRequest(uri)
                .method(HttpMethod.POST)
                .body(new StringRequestContent(MESSAGE))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(MESSAGE, _servlet._received);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    public void testServerWithHttpClientPathContent() throws Exception
    {
        try (HttpClient client = new HttpClient())
        {
            String uri = "http://localhost:" + _connector.getLocalPort() + "/test/";
            AuthenticationStore authStore = client.getAuthenticationStore();
            authStore.addAuthentication(new DigestAuthentication(URI.create(uri), _realm, _user, _password));
            client.start();

            _servlet._received = null;
            Path path = MavenPaths.findTestResourceFile("message.txt");
            ContentResponse response = client.newRequest(uri)
                .method(HttpMethod.POST)
                .body(new PathRequestContent(path))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(Files.readString(path), _servlet._received);
            assertEquals(200, response.getStatus());
        }
    }

    private String newResponse(String method, String uri, String nonce) throws Exception
    {
        MessageDigest md = MessageDigest.getInstance(_authenticator.getAlgorithm());

        // Calculate A1 digest.
        String a1 = _user + ":" + _realm + ":" + _password;
        byte[] ha1 = md.digest(a1.getBytes(UTF_8));

        // Calculate A2 digest.
        String a2 = method + ":" + uri;
        byte[] ha2 = md.digest(a2.getBytes(UTF_8));

        String rsp = TypeUtil.toString(ha1, 16) + ":" + nonce + ":" + nc +
            ":" + cnonce + ":auth:" + TypeUtil.toString(ha2, 16);
        return TypeUtil.toString(md.digest(rsp.getBytes(UTF_8)), 16);
    }

    private String nonceFrom(String authenticate)
    {
        Pattern pattern = Pattern.compile("nonce=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(authenticate);
        if (matcher.find())
            return matcher.group(1);
        return null;
    }

    public static class PostServlet extends HttpServlet
    {
        public String _received;

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            String received = IO.toString(request.getInputStream());
            _received = received;

            response.setStatus(200);
            response.getWriter().println("Received " + received.length() + " bytes");
        }
    }

    public static class TestLoginService extends AbstractLoginService
    {
        protected Map<String, UserPrincipal> users = new HashMap<>();
        protected Map<String, List<RolePrincipal>> roles = new HashMap<>();

        public TestLoginService(String name)
        {
            setName(name);
        }

        public void putUser(String username, Credential credential, String[] roleNames)
        {
            UserPrincipal userPrincipal = new UserPrincipal(username, credential);
            users.put(username, userPrincipal);
            if (roleNames != null)
                roles.put(username, Arrays.stream(roleNames).map(RolePrincipal::new).toList());
        }

        @Override
        protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
        {
            return roles.get(user.getName());
        }

        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return users.get(username);
        }
    }
}
