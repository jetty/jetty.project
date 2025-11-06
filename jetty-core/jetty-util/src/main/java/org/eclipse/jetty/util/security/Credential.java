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

package org.eclipse.jetty.util.security;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>An abstraction for checking authentication credentials.</p>
 * <p>A credential instance either represents a secret,
 * or some data that could only be derived from knowing the
 * secret, such as a checksum.</p>
 * <p>This class includes implementations for:</p>
 * <ul>
 *   <li>the Unix Crypt algorithm</li>
 *   <li>the MD5 message digest algorithm</li>
 *   <li>any generic message digest algorithm supported by the current JVM</li>
 * </ul>
 *
 * @see Password
 */
public abstract class Credential implements Serializable
{
    // NOTE: DO NOT INTRODUCE LOGGING TO THIS CLASS
    @Serial
    private static final long serialVersionUID = -7760551052768181572L;
    // Intentionally NOT using TypeUtil.serviceProviderStream
    // as that introduces a Logger requirement that command line Password cannot use.
    private static final List<CredentialProvider> CREDENTIAL_PROVIDERS = ServiceLoader.load(CredentialProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .toList();

    /**
     * <p>Checks the given credential against this credential instance.</p>
     *
     * @param credentials the credential to check against this instance.
     * This may either be another Credential object; or a Password object;
     * or a String, char[] or byte[] that are interpreted by this credential.
     * @return whether the given credentials match this credential instance
     */
    public abstract boolean check(Object credentials);

    /**
     * <p>Converts the given String into a Credential.</p>
     * <p>If the String starts with a known Credential type (such as {@code CRYPT:}
     * or {@code MD5:}) then a Credential of that type is returned.
     * Otherwise, it tries to find a credential provider whose prefix matches
     * the start of the String.
     * Otherwise, the credential is assumed to be a {@link Password}.</p>
     *
     * @param credential String representation of the credential
     * @return A Credential or Password instance.
     */
    public static Credential getCredential(String credential)
    {
        if (credential.startsWith(Crypt.TYPE))
            return new Crypt(credential);
        if (credential.startsWith(MD5.TYPE))
            return new MD5(credential);
        if (credential.startsWith(MD.TYPE))
            return new MD(credential);

        for (CredentialProvider cp : CREDENTIAL_PROVIDERS)
        {
            if (credential.startsWith(cp.getPrefix()))
            {
                final Credential credentialObj = cp.getCredential(credential);
                if (credentialObj != null)
                    return credentialObj;
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
        boolean sameObject = known == unknown;
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
     * <p>Unix Crypt Credential.</p>
     */
    public static class Crypt extends Credential
    {
        @Serial
        private static final long serialVersionUID = -2027792997664744210L;
        private static final String TYPE = "CRYPT:";

        private final String _cooked;

        private Crypt(String cooked)
        {
            _cooked = cooked.startsWith(Crypt.TYPE) ? cooked.substring(TYPE.length()) : cooked;
        }

        @Override
        public boolean check(Object credentials)
        {
            if (credentials instanceof char[])
                credentials = new String((char[])credentials);

            return stringEquals(_cooked, UnixCrypt.crypt(credentials.toString(), _cooked));
        }

        @Override
        public int hashCode()
        {
            return _cooked.hashCode();
        }

        @Override
        public boolean equals(Object credential)
        {
            if (credential instanceof Crypt c)
                return stringEquals(_cooked, c._cooked);
            return false;
        }

        public static String crypt(String user, String pw)
        {
            return TYPE + UnixCrypt.crypt(pw, user);
        }
    }

    /**
     * <p>MD5 Credential.</p>
     * <p>For generic message digest credentials, see {@link MD}.</p>
     */
    public static class MD5 extends Credential
    {
        @Serial
        private static final long serialVersionUID = 5533846540822684240L;
        private static final String TYPE = "MD5:";
        private static final AutoLock __md5Lock = new AutoLock();
        private static MessageDigest __md;

        private final byte[] _digest;

        private MD5(String digest)
        {
            digest = digest.startsWith(TYPE) ? digest.substring(TYPE.length()) : digest;
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
                // Normalize to String, if possible.
                if (credentials instanceof char[] chars)
                    credentials = new String(chars);
                else if (credentials instanceof Password password)
                    credentials = password.toString();

                if (credentials instanceof String password)
                    return byteEquals(_digest, md5(password));
                if (credentials instanceof MD5)
                    return equals(credentials);
                if (credentials instanceof Credential other)
                    // Allow the other Credential to check.
                    return other.check(this);
                return false;
            }
            catch (Throwable x)
            {
                return false;
            }
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(_digest);
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
                byte[] digest = md5(password);
                return TYPE + StringUtil.toHexString(digest);
            }
            catch (Throwable x)
            {
                return "<MD5 algorithm failure: %s>".formatted(x);
            }
        }

        private static byte[] md5(String password) throws NoSuchAlgorithmException
        {
            try (AutoLock ignored = __md5Lock.lock())
            {
                if (__md == null)
                    __md = MessageDigest.getInstance("MD5");
                __md.reset();
                return __md.digest(password.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
    }

    /**
     * <p>Generic message digest credential.</p>
     * <p>The string format is {@code MD:<algorithm>:<hex>}, for example:
     * {@code MD:SHA-1:5bAa61E4C9B93f3f0682250b6cF8331b7eE68fD8}.</p>
     */
    public static class MD extends Credential
    {
        @Serial
        private static final long serialVersionUID = -4794312910062793449L;
        private static final String TYPE = "MD:";

        private final String _algorithm;
        private final byte[] _digest;

        private MD(String credential)
        {
            if (!credential.startsWith(TYPE))
                throw new IllegalArgumentException("Invalid credential " + credential);
            String algoAndDigest = credential.substring(TYPE.length());
            int colon = algoAndDigest.indexOf(':');
            if (colon < 0)
                throw new IllegalArgumentException("Invalid credential " + credential);
            _algorithm = algoAndDigest.substring(0, colon);
            _digest = StringUtil.fromHexString(algoAndDigest.substring(colon + 1));
        }

        @Override
        public boolean check(Object credentials)
        {
            try
            {
                // Normalize to String, if possible.
                if (credentials instanceof char[] chars)
                    credentials = new String(chars);
                else if (credentials instanceof byte[] bytes)
                    credentials = new String(bytes, StandardCharsets.UTF_8);
                else if (credentials instanceof Password password)
                    credentials = password.toString();

                if (credentials instanceof String password)
                    return byteEquals(_digest, digest(_algorithm, password));
                if (credentials instanceof MD)
                    return equals(credentials);
                if (credentials instanceof Credential other)
                    // Allow the other Credential to check.
                    return other.check(this);
                return false;
            }
            catch (Throwable x)
            {
                return false;
            }
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(_digest);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof MD other)
                return byteEquals(_digest, other._digest);
            return false;
        }

        static String format(String algorithm, String password)
        {
            try
            {
                byte[] bytes = digest(algorithm, password);
                return TYPE + algorithm + ":" + StringUtil.toHexString(bytes);
            }
            catch (Throwable x)
            {
                return "<%s algorithm failure: %s>".formatted(algorithm, x);
            }
        }

        private static byte[] digest(String algorithm, String password) throws NoSuchAlgorithmException
        {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        }
    }
}
