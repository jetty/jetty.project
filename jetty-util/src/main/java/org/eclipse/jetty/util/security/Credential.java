//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.security;

import java.io.Serializable;
import java.security.MessageDigest;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * Credentials. The Credential class represents an abstract mechanism for
 * checking authentication credentials. A credential instance either represents
 * a secret, or some data that could only be derived from knowing the secret.
 * <p>
 * Often a Credential is related to a Password via a one way algorithm, so while
 * a Password itself is a Credential, a UnixCrypt or MD5 digest of a a password
 * is only a credential that can be checked against the password.
 * <p>
 * This class includes an implementation for unix Crypt an MD5 digest.
 * 
 * @see Password
 * 
 */
public abstract class Credential implements Serializable
{
    private static final Logger LOG = Log.getLogger(Credential.class);

    private static final long serialVersionUID = -7760551052768181572L;

    /* ------------------------------------------------------------ */
    /**
     * Check a credential
     * 
     * @param credentials The credential to check against. This may either be
     *                another Credential object, a Password object or a String
     *                which is interpreted by this credential.
     * @return True if the credentials indicated that the shared secret is known
     *         to both this Credential and the passed credential.
     */
    public abstract boolean check(Object credentials);

    /* ------------------------------------------------------------ */
    /**
     * Get a credential from a String. If the credential String starts with a
     * known Credential type (eg "CRYPT:" or "MD5:" ) then a Credential of that
     * type is returned. Else the credential is assumed to be a Password.
     * 
     * @param credential String representation of the credential
     * @return A Credential or Password instance.
     */
    public static Credential getCredential(String credential)
    {
        if (credential.startsWith(Crypt.__TYPE)) return new Crypt(credential);
        if (credential.startsWith(MD5.__TYPE)) return new MD5(credential);

        return new Password(credential);
    }

    /* ------------------------------------------------------------ */
    /**
     * Unix Crypt Credentials
     */
    public static class Crypt extends Credential
    {
        private static final long serialVersionUID = -2027792997664744210L;

        public static final String __TYPE = "CRYPT:";

        private final String _cooked;

        Crypt(String cooked)
        {
            _cooked = cooked.startsWith(Crypt.__TYPE) ? cooked.substring(__TYPE.length()) : cooked;
        }

        @Override
        public boolean check(Object credentials)
        {
            if (credentials instanceof char[])
                credentials=new String((char[])credentials);
            if (!(credentials instanceof String) && !(credentials instanceof Password)) 
                LOG.warn("Can't check " + credentials.getClass() + " against CRYPT");

            String passwd = credentials.toString();
            return _cooked.equals(UnixCrypt.crypt(passwd, _cooked));
        }

        public static String crypt(String user, String pw)
        {
            return "CRYPT:" + UnixCrypt.crypt(pw, user);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * MD5 Credentials
     */
    public static class MD5 extends Credential
    {
        private static final long serialVersionUID = 5533846540822684240L;

        public static final String __TYPE = "MD5:";

        public static final Object __md5Lock = new Object();

        private static MessageDigest __md;

        private final byte[] _digest;

        /* ------------------------------------------------------------ */
        MD5(String digest)
        {
            digest = digest.startsWith(__TYPE) ? digest.substring(__TYPE.length()) : digest;
            _digest = TypeUtil.parseBytes(digest, 16);
        }

        /* ------------------------------------------------------------ */
        public byte[] getDigest()
        {
            return _digest;
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean check(Object credentials)
        {
            try
            {
                byte[] digest = null;

                if (credentials instanceof char[])
                    credentials=new String((char[])credentials);
                if (credentials instanceof Password || credentials instanceof String)
                {
                    synchronized (__md5Lock)
                    {
                        if (__md == null) __md = MessageDigest.getInstance("MD5");
                        __md.reset();
                        __md.update(credentials.toString().getBytes(StringUtil.__ISO_8859_1));
                        digest = __md.digest();
                    }
                    if (digest == null || digest.length != _digest.length) return false;
                    for (int i = 0; i < digest.length; i++)
                        if (digest[i] != _digest[i]) return false;
                    return true;
                }
                else if (credentials instanceof MD5)
                {
                    MD5 md5 = (MD5) credentials;
                    if (_digest.length != md5._digest.length) return false;
                    for (int i = 0; i < _digest.length; i++)
                        if (_digest[i] != md5._digest[i]) return false;
                    return true;
                }
                else if (credentials instanceof Credential)
                {
                    // Allow credential to attempt check - i.e. this'll work
                    // for DigestAuthModule$Digest credentials
                    return ((Credential) credentials).check(this);
                }
                else
                {
                    LOG.warn("Can't check " + credentials.getClass() + " against MD5");
                    return false;
                }
            }
            catch (Exception e)
            {
                LOG.warn(e);
                return false;
            }
        }

        /* ------------------------------------------------------------ */
        public static String digest(String password)
        {
            try
            {
                byte[] digest;
                synchronized (__md5Lock)
                {
                    if (__md == null)
                    {
                        try
                        {
                            __md = MessageDigest.getInstance("MD5");
                        }
                        catch (Exception e)
                        {
                            LOG.warn(e);
                            return null;
                        }
                    }

                    __md.reset();
                    __md.update(password.getBytes(StringUtil.__ISO_8859_1));
                    digest = __md.digest();
                }

                return __TYPE + TypeUtil.toString(digest, 16);
            }
            catch (Exception e)
            {
                LOG.warn(e);
                return null;
            }
        }
    }
}
