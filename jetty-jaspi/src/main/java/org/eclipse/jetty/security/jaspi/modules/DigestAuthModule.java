//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi.modules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;

@Deprecated
public class DigestAuthModule extends BaseAuthModule
{
    private static final Logger LOG = Log.getLogger(DigestAuthModule.class);

    protected long maxNonceAge = 0;

    protected long nonceSecret = this.hashCode() ^ System.currentTimeMillis();

    protected boolean useStale = false;

    private String realmName;

    private static final String REALM_KEY = "org.eclipse.jetty.security.jaspi.modules.RealmName";

    public DigestAuthModule()
    {
    }

    public DigestAuthModule(CallbackHandler callbackHandler, String realmName)
    {
        super(callbackHandler);
        this.realmName = realmName;
    }

    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, 
                           CallbackHandler handler, Map options) 
    throws AuthException
    {
        super.initialize(requestPolicy, responsePolicy, handler, options);
        realmName = (String) options.get(REALM_KEY);
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, 
                                      Subject serviceSubject) 
    throws AuthException
    {
        HttpServletRequest request = (HttpServletRequest) messageInfo.getRequestMessage();
        HttpServletResponse response = (HttpServletResponse) messageInfo.getResponseMessage();
        String credentials = request.getHeader(HttpHeader.AUTHORIZATION.asString());

        try
        {
            boolean stale = false;
            // TODO extract from request
            long timestamp = System.currentTimeMillis();
            if (credentials != null)
            {
                if (LOG.isDebugEnabled()) LOG.debug("Credentials: " + credentials);
                QuotedStringTokenizer tokenizer = new QuotedStringTokenizer(credentials, "=, ", true, false);
                final Digest digest = new Digest(request.getMethod());
                String last = null;
                String name = null;

                while (tokenizer.hasMoreTokens())
                {
                    String tok = tokenizer.nextToken();
                    char c = (tok.length() == 1) ? tok.charAt(0) : '\0';

                    switch (c)
                    {
                        case '=':
                            name = last;
                            last = tok;
                            break;
                        case ',':
                            name = null;
                        case ' ':
                            break;

                        default:
                            last = tok;
                            if (name != null)
                            {
                                if ("username".equalsIgnoreCase(name))
                                    digest.username = tok;
                                else if ("realm".equalsIgnoreCase(name))
                                    digest.realm = tok;
                                else if ("nonce".equalsIgnoreCase(name))
                                    digest.nonce = tok;
                                else if ("nc".equalsIgnoreCase(name))
                                    digest.nc = tok;
                                else if ("cnonce".equalsIgnoreCase(name))
                                    digest.cnonce = tok;
                                else if ("qop".equalsIgnoreCase(name))
                                    digest.qop = tok;
                                else if ("uri".equalsIgnoreCase(name))
                                    digest.uri = tok;
                                else if ("response".equalsIgnoreCase(name)) digest.response = tok;
                                break;
                            }
                    }
                }

                int n = checkNonce(digest.nonce, timestamp);

                if (n > 0)
                {
                    if (login(clientSubject, digest.username, digest, Constraint.__DIGEST_AUTH, messageInfo)) { return AuthStatus.SUCCESS; }
                }
                else if (n == 0) stale = true;

            }

            if (!isMandatory(messageInfo)) { return AuthStatus.SUCCESS; }
            String domain = request.getContextPath();
            if (domain == null) domain = "/";
            response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), "Digest realm=\"" + realmName
                                                             + "\", domain=\""
                                                             + domain
                                                             + "\", nonce=\""
                                                             + newNonce(timestamp)
                                                             + "\", algorithm=MD5, qop=\"auth\""
                                                             + (useStale ? (" stale=" + stale) : ""));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthStatus.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new AuthException(e.getMessage());
        }
        catch (UnsupportedCallbackException e)
        {
            throw new AuthException(e.getMessage());
        }

    }

    public String newNonce(long ts)
    {
        // long ts=request.getTimeStamp();
        long sk = nonceSecret;

        byte[] nounce = new byte[24];
        for (int i = 0; i < 8; i++)
        {
            nounce[i] = (byte) (ts & 0xff);
            ts = ts >> 8;
            nounce[8 + i] = (byte) (sk & 0xff);
            sk = sk >> 8;
        }

        byte[] hash = null;
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.reset();
            md.update(nounce, 0, 16);
            hash = md.digest();
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        for (int i = 0; i < hash.length; i++)
        {
            nounce[8 + i] = hash[i];
            if (i == 23) break;
        }

        return new String(B64Code.encode(nounce));
    }

    /**
     * @param nonce the nonce
     * @param timestamp should be timestamp of request.
     * @return -1 for a bad nonce, 0 for a stale none, 1 for a good nonce
     */
    public int checkNonce(String nonce, long timestamp)
    {
        try
        {
            byte[] n = B64Code.decode(nonce.toCharArray());
            if (n.length != 24) return -1;

            long ts = 0;
            long sk = nonceSecret;
            byte[] n2 = new byte[16];
            System.arraycopy(n, 0, n2, 0, 8);
            for (int i = 0; i < 8; i++)
            {
                n2[8 + i] = (byte) (sk & 0xff);
                sk = sk >> 8;
                ts = (ts << 8) + (0xff & (long) n[7 - i]);
            }

            long age = timestamp - ts;
            if (LOG.isDebugEnabled()) LOG.debug("age=" + age);

            byte[] hash = null;
            try
            {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.reset();
                md.update(n2, 0, 16);
                hash = md.digest();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }

            for (int i = 0; i < 16; i++)
                if (n[i + 8] != hash[i]) return -1;

            if (maxNonceAge > 0 && (age < 0 || age > maxNonceAge)) return 0; // stale

            return 1;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
        return -1;
    }

    private static class Digest extends Credential
    {
        private static final long serialVersionUID = -1866670896275159116L;

        String method = null;
        String username = null;
        String realm = null;
        String nonce = null;
        String nc = null;
        String cnonce = null;
        String qop = null;
        String uri = null;
        String response = null;

        /* ------------------------------------------------------------ */
        Digest(String m)
        {
            method = m;
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean check(Object credentials)
        {
            String password = (credentials instanceof String) ? (String) credentials : credentials.toString();

            try
            {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] ha1;
                if (credentials instanceof Credential.MD5)
                {
                    // Credentials are already a MD5 digest - assume it's in
                    // form user:realm:password (we have no way to know since
                    // it's a digest, alright?)
                    ha1 = ((Credential.MD5) credentials).getDigest();
                }
                else
                {
                    // calc A1 digest
                    md.update(username.getBytes(StandardCharsets.ISO_8859_1));
                    md.update((byte) ':');
                    md.update(realm.getBytes(StandardCharsets.ISO_8859_1));
                    md.update((byte) ':');
                    md.update(password.getBytes(StandardCharsets.ISO_8859_1));
                    ha1 = md.digest();
                }
                // calc A2 digest
                md.reset();
                md.update(method.getBytes(StandardCharsets.ISO_8859_1));
                md.update((byte) ':');
                md.update(uri.getBytes(StandardCharsets.ISO_8859_1));
                byte[] ha2 = md.digest();

                // calc digest
                // request-digest = <"> < KD ( H(A1), unq(nonce-value) ":"
                // nc-value ":" unq(cnonce-value) ":" unq(qop-value) ":" H(A2) )
                // <">
                // request-digest = <"> < KD ( H(A1), unq(nonce-value) ":" H(A2)
                // ) > <">

                md.update(TypeUtil.toString(ha1, 16).getBytes(StandardCharsets.ISO_8859_1));
                md.update((byte) ':');
                md.update(nonce.getBytes(StandardCharsets.ISO_8859_1));
                md.update((byte) ':');
                md.update(nc.getBytes(StandardCharsets.ISO_8859_1));
                md.update((byte) ':');
                md.update(cnonce.getBytes(StandardCharsets.ISO_8859_1));
                md.update((byte) ':');
                md.update(qop.getBytes(StandardCharsets.ISO_8859_1));
                md.update((byte) ':');
                md.update(TypeUtil.toString(ha2, 16).getBytes(StandardCharsets.ISO_8859_1));
                byte[] digest = md.digest();

                // check digest
                return (TypeUtil.toString(digest, 16).equalsIgnoreCase(response));
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }

            return false;
        }

        @Override
        public String toString()
        {
            return username + "," + response;
        }

    }
}
