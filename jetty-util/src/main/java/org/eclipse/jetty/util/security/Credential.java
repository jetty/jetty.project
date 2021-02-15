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

package org.eclipse.jetty.util.security;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Credentials. The Credential class represents an abstract mechanism for checking authentication credentials. A credential instance either represents a secret,
 * or some data that could only be derived from knowing the secret.
 * <p>
 * Often a Credential is related to a Password via a one way algorithm, so while a Password itself is a Credential, a UnixCrypt or MD5 digest of a a password is
 * only a credential that can be checked against the password.
 * <p>
 * This class includes an implementation for unix Crypt an MD5 digest.
 *
 * @see Password
 */
public abstract class Credential implements Serializable
{
    private static final long serialVersionUID = -7760551052768181572L;
    private static final Logger LOG = Log.getLogger(Credential.class);
    private static final ServiceLoader<CredentialProvider> CREDENTIAL_PROVIDER_LOADER = ServiceLoader.load(CredentialProvider.class);

    /**
     * Check a credential
     *
     * @param credentials The credential to check against. This may either be another Credential object, a Password object or a String which is interpreted by this
     * credential.
     * @return True if the credentials indicated that the shared secret is known to both this Credential and the passed credential.
     */
    public abstract boolean check(Object credentials);

    /**
     * Get a credential from a String. If the credential String starts with a known Credential type (eg "CRYPT:" or "MD5:" ) then a Credential of that type is
     * returned. Otherwise, it tries to find a credential provider whose prefix matches with the start of the credential String. Else the credential is assumed
     * to be a Password.
     *
     * @param credential String representation of the credential
     * @return A Credential or Password instance.
     */
    public static Credential getCredential(String credential)
    {
        if (credential.startsWith(Crypt.__TYPE))
            return new Crypt(credential);
        if (credential.startsWith(MD5.__TYPE))
            return new MD5(credential);

        for (CredentialProvider cp : CREDENTIAL_PROVIDER_LOADER)
        {
            if (credential.startsWith(cp.getPrefix()))
            {
                final Credential credentialObj = cp.getCredential(credential);
                if (credentialObj != null)
                {
                    return credentialObj;
                }
            }
        }

        return new Password(credential);
    }

    /**
     * <p>Utility method that replaces String.equals() to avoid timing attacks.
     * The length of the loop executed will always be the length of the unknown credential</p>
     *
     * @param known the first string to compare (should be known string)
     * @param unknown the second string to compare (should be the unknown string)
     * @return whether the two strings are equal
     */
    protected static boolean stringEquals(String known, String unknown)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean sameObject = (known == unknown);
        if (sameObject)
            return true;
        if (known == null || unknown == null)
            return false;
        boolean result = true;
        int l1 = known.length();
        int l2 = unknown.length();
        for (int i = 0; i < l2; ++i)
        {
            result &= ((l1 == 0) ? unknown.charAt(l2 - i - 1) : known.charAt(i % l1)) == unknown.charAt(i);
        }
        return result && l1 == l2;
    }

    /**
     * <p>Utility method that replaces Arrays.equals() to avoid timing attacks.
     * The length of the loop executed will always be the length of the unknown credential</p>
     *
     * @param known the first byte array to compare (should be known value)
     * @param unknown the second byte array to compare  (should be unknown value)
     * @return whether the two byte arrays are equal
     */
    protected static boolean byteEquals(byte[] known, byte[] unknown)
    {
        if (known == unknown)
            return true;
        if (known == null || unknown == null)
            return false;
        boolean result = true;
        int l1 = known.length;
        int l2 = unknown.length;
        for (int i = 0; i < l2; ++i)
        {
            result &= ((l1 == 0) ? unknown[l2 - i - 1] : known[i % l1]) == unknown[i];
        }
        return result && l1 == l2;
    }

    /**
     * Unix Crypt Credentials
     */
    public static class Crypt extends Credential
    {
        private static final long serialVersionUID = -2027792997664744210L;
        private static final String __TYPE = "CRYPT:";

        private final String _cooked;

        Crypt(String cooked)
        {
            _cooked = cooked.startsWith(Crypt.__TYPE) ? cooked.substring(__TYPE.length()) : cooked;
        }

        @Override
        public boolean check(Object credentials)
        {
            if (credentials instanceof char[])
                credentials = new String((char[])credentials);
            if (!(credentials instanceof String) && !(credentials instanceof Password))
                LOG.warn("Can't check " + credentials.getClass() + " against CRYPT");
            return stringEquals(_cooked, UnixCrypt.crypt(credentials.toString(), _cooked));
        }

        @Override
        public boolean equals(Object credential)
        {
            if (!(credential instanceof Crypt))
                return false;
            Crypt c = (Crypt)credential;
            return stringEquals(_cooked, c._cooked);
        }

        public static String crypt(String user, String pw)
        {
            return __TYPE + UnixCrypt.crypt(pw, user);
        }
    }

    /**
     * MD5 Credentials
     */
    public static class MD5 extends Credential
    {
        private static final long serialVersionUID = 5533846540822684240L;
        private static final String __TYPE = "MD5:";
        private static final Object __md5Lock = new Object();
        private static MessageDigest __md;

        private final byte[] _digest;

        MD5(String digest)
        {
            digest = digest.startsWith(__TYPE) ? digest.substring(__TYPE.length()) : digest;
            _digest = TypeUtil.parseBytes(digest, 16);
        }

        public byte[] getDigest()
        {
            return _digest;
        }

        @Override
        public boolean check(Object credentials)
        {
            try
            {
                if (credentials instanceof char[])
                    credentials = new String((char[])credentials);
                if (credentials instanceof Password || credentials instanceof String)
                {
                    byte[] digest;
                    synchronized (__md5Lock)
                    {
                        if (__md == null)
                            __md = MessageDigest.getInstance("MD5");
                        __md.reset();
                        __md.update(credentials.toString().getBytes(StandardCharsets.ISO_8859_1));
                        digest = __md.digest();
                    }
                    return byteEquals(_digest, digest);
                }
                else if (credentials instanceof MD5)
                {
                    return equals(credentials);
                }
                else if (credentials instanceof Credential)
                {
                    // Allow credential to attempt check - i.e. this'll work
                    // for DigestAuthModule$Digest credentials
                    return ((Credential)credentials).check(this);
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

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof MD5)
                return byteEquals(_digest, ((MD5)obj)._digest);
            return false;
        }

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
                    __md.update(password.getBytes(StandardCharsets.ISO_8859_1));
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
