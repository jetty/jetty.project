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

package org.eclipse.jetty.util.security;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;

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
    // NOTE: DO NOT INTRODUCE LOGGING TO THIS CLASS
    private static final long serialVersionUID = -7760551052768181572L;
    // Intentionally NOT using TypeUtil.serviceProviderStream
    // as that introduces a Logger requirement that command line Password cannot use.
    private static final List<CredentialProvider> CREDENTIAL_PROVIDERS = ServiceLoader.load(CredentialProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .collect(Collectors.toList());

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

        for (CredentialProvider cp : CREDENTIAL_PROVIDERS)
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
        private static final AutoLock __md5Lock = new AutoLock();
        private static MessageDigest __md;

        private final byte[] _digest;

        MD5(String digest)
        {
            digest = digest.startsWith(__TYPE) ? digest.substring(__TYPE.length()) : digest;
            _digest = StringUtil.fromHexString(digest);
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
                    try (AutoLock l = __md5Lock.lock())
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
                    // Not a MD5 or Credential class
                    return false;
                }
            }
            catch (Exception e)
            {
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

        /**
         * Used only by Command Line Password utility
         */
        public static String digest(String password)
        {
            try
            {
                byte[] digest;
                try (AutoLock l = __md5Lock.lock())
                {
                    if (__md == null)
                    {
                        try
                        {
                            __md = MessageDigest.getInstance("MD5");
                        }
                        catch (Exception e)
                        {
                            System.err.println("Unable to access MD5 message digest");
                            e.printStackTrace();
                            return null;
                        }
                    }

                    __md.reset();
                    __md.update(password.getBytes(StandardCharsets.ISO_8859_1));
                    digest = __md.digest();
                }

                return __TYPE + StringUtil.toHexString(digest);
            }
            catch (Exception e)
            {
                System.err.println("Message Digest Failure");
                e.printStackTrace();
                return null;
            }
        }
    }
}
