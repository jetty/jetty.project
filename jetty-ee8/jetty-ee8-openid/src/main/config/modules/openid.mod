# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds OpenId Connect authentication to the server.

[depend]
security
client

[lib]
lib/jetty-ee8-openid-${jetty.version}.jar
lib/jetty-util-ajax-${jetty.version}.jar

[files]
basehome:modules/openid/jetty-ee8-openid-baseloginservice.xml|etc/openid-baseloginservice.xml

[xml]
etc/openid-baseloginservice.xml
etc/jetty-openid.xml

[ini-template]
## The OpenID Identity Provider's issuer ID (the entire URL *before* ".well-known/openid-configuration")
# jetty.openid.provider=https://id.example.com/

## The OpenID Identity Provider's authorization endpoint (optional if the metadata of the OP is accessible)
# jetty.openid.provider.authorizationEndpoint=https://id.example.com/authorization

## The OpenID Identity Provider's token endpoint (optional if the metadata of the OP is accessible)
# jetty.openid.provider.tokenEndpoint=https://id.example.com/token

## The Client Identifier
# jetty.openid.clientId=test1234

## The Client Secret
# jetty.openid.clientSecret=XT_Mafv_aUCGheuCaKY8P

## Additional Scopes to Request
# jetty.openid.scopes=email,profile

## Whether to Authenticate users not found by base LoginService
# jetty.openid.authenticateNewUsers=false

## True if all certificates should be trusted by the default SslContextFactory
# jetty.openid.sslContextFactory.trustAll=false

## What authentication method to use with the Token Endpoint (client_secret_post, client_secret_basic).
# jetty.openid.authMethod=client_secret_post
