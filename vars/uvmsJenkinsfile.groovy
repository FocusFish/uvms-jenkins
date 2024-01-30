def call(body) {
  
  // START evaluate the body block, and collect configuration into the object
  def inParams = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = inParams
  body()
  //END evaluate

  def MAVEN_PROFILES          = (inParams.MAVEN_PROFILES)         ?: ''
  def MAVEN_PROFILES_RELEASE   = (inParams.MAVEN_PROFILES_RELEASE) ?: ''
  def MAVEN_OPTS_RELEASE       = (inParams.MAVEN_OPTS_RELEASE)     ?: '-DskipTests'
  def Boolean DOCKER          = (inParams.DOCKER)                 ?: false
  def SONAR_JDK_TOOL          = 'JDK17'

  def version

  node('uvms') {
    checkout scm
    withMaven(maven: 'Maven3', globalMavenSettingsConfig: 'focus_maven_settings.xml') {
      def currentVersion = sh script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout | tail -1', returnStdout: true
      version = currentVersion.replace("-SNAPSHOT", "")
    }
  }

  def parameterList = []
  if (env.BRANCH_NAME == 'develop') {
    parameterList.add(booleanParam(defaultValue: false, name: 'RELEASE', description: 'Create a release'))
    parameterList.add(string(name: 'VERSION', defaultValue: version, description: 'Release version'))
  }
  properties([parameters(parameterList)])

  pipeline {
    agent {
      label 'uvms'
    }
    options {
      buildDiscarder(logRotator(numToKeepStr: '10 '))
    }
    tools {
      maven 'Maven3'
      jdk 'JDK11'
    }
    triggers {
      issueCommentTrigger('.*test me.*')
    }
    stages {
      stage('Remove docker containers') {
        when {
          allOf {
            expression { DOCKER }
            expression { !params.RELEASE }
          }
        }
        steps {
          sh 'docker ps -a -q|xargs -r docker rm -f'
        }
      }
      stage('Build') {
        when {
          allOf {
            not { branch 'main' }
            expression { !params.RELEASE }
          }
        }
        steps {
          withMaven(maven: 'Maven3', globalMavenSettingsConfig: 'focus_maven_settings.xml') {
            sh "mvn clean deploy $MAVEN_PROFILES -Dci=true -U"
            script {currentBuild.displayName = "#${BUILD_NUMBER}- Built " +   readMavenPom().getVersion()}
          }
        }
      }
      stage('Build release') {
        when {
          allOf {
            branch 'main'
            expression { !params.RELEASE }
          }
        }
        steps {
          withMaven(maven: 'Maven3', globalMavenSettingsConfig: 'focus_maven_settings.xml') {
            sh "mvn clean deploy $MAVEN_OPTS_RELEASE $MAVEN_PROFILES_RELEASE -Dci=true -U"
            script {currentBuild.displayName = "#${BUILD_NUMBER}- Released " + readMavenPom().getVersion()}
          }
        }
      }
      stage('SonarQube analysis') {
        when {
          allOf {
            branch 'develop'
            expression { !params.RELEASE }
          }
        }
        steps{ 
          withSonarQubeEnv('Sonarqube.com') {
            withMaven(maven: 'Maven3', globalMavenSettingsConfig: 'focus_maven_settings.xml', jdk: "${SONAR_JDK_TOOL}") {
              sh "mvn $SONAR_MAVEN_GOAL -Dsonar.dynamicAnalysis=reuseReports -Dsonar.host.url=$SONAR_HOST_URL -Dsonar.login=$SONAR_AUTH_TOKEN $SONAR_EXTRA_PROPS"
            }
          }
        }
      }
      stage('Release') {
        when {
          allOf {
            branch 'develop'
            expression { params.RELEASE }
          }
        }
        steps {
          git branch: 'main', url: "$GIT_URL"
          git branch: 'develop', url: "$GIT_URL"
          withMaven(maven: 'Maven3', globalMavenSettingsConfig: 'focus_maven_settings.xml') {
            sh "git config user.name uvmsci"
            sh "git config user.email uvmsci@gmail.com"
            sh "mvn -B gitflow:release -DskipTestProject -DreleaseVersion=${VERSION} -DversionsForceUpdate=true"
            
            script {currentBuild.displayName = "#${BUILD_NUMBER}- Start release of ${VERSION}"}
          }
        }
      }
    }
    post {
      always {
        deleteDir()
      }
    }
  }
}
