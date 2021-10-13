#!/bin/bash

echo ""
echo "-----------------------------------------------"
echo "  Verify Environment"

requiredExecutable() {
    hash $1 2>/dev/null 
    if [ $? != 0 ] ; then
        echo "ERROR: $1 not found.  Install $1"
        exit -1
    fi
}

requiredExecutable "git"
requiredExecutable "xmllint"
requiredExecutable "sed"
requiredExecutable "gpg"
requiredExecutable "egrep"
requiredExecutable "mvn"
requiredExecutable "dot"

proceedyn() {
    while true; do
        read -p "$1 " yn
        case ${yn:-$2} in
            [Yy]* ) return 0;;
            [Nn]* ) return 1;;
            * ) echo "Please answer yes or no.";;
        esac
    done
}

echo ""
echo "-----------------------------------------------"
echo "  Collect Information About Release"

function gitFindRemoteByUrl() {
    URL="$1"
    for GREMOTE in $(git remote); do
        git ls-remote --get-url $GREMOTE | grep "$URL" 2>&1 > /dev/null
        if [ $? -eq 0 ] ; then
            echo $GREMOTE
        fi
    done
    return 0
}

GIT_REMOTE_URL="github.com:eclipse/jetty.project.git"
GIT_REMOTE_ID=$(gitFindRemoteByUrl "$GIT_REMOTE_URL")
GIT_BRANCH_ID=$(git symbolic-ref -q --short HEAD || git describe --tags --exact-match)

if [ -z "$GIT_REMOTE_ID" ] ; then
    echo "ERROR: Unable to determine git remote id for $GIT_REMOTE_URL"
    echo "Are you running this build from a properly cloned git local repository?"
    exit -1
fi

# Ensure that git user is in their gpg key list
GIT_USER_EMAIL=`git config --get user.email`

#gpg -q --list-keys "$GIT_USER_EMAIL" 2>&1 > /dev/null
#if [ $? != 0 ] ; then
#    echo "ERROR: git user.email of $GIT_USER_EMAIL is not present in your gpg --list-keys"
#    echo "Go ahead and make one $ gpg --gen-key"
#    exit -1
#fi

VER_CURRENT=`sed -e "s/xmlns/ignore/" pom.xml | xmllint --xpath "/project/version/text()" -`
echo "Current pom.xml Version: ${VER_CURRENT}"
read -e -p "Release Version  ? " VER_RELEASE
read -e -p "Next Dev Version ? " VER_NEXT
TAG_NAME="jetty-$VER_RELEASE"

# Ensure tag doesn't exist (yet)
git rev-parse --quiet --verify "$TAG_NAME" 2>&1 > /dev/null
if [ $? -eq 0 ] ; then
    echo ""
    echo "ERROR: Git Tag $TAG_NAME already exists"
    echo ""
    git show -s "$TAG_NAME"
    exit -1
fi

ALT_DEPLOY_DIR=$HOME/.m2/alt-deploy
if [ ! -d "$ALT_DEPLOY_DIR" ] ; then
    mkdir -p "$ALT_DEPLOY_DIR"
fi

# DEPLOY_OPTS="-Dmaven.test.failure.ignore=true"
DEPLOY_OPTS="-DskipTests"
# DEPLOY_OPTS="$DEPLOY_OPTS -DaltDeploymentRepository=intarget::default::file://$ALT_DEPLOY_DIR/"

# Uncomment for Java 1.7
export MAVEN_OPTS="-Xmx2g"

echo ""
echo "-----------------------------------------------"
echo "  Release Plan Review"
echo ""
echo "Git Remote ID    : $GIT_REMOTE_ID"
echo "Git Branch ID    : $GIT_BRANCH_ID"
echo "Git user.email   : $GIT_USER_EMAIL"
echo "Current Version  : $VER_CURRENT"
echo "Release Version  : $VER_RELEASE"
echo "Next Dev Version : $VER_NEXT"
echo "Tag name         : $TAG_NAME"
echo "MAVEN_OPTS       : $MAVEN_OPTS"
echo "Maven Deploy Opts: $DEPLOY_OPTS"

