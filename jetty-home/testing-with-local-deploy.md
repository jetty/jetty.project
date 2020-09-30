# Testing Jetty Home
 
Sometimes you need to test a module download scenario.
This is best done with a locally deployed (SNAPSHOT) repository.

How to create a local deploy repo location on disk.

## Initialize the local deploy directory.

This will create the directory for the deploy,
and symlink a few (non-snapshot) artifacts that are not deployed by our build. 

```
export JDEPLOY_REPO=$HOME/tmp/test-deploy-repo
mkdir -p $JDEPLOY_REPO
mkdir -p $JDEPLOY_REPO/org/eclipse/jetty
ln -s $HOME/.m2/repository/org/eclipse/jetty/orbit $JDEPLOY_REPO/org/eclipse/jetty/orbit
ln -s $HOME/.m2/repository/jakarta $JDEPLOY_REPO/jakarta
ln -s $HOME/.m2/repository/javax $JDEPLOY_REPO/javax
```

## Perform the build to deploy

This will perform the build, skipping tests, and put all "deployable" artifacts into
the directory specified.

``` shell 
export JDEPLOY_REPO=$HOME/tmp/test-deploy-repo
mvn clean install deploy -DskipTests -DaltSnapshotDeploymentRepository=jettysnapshots::default::file://$JDEPLOY_REPO
```

# Test jetty-home with local deploy repo 

This will setup a fresh test base directory.  
Initialize the base directory with the "demo" module.  
Note: you will want to export setup a valid `$JETTY_HOME` location first.

``` shell
export JDEPLOY_REPO=$HOME/tmp/test-deploy-repo
export JLOCAL_REPO=$HOME/tmp/test-local-repo
mkdir $HOME/tmp/mybase
rm -rf $HOME/tmp/mybase/*
cd $HOME/tmp/mybase
mkdir $JLOCAL_REPO
rm -rf $JLOCAL_REPO/*
java -jar $JETTY_HOME/start.jar maven.local.repo=$JLOCAL_REPO/ maven.repo.uri=file://$JDEPLOY_REPO/ --add-module=demo
```
