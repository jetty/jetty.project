#!groovy

pipeline {
  agent any
  // save some io during the build
  options { durabilityHint('PERFORMANCE_OPTIMIZED') }
  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test - JDK8") {
          agent { node { label 'linux' } }
          steps {
            container('jetty-build') {
              timeout( time: 120, unit: 'MINUTES' ) {
                mavenBuild( "jdk8", "clean install -T3", "maven3",
                            [[parserName: 'Maven'], [parserName: 'Java']])
                // Collect up the jacoco execution results (only on main build)
                jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                       exclusionPattern: '' +
                               // build tools
                               '**/org/eclipse/jetty/ant/**' + ',**/org/eclipse/jetty/maven/**' +
                               ',**/org/eclipse/jetty/jspc/**' +
                               // example code / documentation
                               ',**/org/eclipse/jetty/embedded/**' + ',**/org/eclipse/jetty/asyncrest/**' +
                               ',**/org/eclipse/jetty/demo/**' +
                               // special environments / late integrations
                               ',**/org/eclipse/jetty/gcloud/**' + ',**/org/eclipse/jetty/infinispan/**' +
                               ',**/org/eclipse/jetty/osgi/**' + ',**/org/eclipse/jetty/spring/**' +
                               ',**/org/eclipse/jetty/http/spi/**' +
                               // test classes
                               ',**/org/eclipse/jetty/tests/**' + ',**/org/eclipse/jetty/test/**',
                       execPattern: '**/target/jacoco.exec',
                       classPattern: '**/target/classes',
                       sourcePattern: '**/src/main/java'
              }
            }
          }
        }

        stage("Build / Test - JDK11") {
          agent { node { label 'linux' } }
          steps {
            container( 'jetty-build' ) {
              timeout( time: 120, unit: 'MINUTES' ) {
                mavenBuild( "jdk11", "clean install -T3 -Djacoco.skip=true ", "maven3",
                            [[parserName: 'Maven'], [parserName: 'Java']])
              }
            }
          }
        }

        stage("Build / Test - JDK15") {
          agent { node { label 'linux' } }
          steps {
            container( 'jetty-build' ) {
              timeout( time: 120, unit: 'MINUTES' ) {
                mavenBuild( "jdk15", "clean install -T3 -Djacoco.skip=true ", "maven3",
                            [[parserName: 'Maven'], [parserName: 'Java']])
              }
            }
          }
        }

        stage("Build Javadoc") {
          agent { node { label 'linux' } }
          steps {
            container( 'jetty-build' ) {
              timeout( time: 40, unit: 'MINUTES' ) {
                mavenBuild( "jdk11",
                            "install javadoc:javadoc javadoc:aggregate-jar -DskipTests -Dpmd.skip=true -Dcheckstyle.skip=true",
                            "maven3", [[parserName: 'Maven'], [parserName: 'JavaDoc'], [parserName: 'Java']])
              }
            }
          }
        }

        stage("Build Compact3") {
          agent { node { label 'linux' } }
          steps {
            container( 'jetty-build' ) {
              timeout( time: 30, unit: 'MINUTES' ) {
                mavenBuild( "jdk8", "-T3 -Pcompact3 clean install -DskipTests", "maven3",
                            [[parserName: 'Maven'], [parserName: 'Java']])
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
      try
      {
        if ( env.BRANCH_NAME == 'jetty-10.0.x' || env.BRANCH_NAME == 'jetty-9.4.x' )
        {
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
def mavenBuild(jdk, cmdline, mvnName, consoleParsers) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${env.JAVA_HOME}/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider(
                [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -Psnapshot-repositories -Premote-session-tests -Pci -V -B -e -Djetty.testtracker.log=true $cmdline -Dunix.socket.tmp=" +
                     env.JENKINS_HOME
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml'
      //archiveArtifacts artifacts: '**/jetty-webapp/target/**'
      if(consoleParsers!=null){
        warnings consoleParsers: consoleParsers
      }
    }
  }
}


// vim: et:ts=2:sw=2:ft=groovy
