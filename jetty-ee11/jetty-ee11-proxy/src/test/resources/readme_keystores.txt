The keystores are generated differently from jetty-client's readme_keystores.txt.

Since SslContextFactory also loads the KeyStore as a TrustStore, rather than doing
CSR for the client certificates and sign them with the server|proxy certificate,
we just load the client certificates in the server|proxy KeyStores so that they
are trusted.

Structure is the following:

server_keystore.p12:
  mykey: self-signed certificate with private key
  client: certificate from client_keystore.p12@client_to_server

proxy_keystore.p12:
  mykey: self-signed certificate with private key
  client: certificate from client_keystore.p12@client_to_proxy

client_keystore.p12
  client_to_proxy: self-signed certificate with private key (client certificate to send to proxy)
  client_to_server: self-signed certificate with private key (client certificate to send to server)
  proxy: certificate from proxy_keystore.p12@mykey (to trust proxy certificate)
  server: certificate from server_keystore.p12@mykey (to trust server certificate)
