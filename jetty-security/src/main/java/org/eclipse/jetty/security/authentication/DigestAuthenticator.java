// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.security.authentication;

import java.io.IOException;
import java.security.MessageDigest;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.security.B64Code;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.http.security.Credential;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;

/**
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class DigestAuthenticator extends LoginAuthenticator
{
    protected long _maxNonceAge = 0;
    protected long _nonceSecret = this.hashCode() ^ System.currentTimeMillis();
    protected boolean _useStale = false;

    public DigestAuthenticator()
    {
        super();
    }

    public String getAuthMethod()
    {
        return Constraint.__DIGEST_AUTH;
    }
    
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        return true;
    }

    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        if (mandatory)
            return _deferred;
        
        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)res;
        String credentials = request.getHeader(HttpHeaders.AUTHORIZATION);

        try
        {
            boolean stale = false;
            if (credentials != null)
            {
                if (Log.isDebugEnabled()) Log.debug("Credentials: " + credentials);
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

                int n = checkNonce(digest.nonce, (Request)request);

                if (n > 0)
                {
                    UserIdentity user = _loginService.login(digest.username,digest);
                    if (user!=null)
                        return new UserAuthentication(this,user);
                }
                else if (n == 0) 
                    stale = true;

            }

            if (!_deferred.isDeferred(response))
            {
                String domain = request.getContextPath();
                if (domain == null) 
                    domain = "/";
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Digest realm=\"" + _loginService.getName()
                        + "\", domain=\""
                        + domain
                        + "\", nonce=\""
                        + newNonce((Request)request)
                        + "\", algorithm=MD5, qop=\"auth\""
                        + (_useStale ? (" stale=" + stale) : ""));
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);

                return Authentication.SEND_CONTINUE;
            }

            return Authentication.UNAUTHENTICATED;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }

    }

    public String newNonce(Request request)
    {
        long ts=request.getTimeStamp();
        long sk = _nonceSecret;

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
            Log.warn(e);
        }

        for (int i = 0; i < hash.length; i++)
        {
            nounce[8 + i] = hash[i];
            if (i == 23) break;
        }

        return new String(B64Code.encode(nounce));
    }

    /**
     * @param nonce nonce to check
     * @param request
     * @return -1 for a bad nonce, 0 for a stale none, 1 for a good nonce
     */
    /* ------------------------------------------------------------ */
    private int checkNonce(String nonce, Request request)
    {
        try
        {
            byte[] n = B64Code.decode(nonce.toCharArray());
            if (n.length != 24) return -1;

            long ts = 0;
            long sk = _nonceSecret;
            byte[] n2 = new byte[16];
            System.arraycopy(n, 0, n2, 0, 8);
            for (int i = 0; i < 8; i++)
            {
                n2[8 + i] = (byte) (sk & 0xff);
                sk = sk >> 8;
                ts = (ts << 8) + (0xff & (long) n[7 - i]);
            }

            long age = request.getTimeStamp() - ts;
            if (Log.isDebugEnabled()) Log.debug("age=" + age);

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
                Log.warn(e);
            }

            for (int i = 0; i < 16; i++)
                if (n[i + 8] != hash[i]) return -1;

            if (_maxNonceAge > 0 && (age < 0 || age > _maxNonceAge)) return 0; // stale

            return 1;
        }
        catch (Exception e)
        {
            Log.ignore(e);
        }
        return -1;
    }

    private static class Digest extends Credential
    {
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
        public boolean check(Object credentials)
        {
            if (credentials instanceof char[])
                credentials=new String((char[])credentials);
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
                    md.update(username.getBytes(StringUtil.__ISO_8859_1));
                    md.update((byte) ':');
                    md.update(realm.getBytes(StringUtil.__ISO_8859_1));
                    md.update((byte) ':');
                    md.update(password.getBytes(StringUtil.__ISO_8859_1));
                    ha1 = md.digest();
                }
                // calc A2 digest
                md.reset();
                md.update(method.getBytes(StringUtil.__ISO_8859_1));
                md.update((byte) ':');
                md.update(uri.getBytes(StringUtil.__ISO_8859_1));
                byte[] ha2 = md.digest();

                // calc digest
                // request-digest = <"> < KD ( H(A1), unq(nonce-value) ":"
                // nc-value ":" unq(cnonce-value) ":" unq(qop-value) ":" H(A2) )
                // <">
                // request-digest = <"> < KD ( H(A1), unq(nonce-value) ":" H(A2)
                // ) > <">

                md.update(TypeUtil.toString(ha1, 16).getBytes(StringUtil.__ISO_8859_1));
                md.update((byte) ':');
                md.update(nonce.getBytes(StringUtil.__ISO_8859_1));
                md.update((byte) ':');
                md.update(nc.getBytes(StringUtil.__ISO_8859_1));
                md.update((byte) ':');
                md.update(cnonce.getBytes(StringUtil.__ISO_8859_1));
                md.update((byte) ':');
                md.update(qop.getBytes(StringUtil.__ISO_8859_1));
                md.update((byte) ':');
                md.update(TypeUtil.toString(ha2, 16).getBytes(StringUtil.__ISO_8859_1));
                byte[] digest = md.digest();

                // check digest
                return (TypeUtil.toString(digest, 16).equalsIgnoreCase(response));
            }
            catch (Exception e)
            {
                Log.warn(e);
            }

            return false;
        }

        public String toString()
        {
            return username + "," + response;
        }
    }
}
