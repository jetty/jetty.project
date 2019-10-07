package org.eclipse.jetty.security.openid;

import java.util.Base64;

public class JwtEncoder
{
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder();
    private static final String DEFAULT_HEADER = "{\"INFO\": \"this is not used or checked in our implementation\"}";
    private static final String DEFAULT_SIGNATURE = "we do not validate signature as we use the authorization code flow";

    public static String encode(String idToken)
    {
        return stripPadding(ENCODER.encodeToString(DEFAULT_HEADER.getBytes())) + "." +
            stripPadding(ENCODER.encodeToString(idToken.getBytes())) + "." +
            stripPadding(ENCODER.encodeToString(DEFAULT_SIGNATURE.getBytes()));
    }

    private static String stripPadding(String paddedBase64)
    {
        return paddedBase64.split("=")[0];
    }

    public static String createIdToken(String provider, String clientId, String subject, String name, long expiry)
    {
        return "{" +
            "\"iss\": \"" + provider + "\"," +
            "\"sub\": \"" + subject + "\"," +
            "\"aud\": \"" + clientId + "\"," +
            "\"exp\": " + expiry + "," +
            "\"name\": \"" + name + "\"," +
            "\"email\": \"" + name + "@fake-email.com" + "\"" +
            "}";
    }
}
