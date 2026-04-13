Client Certificate Authentication Test Keystores
================================================

These keystores support client certificate authentication testing on modern JDKs
(JDK 17+), replacing legacy JKS keystores that used MD5withRSA (disabled in JDK 26).

IMPORTANT: The certificate Distinguished Name (DN) must match the entry in realm.properties:
CN=localhost,OU=Jetty,O=Webtide,L=Omaha,ST=NE,C=US=,,Administrator

Certificate Properties
----------------------
- Signature Algorithm: SHA384withRSA (secure, modern)
- Key Size: 2048-bit RSA (recommended minimum)
- Validity: 100 years (36500 days) (all good we should be retired by then)
- Format: PKCS12 (industry standard)
- Distinguished Name: CN=localhost, OU=Jetty, O=Webtide, L=Omaha, ST=NE, C=US

Keystore Files
-------------
- server_keystore.p12: Server/client keystore containing private key and self-signed certificate with CA capabilities
- truststore.p12: Truststore containing the server certificate for mutual trust

Test Setup
----------
The test uses a simplified mutual authentication setup:
- Server uses server_keystore.p12 as its keystore (to present server certificate)
- Server uses truststore.p12 as its truststore (to trust client certificates)
- Client uses server_keystore.p12 (same as server - both use same self-signed certificate)

Both server and client use the same self-signed certificate, matching the
pattern of the original JKS keystores (cacerts.jks and clientcert.jks).

Generation Commands
------------------
To regenerate these keystores (from jetty-ee9/jetty-ee9-security/src/test/resources/):

# 1. Generate self-signed certificate with CA capabilities
keytool -v -genkeypair \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048 \
  -keystore server_keystore.p12 \
  -storetype pkcs12 \
  -storepass storepwd \
  -keypass storepwd \
  -dname "CN=localhost, OU=Jetty, O=Webtide, L=Omaha, ST=NE, C=US" \
  -ext bc=ca:true \
  -ext san=ip:127.0.0.1,ip:[::1],dns:localhost

# 2. Export certificate and create truststore
keytool -exportcert \
  -keystore server_keystore.p12 \
  -storepass storepwd \
  -rfc \
  -file server.crt

keytool -importcert \
  -alias ca \
  -file server.crt \
  -keystore truststore.p12 \
  -storetype pkcs12 \
  -storepass storepwd \
  -noprompt

# 3. Clean up temporary file
rm server.crt

Verification Commands
--------------------
# Verify server keystore contents
keytool -list -v -keystore server_keystore.p12 -storepass storepwd

# Expected output:
# - Signature algorithm: SHA384withRSA (NOT MD5withRSA)
# - Key size: 2048-bit RSA (NOT 1024-bit)
# - Owner DN matches realm.properties entry
# - BasicConstraints: CA:true extension
# - SubjectAlternativeName with localhost, 127.0.0.1, ::1
# - No security warnings

# Verify truststore contents
keytool -list -v -keystore truststore.p12 -storepass storepwd

# Expected output:
# - One trusted certificate entry
# - Same certificate/fingerprint as in server_keystore.p12

Testing
-------
# Run test on JDK 26 (should pass - SHA384withRSA is allowed)
mvn -pl jetty-ee9/jetty-ee9-security test -Dtest=ClientCertAuthenticatorTest

Passwords
---------
All keystores use password: storepwd

