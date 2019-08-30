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
# jetty.openid.clientId=1051168419525-5nl60mkugb77p9j194mrh287p1e0ahfi.apps.googleusercontent.com

## The Client Secret
# jetty.openid.clientSecret=XT_MIsSv_aUCGollauCaJY8S

## Additional Scopes to Request
# jetty.openid.scopes=email,profile

## Whether to Authenticate users not found by base LoginService
# jetty.openid.authenticateNewUsers=false