#!groovy

pipeline {
  agent any
  // save some io during the build
  options {
    skipDefaultCheckout()
    durabilityHint('PERFORMANCE_OPTIMIZED')
  }
  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test - JDK17") {
          agent { node { label 'linux' } }
          steps {
            container('jetty-build') {
              timeout( time: 180, unit: 'MINUTES' ) {               
                checkout scm
                sh "ls -lrt /home/jenkins/"
                sh "ls -lrt /home/jenkins/jetty.project.git"
                mavenBuild( "jdk17", "clean install -Perrorprone", "maven3")
                // Collect up the jacoco execution results (only on main build)
                jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                       exclusionPattern: '' +
                               // build tools
                               '**/org/eclipse/jetty/ant/**' +
                               ',*/org/eclipse/jetty/maven/its/**' +
                               ',**/org/eclipse/jetty/its/**' +
                               // example code / documentation
                               ',**/org/eclipse/jetty/embedded/**' +
                               ',**/org/eclipse/jetty/asyncrest/**' +
                               ',**/org/eclipse/jetty/demo/**' +
                               // special environments / late integrations
                               ',**/org/eclipse/jetty/gcloud/**' +
                               ',**/org/eclipse/jetty/infinispan/**' +
                               ',**/org/eclipse/jetty/osgi/**' +
                               ',**/org/eclipse/jetty/http/spi/**' +
                               // test classes
                               ',**/org/eclipse/jetty/tests/**' +
                               ',**/org/eclipse/jetty/test/**',
                       execPattern: '**/target/jacoco.exec',
                       classPattern: '**/target/classes',
                       sourcePattern: '**/src/main/java'
                recordIssues id: "jdk17", name: "Static Analysis jdk17", aggregatingResults: true, enabledForFailure: true, tools: [mavenConsole(), java(), checkStyle(), errorProne(), spotBugs()]
              }
            }
          }
        }

        stage("Build / Test - JDK11") {
          agent { node { label 'linux' } }
          steps {
            container( 'jetty-build' ) {
              timeout( time: 180, unit: 'MINUTES' ) {
                checkout scm
                sh "ls -lrt /home/jenkins/"
                sh "ls -lrt /home/jenkins/jetty.project.git"                
                mavenBuild( "jdk11", "clean install -Dspotbugs.skip=true -Djacoco.skip=true", "maven3")
                recordIssues id: "jdk11", name: "Static Analysis jdk11", aggregatingResults: true, enabledForFailure: true, tools: [mavenConsole(), java(), checkStyle()]
              }
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
    }
  }
}

def slackNotif() {
  script {
    try {
      if ( env.BRANCH_NAME == 'jetty-10.0.x' || env.BRANCH_NAME == 'jetty-11.0.x') {
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
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider(
                [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          //sh "mvn --no-transfer-progress -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -Pci -DexcludedGroups=\"external, large-disk-resource, stress, slow\" -V -B -e -Djetty.testtracker.log=true $cmdline"
          sh "env"
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
