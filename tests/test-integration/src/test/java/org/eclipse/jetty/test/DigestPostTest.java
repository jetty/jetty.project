//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.test;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DigestPostTest
{
    private static final String NC = "00000001";

    public static final String __message =
        "0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 \n" +
            "9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 9876543210 \n" +
            "1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 1234567890 \n" +
            "0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 0987654321 \n" +
            "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz \n" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ ABCDEFGHIJKLMNOPQRSTUVWXYZ ABCDEFGHIJKLMNOPQRSTUVWXYZ \n" +
            "Now is the time for all good men to come to the aid of the party.\n" +
            "How now brown cow.\n" +
            "The quick brown fox jumped over the lazy dog.\n";

    public static volatile String _received = null;
    private static Server _server;

    public static class TestLoginService extends AbstractLoginService
    {
        protected Map<String, UserPrincipal> users = new HashMap<>();
        protected Map<String, List<RolePrincipal>> roles = new HashMap<>();

        public TestLoginService(String name)
        {
            setName(name);
        }

        public void putUser(String username, Credential credential, String[] rolenames)
        {
            UserPrincipal userPrincipal = new UserPrincipal(username, credential);
            users.put(username, userPrincipal);
            if (rolenames != null)
                roles.put(username, Arrays.stream(rolenames).map(RolePrincipal::new).collect(Collectors.toList()));
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

    @BeforeAll
    public static void setUpServer()
    {
        try
        {
            _server = new Server();
            _server.setConnectors(new Connector[]{new ServerConnector(_server)});

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SECURITY);
            context.setContextPath("/test");
            context.addServlet(PostServlet.class, "/");

            TestLoginService realm = new TestLoginService("test");
            realm.putUser("testuser", new Password("password"), new String[]{"test"});
            _server.addBean(realm);

            ConstraintSecurityHandler security = (ConstraintSecurityHandler)context.getSecurityHandler();
            security.setAuthenticator(new DigestAuthenticator());
            security.setLoginService(realm);

            Constraint constraint = new Constraint("SecureTest", "test");
            constraint.setAuthenticate(true);
            ConstraintMapping mapping = new ConstraintMapping();
            mapping.setConstraint(constraint);
            mapping.setPathSpec("/*");

            security.setConstraintMappings(Collections.singletonList(mapping));

            _server.setHandler(new HandlerList(context, new DefaultHandler()));

            _server.start();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    @AfterAll
    public static void tearDownServer() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testServerDirectlyHTTP10() throws Exception
    {
        Socket socket = new Socket("127.0.0.1", ((NetworkConnector)_server.getConnectors()[0]).getLocalPort());
        byte[] bytes = __message.getBytes(StandardCharsets.UTF_8);

        _received = null;
        socket.getOutputStream().write(
            ("POST /test/ HTTP/1.0\r\n" +
                "Host: 127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        String result = IO.toString(socket.getInputStream());

        assertTrue(result.startsWith("HTTP/1.1 401 Unauthorized"));
        assertNull(_received);

        int n = result.indexOf("nonce=");
        String nonce = result.substring(n + 7, result.indexOf('"', n + 7));
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b = md.digest(String.valueOf(NanoTime.now()).getBytes(StandardCharsets.ISO_8859_1));
        String cnonce = encode(b);
        String digest = "Digest username=\"testuser\" realm=\"test\" nonce=\"" + nonce + "\" uri=\"/test/\" algorithm=MD5 response=\"" +
            newResponse("POST", "/test/", cnonce, "testuser", "test", "password", nonce, "auth") +
            "\" qop=auth nc=" + NC + " cnonce=\"" + cnonce + "\"";

        socket = new Socket("127.0.0.1", ((NetworkConnector)_server.getConnectors()[0]).getLocalPort());

        _received = null;
        socket.getOutputStream().write(
            ("POST /test/ HTTP/1.0\r\n" +
                "Host: 127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Authorization: " + digest + "\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        result = IO.toString(socket.getInputStream());

        assertTrue(result.startsWith("HTTP/1.1 200 OK"));
        assertEquals(__message, _received);
    }

    @Test
    public void testServerDirectlyHTTP11() throws Exception
    {
        Socket socket = new Socket("127.0.0.1", ((NetworkConnector)_server.getConnectors()[0]).getLocalPort());
        byte[] bytes = __message.getBytes(StandardCharsets.UTF_8);

        _received = null;
        socket.getOutputStream().write(
            ("POST /test/ HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        Thread.sleep(100);

        byte[] buf = new byte[4096];
        int len = socket.getInputStream().read(buf);
        String result = new String(buf, 0, len, StandardCharsets.UTF_8);

        assertTrue(result.startsWith("HTTP/1.1 401 Unauthorized"));
        assertNull(_received);

        int n = result.indexOf("nonce=");
        String nonce = result.substring(n + 7, result.indexOf('"', n + 7));
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b = md.digest(String.valueOf(NanoTime.now()).getBytes(StandardCharsets.ISO_8859_1));
        String cnonce = encode(b);
        String digest = "Digest username=\"testuser\" realm=\"test\" nonce=\"" + nonce + "\" uri=\"/test/\" algorithm=MD5 response=\"" +
            newResponse("POST", "/test/", cnonce, "testuser", "test", "password", nonce, "auth") +
            "\" qop=auth nc=" + NC + " cnonce=\"" + cnonce + "\"";

        _received = null;
        socket.getOutputStream().write(
            ("POST /test/ HTTP/1.0\r\n" +
                "Host: 127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Authorization: " + digest + "\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();

        result = IO.toString(socket.getInputStream());

        assertTrue(result.startsWith("HTTP/1.1 200 OK"));
        assertEquals(__message, _received);
    }

    @Test
    public void testServerWithHttpClientStringContent() throws Exception
    {
        String srvUrl = "http://127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "/test/";
        HttpClient client = new HttpClient();

        try
        {
            AuthenticationStore authStore = client.getAuthenticationStore();
            authStore.addAuthentication(new DigestAuthentication(new URI(srvUrl), "test", "testuser", "password"));
            client.start();

            Request request = client.newRequest(srvUrl);
            request.method(HttpMethod.POST);
            request.body(new BytesRequestContent(__message.getBytes(StandardCharsets.UTF_8)));
            _received = null;
            request = request.timeout(5, TimeUnit.SECONDS);
            ContentResponse response = request.send();
            assertEquals(__message, _received);
            assertEquals(200, response.getStatus());
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testServerWithHttpClientStreamContent() throws Exception
    {
        String srvUrl = "http://127.0.0.1:" + ((NetworkConnector)_server.getConnectors()[0]).getLocalPort() + "/test/";
        HttpClient client = new HttpClient();
        try
        {
            AuthenticationStore authStore = client.getAuthenticationStore();
            authStore.addAuthentication(new DigestAuthentication(new URI(srvUrl), "test", "testuser", "password"));
            client.start();

            String sent = IO.toString(new FileInputStream("src/test/resources/message.txt"));

            Request request = client.newRequest(srvUrl);
            request.method(HttpMethod.POST);
            request.body(new StringRequestContent(sent));
            _received = null;
            request = request.timeout(5, TimeUnit.SECONDS);
            ContentResponse response = request.send();

            assertEquals(200, response.getStatus());
            assertEquals(sent, _received);
        }
        finally
        {
            client.stop();
        }
    }

    public static class PostServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException
        {
            String received = IO.toString(request.getInputStream());
            _received = received;

            response.setStatus(200);
            response.getWriter().println("Received " + received.length() + " bytes");
        }
    }

    protected String newResponse(String method, String uri, String cnonce, String principal, String realm, String credentials, String nonce, String qop)
        throws Exception
    {
        MessageDigest md = MessageDigest.getInstance("MD5");

        // calc A1 digest
        md.update(principal.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(realm.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(credentials.getBytes(StandardCharsets.ISO_8859_1));
        byte[] ha1 = md.digest();
        // calc A2 digest
        md.reset();
        md.update(method.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(uri.getBytes(StandardCharsets.ISO_8859_1));
        byte[] ha2 = md.digest();

        md.update(TypeUtil.toString(ha1, 16).getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(nonce.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(NC.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(cnonce.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(qop.getBytes(StandardCharsets.ISO_8859_1));
        md.update((byte)':');
        md.update(TypeUtil.toString(ha2, 16).getBytes(StandardCharsets.ISO_8859_1));
        byte[] digest = md.digest();

        // check digest
        return encode(digest);
    }

    private static String encode(byte[] data)
    {
        StringBuilder buffer = new StringBuilder();
        for (byte datum : data)
        {
            buffer.append(Integer.toHexString((datum & 0xf0) >>> 4));
            buffer.append(Integer.toHexString(datum & 0x0f));
        }
        return buffer.toString();
    }
}