reportMavenTestFailures() {
    failFiles=$(egrep -lr --include="*.txt" -E "^Tests .* FAILURE" .)
    oldIFS="$IFS"
    IFS='
'
    IFS=${IFS:0:1}
    failarray=( $failFiles )
    IFS="$oldIFS"

    for index in ${!failarray[@]}; do
        echo ${failarray[index]}
        cat ${failarray[index]}
    done

    if [ ${#failarray[@]} -gt 0 ] ; then
        echo "There are ${#failarray[@]} Test Cases with failures"
    else
        echo "There are no testcases with failures"
    fi
}

echo ""
if proceedyn "Are you sure you want to release using above? (y/N)" n; then
    mvn clean install -pl build-resources
    echo ""
    if proceedyn "Update VERSION.txt for $VER_RELEASE? (Y/n)" y; then
        mvn -N -Pupdate-version generate-resources
        cp VERSION.txt VERSION.txt.backup
        cat VERSION.txt.backup | sed -e "s/$VER_CURRENT/$VER_RELEASE/" > VERSION.txt
        rm VERSION.txt.backup
        echo "VERIFY the following files (in a different console window) before continuing."
        echo "   VERSION.txt - top section"
        echo "   target/version-tag.txt - for the tag commit message"
    fi

    # This is equivalent to 'mvn release:prepare'
    if proceedyn "Update project.versions for $VER_RELEASE? (Y/n)" y; then
        mvn org.codehaus.mojo:versions-maven-plugin:2.7:set \
            -Peclipse-release \
            -DoldVersion="$VER_CURRENT" \
            -DnewVersion="$VER_RELEASE" \
            -DprocessAllModules=true 
    fi
    if proceedyn "Commit $VER_RELEASE updates? (Y/n)" y; then
        git commit -a -m "Updating to version $VER_RELEASE"
    fi
    if proceedyn "Create Tag $TAG_NAME? (Y/n)" y; then
        echo "TODO: Sign tags with GIT_USER_EMAIL=$GIT_USER_EMAIL"
        echo "Using target/version-tag.txt as tag text"
        git tag --file=target/version-tag.txt $TAG_NAME
    fi

    # This is equivalent to 'mvn release:perform'
    if proceedyn "Build/Deploy from tag $TAG_NAME? (Y/n)" y; then
        git checkout $TAG_NAME
        mvn clean deploy -Peclipse-release $DEPLOY_OPTS
        reportMavenTestFailures
        git checkout $GIT_BRANCH_ID
    fi
    if proceedyn "Update working directory for $VER_NEXT? (Y/n)" y; then
        echo "Update VERSION.txt for $VER_NEXT"
        cp VERSION.txt VERSION.txt.backup
        echo "jetty-$VER_NEXT" > VERSION.txt
        echo "" >> VERSION.txt
        cat VERSION.txt.backup >> VERSION.txt
        echo "Update project.versions for $VER_NEXT"
        mvn org.codehaus.mojo:versions-maven-plugin:2.7:set \
            -Peclipse-release \
            -DoldVersion="$VER_RELEASE" \
            -DnewVersion="$VER_NEXT" \
            -DprocessAllModules=true 
        echo "Commit $VER_NEXT"
        if proceedyn "Commit updates in working directory for $VER_NEXT? (Y/n)" y; then
            git commit -a -m "Updating to version $VER_NEXT"
        fi
    fi
    if proceedyn "Push git commits to remote $GIT_REMOTE_ID? (Y/n)" y; then
        git push $GIT_REMOTE_ID $GIT_BRANCH_ID
        git push $GIT_REMOTE_ID $TAG_NAME
    fi
else
    echo "Not performing release"
fi


