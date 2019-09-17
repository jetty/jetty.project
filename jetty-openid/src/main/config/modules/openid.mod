DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds OpenId Connect authentication.

[depend]
security

[lib]
lib/jetty-openid-${jetty.version}.jar
lib/jetty-util-ajax-${jetty.version}.jar

[files]
basehome:modules/openid/openid-baseloginservice.xml|etc/openid-baseloginservice.xml

[xml]
etc/openid-baseloginservice.xml
etc/jetty-openid.xml

[ini-template]
## The OpenID Identity Provider
# jetty.openid.openIdProvider=https://accounts.google.com/

## The Client Identifier
# jetty.openid.clientId=test1234.apps.googleusercontent.com

## The Client Secret
# jetty.openid.clientSecret=XT_Mafv_aUCGheuCaKY8P

## Additional Scopes to Request
# jetty.openid.scopes=email,profile

## Whether to Authenticate users not found by base LoginService
# jetty.openid.authenticateNewUsers=false