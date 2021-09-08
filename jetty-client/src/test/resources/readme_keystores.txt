Since OpenJDK 13.0.2/11.0.6 it is required that CA certificates have the extension CA=true.

The keystores are generated in the following way:

# Generates the server keystore. Note the BasicConstraint=CA:true extension.
$ keytool -v -genkeypair -validity 36500 -keyalg RSA -keysize 2048 -keystore keystore.p12 -storetype pkcs12 -dname "CN=server, OU=Jetty, O=Webtide, L=Omaha, S=NE, C=US" -ext BC=CA:true

# Export the server certificate.
$ keytool -v -export -keystore keystore.p12 -rfc -file server.crt

# Export the server private key.
$ openssl pkcs12 -in keystore.p12 -nodes -nocerts -out server.key

# Generate the client keystore.
$ keytool -v -genkeypair -validity 36500 -keyalg RSA -keysize 2048 -keystore client_keystore.p12 -storetype pkcs12 -dname "CN=client, OU=Jetty, O=Webtide, L=Omaha, S=NE, C=US"

# Generate the Certificate Signing Request.
$ keytool -certreq -file client.csr -keystore client_keystore.p12

# Sign the CSR.
$ openssl x509 -req -days 36500 -in client.csr -CA server.crt -CAkey server.key -CAcreateserial -sha256 -out signed.crt

# Import the server certificate into the client keystore.
$ keytool -v -import -alias ca -file server.crt -keystore client_keystore.p12

# Import the signed CSR.
$ keytool -import -file signed.crt -keystore client_keystore.p12
