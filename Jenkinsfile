#!groovy

pipeline {
    agent any
    options { durabilityHint('PERFORMANCE_OPTIMIZED') }
    stages {
        stage("Parallel Stage") {
            parallel {
                stage("Build / Test - JDK11") {
                    agent { node { label 'linux' } }
                    options { timeout(time: 120, unit: 'MINUTES') }
                    steps {
                        mavenBuild("jdk11", "-Pautobahn -Pmongodb install", "maven3")
                        warnings consoleParsers: [[parserName: 'Maven'], [parserName: 'Java']]
                        // Collect up the jacoco execution results (only on main build)
                        jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                            exclusionPattern: '' +
                                // build tools
                                '**/org/eclipse/jetty/ant/**' +
                                ',**/org/eclipse/jetty/maven/**' +
                                ',**/org/eclipse/jetty/jspc/**' +
                                // example code / documentation
                                ',**/org/eclipse/jetty/embedded/**' +
                                ',**/org/eclipse/jetty/asyncrest/**' +
                                ',**/org/eclipse/jetty/demo/**' +
                                // special environments / late integrations
                                ',**/org/eclipse/jetty/gcloud/**' +
                                ',**/org/eclipse/jetty/infinispan/**' +
                                ',**/org/eclipse/jetty/osgi/**' +
                                ',**/org/eclipse/jetty/spring/**' +
                                ',**/org/eclipse/jetty/http/spi/**' +
                                // test classes
                                ',**/org/eclipse/jetty/tests/**' +
                                ',**/org/eclipse/jetty/test/**',
                            execPattern: '**/target/jacoco.exec',
                            classPattern: '**/target/classes',
                            sourcePattern: '**/src/main/java'
                        warnings consoleParsers: [[parserName: 'Maven'], [parserName: 'Java']]
                        junit '**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml,**/autobahntestsuite-reports/*.xml'
                        maven_invoker reportsFilenamePattern: "**/target/invoker-reports/BUILD*.xml", invokerBuildDir: "**/target/its"
                    }
                }
                stage("Build Javadoc") {
                    agent { node { label 'linux' } }
                    options { timeout(time: 30, unit: 'MINUTES') }
                    steps {
                        mavenBuild("jdk11", "install javadoc:javadoc -DskipTests", "maven3")
                        warnings consoleParsers: [[parserName: 'Maven'], [parserName: 'JavaDoc'], [parserName: 'Java']]
                    }
                }

                /* Deprecated in Jetty build, will be removed in future.
                stage("Build Compact3") {
                    agent { node { label 'linux' } }
                    options { timeout(time: 120, unit: 'MINUTES') }
                    steps {
                        mavenBuild("jdk11", "-Pcompact3 install -DskipTests")
                        warnings consoleParsers: [[parserName: 'Maven'], [parserName: 'Java']]
                    }
                }
                */
            }
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
 * @paran mvnName maven installation to use
 * @return the Jenkinsfile step representing a maven build
 */
def mavenBuild(jdk, cmdline, mvnName) {
  def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}" // ".repository" //
  def mavenOpts = '-Xms1g -Xmx4g -Djava.awt.headless=true'

    withMaven(
        maven: mvnName,
        jdk: "$jdk",
        publisherStrategy: 'EXPLICIT',
        options: [mavenLinkerPublisher(disabled: false),pipelineGraphPublisher(disabled: false)],
        mavenOpts: mavenOpts,
        mavenLocalRepo: localRepo) {
        // Some common Maven command line + provided command line
        sh "mvn -V -B -T3 -e -fae -Dmaven.test.failure.ignore=true -Djetty.testtracker.log=true $cmdline -Dunix.socket.tmp=" + env.JENKINS_HOME
    }
}

// vim: et:ts=2:sw=2:ft=groovy
