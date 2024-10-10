#!groovy

pipeline {
  agent none
  // save some io during the build
  options {
    skipDefaultCheckout()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    buildDiscarder logRotator( numToKeepStr: '60' )
    disableRestartFromStage()
  }
  environment {
    LAUNCHABLE_TOKEN = credentials('launchable-token')
  }
  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test - JDK21") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk21", "clean install -Dspotbugs.skip=true -Djacoco.skip=true", "maven3")
              recordIssues id: "jdk21", name: "Static Analysis jdk21", aggregatingResults: true, enabledForFailure: true,
                            tools: [mavenConsole(), java(), checkStyle(), javaDoc()],
                            skipPublishingChecks: true, skipBlames: true
            }
          }
        }

        stage("Build / Test - JDK23") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk23", "clean install -Dspotbugs.skip=true -Djacoco.skip=true", "maven3")
              recordIssues id: "jdk23", name: "Static Analysis jdk23", aggregatingResults: true, enabledForFailure: true, tools: [mavenConsole(), java(), checkStyle(), javaDoc()]
            }
          }
        }

        stage("Build / Test - JDK17") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk17", "clean install -Perrorprone", "maven3") // javadoc:javadoc
              recordIssues id: "analysis-jdk17", name: "Static Analysis jdk17", aggregatingResults: true, enabledForFailure: true,
                            tools: [mavenConsole(), java(), checkStyle(), errorProne(), spotBugs(), javaDoc()],
                            skipPublishingChecks: true, skipBlames: true
              recordCoverage id: "coverage-jdk17", name: "Coverage jdk17", tools: [[parser: 'JACOCO']], sourceCodeRetention: 'MODIFIED',
                             sourceDirectories: [[path: 'src/main/java'], [path: 'target/generated-sources/ee8']]
            }
          }
        }
      }
    }
  }
  post {
    failure {
      slackNotif()
    }
    unstable {
      slackNotif()
    }
    fixed {
      slackNotif()
      websiteBuild()
    }
    success {
      websiteBuild()
    }
  }
}

def slackNotif() {
  script {
    try {
      if ( env.BRANCH_NAME == 'jetty-10.0.x' || env.BRANCH_NAME == 'jetty-11.0.x' || env.BRANCH_NAME == 'jetty-12.0.x' ) {
        //BUILD_USER = currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId()
        // by ${BUILD_USER}
        COLOR_MAP = ['SUCCESS': 'good', 'FAILURE': 'danger', 'UNSTABLE': 'danger', 'ABORTED': 'danger']
        slackSend channel: '#jenkins',
                  color: COLOR_MAP[currentBuild.currentResult],
                  message: "*${currentBuild.currentResult}:* Job ${env.JOB_NAME} build ${env.BUILD_NUMBER} - ${env.BUILD_URL}"
      }
    } catch (Exception e) {
      e.printStackTrace()
      echo "skip failure slack notification: " + e.getMessage()
    }
  }
}

/**
 * To other developers, if you are using this method above, please use the following syntax.
 *
 * mavenBuild("<jdk>", "<profiles> <goals> <plugins> <properties>"
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @return the Jenkinsfile step representing a maven build
 */
def mavenBuild(jdk, cmdline, mvnName) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${ tool "$jdk" }/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms3072m -Xmx5120m -Djava.awt.headless=true -client -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=100"]) {
      configFileProvider(
        [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS'),
          configFile(fileId: 'maven-build-cache-config.xml', variable: 'MVN_BUILD_CACHE_CONFIG')]) {
          //sh "cp $MVN_BUILD_CACHE_CONFIG .mvn/maven-build-cache-config.xml"
          //-Dmaven.build.cache.configPath=$MVN_BUILD_CACHE_CONFIG
          buildCache = useBuildCache()
          if (buildCache) {
            echo "Using build cache"
            extraArgs = " -Dmaven.build.cache.restoreGeneratedSources=false -Dmaven.build.cache.remote.url=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-build-cache -Dmaven.build.cache.remote.enabled=true -Dmaven.build.cache.remote.save.enabled=true -Dmaven.build.cache.remote.server.id=nexus-cred "
          } else {
            // when not using cache
            echo "Not using build cache"
            extraArgs = " -Dmaven.test.failure.ignore=true -Dmaven.build.cache.skipCache=true -Dmaven.build.cache.remote.url=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-build-cache -Dmaven.build.cache.remote.enabled=true -Dmaven.build.cache.remote.save.enabled=true -Dmaven.build.cache.remote.server.id=nexus-cred "
          }
          if (env.BRANCH_NAME ==~ /PR-\d+/) {
            if (pullRequest.labels.contains("build-all-tests")) {
              extraArgs = " -Dmaven.test.failure.ignore=true "
            }
          }
          runLaunchable ("verify")
          runLaunchable ("record build --name jetty-12.0.x")
          dashProfile = ""
          if(useEclipseDash()) {
            dashProfile = " -Peclipse-dash "
          }
          sh "mvn $extraArgs $dashProfile -DsettingsPath=$GLOBAL_MVN_SETTINGS -Dmaven.repo.uri=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-public/ -ntp -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -Pci -V -B -e -U $cmdline"
          if(saveHome()) {
            archiveArtifacts artifacts: ".repository/org/eclipse/jetty/jetty-home/**/jetty-home-*", allowEmptyArchive: true, onlyIfSuccessful: false
          }
        }
      }
    }
    finally
    {
      junit testDataPublishers: [[$class: 'JUnitFlakyTestDataPublisher']], testResults: '**/target/surefire-reports/**/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
      echo "Launchable record tests"
      runLaunchable ("record tests --build jetty-12.0.x maven '**/target/surefire-reports/**/*.xml' '**/target/invoker-reports/TEST*.xml'")
    }
  }
}

/**
 * calculate to use cache or not. per default will not run
 */
def useBuildCache() {
  def labelNoBuildCache = false
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    labelNoBuildCache = pullRequest.labels.contains("build-no-cache") || pullRequest.labels.contains("dependencies")
  }
  def noBuildCache = (env.BRANCH_NAME == 'jetty-12.0.x') || labelNoBuildCache;
  return !noBuildCache;
  // want to skip build cache
  // return false
}

def useEclipseDash() {
  return pullRequest.labels.contains("eclipse-dash")
}

def saveHome() {
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    return pullRequest.labels.contains("save-home")
  }
  return false;
}

def websiteBuild() {
  script {
    try {
      if (env.BRANCH_NAME == 'jetty-10.0.x' || env.BRANCH_NAME == 'jetty-11.0.x' || env.BRANCH_NAME == 'jetty-12.0.x') {
        build(job: 'website/jetty.website/main', propagate: false, wait: false)
      }
    } catch (Exception e) {
      e.printStackTrace()
      echo "skip website build triggering: " + e.getMessage()
    }
  }
}
/**
 * run launchable with args and ignore any errors
 * @param args
 */
def runLaunchable(args) {
  try {
    sh "launchable $args"
  } catch (Exception e) {
    e.printStackTrace()
    echo "skip failure running Launchable: " + e.getMessage()
  }
}

// vim: et:ts=2:sw=2:ft=groovy
