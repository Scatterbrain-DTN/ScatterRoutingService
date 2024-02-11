def recursiveCheckout() {
  checkout([$class: 'GitSCM', 
            branches: [[name: '**']], 
            extensions: [[$class: 'SubmoduleOption', 
                        depth: 1, 
                        disableSubmodules: false, 
                        parentCredentials: false, 
                        recursiveSubmodules: true, 
                        reference: '', 
                        shallow: true, 
                        trackingSubmodules: false]],
             userRemoteConfigs: [[url: 'https://github.com/Scatterbrain-DTN/ScatterRoutingService.git']]])
}

void setBuildStatus(String message, String state) {
/*
  step([
      $class: "GitHubCommitStatusSetter",
      reposSource: [$class: "ManuallyEnteredRepositorySource", url: "https://github.com/Scatterbrain-DTN/ScatterRoutingService"],
      contextSource: [$class: "ManuallyEnteredCommitContextSource", context: "ci/jenkins/build-status"],
      errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
      statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ]);
  */
}

pipeline {
    agent any
      tools {
            gradle "GRADLE_LATEST"
      }
    environment {
      ANDROID_HOME = '/opt/android-sdk-linux'
      ANDROID_SDK_ROOT = "$ANDROID_HOME"
      TARGET_VERSION = 33
      EMULATOR_PID = "/build/$BUILD_ID-emu.pid"
      AVD_NAME = "${EXECUTOR_NUMBER}-avd"
    }
    stages {
        stage('Build') {
            agent { label 'build' }
            steps {
                echo 'Building..'
                setBuildStatus("Build started", "PENDING")

                recursiveCheckout()
                withGradle {
                  sh label: 'gradle build', script: './gradlew build --stacktrace'
                }
                stash includes: 'app/build/**/*', name: 'build'
                stash includes: 'scatterbrainSDK/scatterbrainSDK/build/**/*', name: 'sdkbuild'
            }
            post {
              success {
                cleanWs()
              }
            }
        }
        stage('Test') {
          agent { label 'phone' }
          steps {
            echo 'Testing'
            recursiveCheckout()
            unstash name: 'build'
            unstash name: 'sdkbuild'
            withGradle {
            /*
              sh label: 'Downloadd system image', script: "$ANDROID_HOME/cmdline-tools/tools/bin/sdkmanager \"system-images;android-${TARGET_VERSION};google_apis;x86_64\" --sdk_root=$ANDROID_HOME"
              sh label: 'Create AVD', script: "echo no | $ANDROID_HOME/cmdline-tools/tools/bin/avdmanager create avd --force -n $AVD_NAME -k \"system-images;android-${TARGET_VERSION};google_apis;x86_64\""
              sh label: 'Start emulator and run tests', script: """
              $ANDROID_HOME/emulator/emulator -memory 1024 -avd $AVD_NAME -skin 768x1280 -no-boot-anim -no-audio -no-window -no-snapshot -no-snapshot-load -no-snapstorage -no-cache -verbose > ${EMULATOR_PID}.stdout &
              $ANDROID_HOME/platform-tools/adb wait-for-device shell 'while [[ -z \$(getprop sys.boot_completed) ]]; do sleep 1; done;'
              ./gradlew jacocoTestReport --stacktrace
              """
              */
              sh './gradlew testDebugUnitTest jacocoTestReport'
              recordCoverage(tools: [[parser: 'JACOCO']],
                      id: 'jacoco', name: 'JaCoCo Coverage',
                      sourceCodeRetention: 'EVERY_BUILD')
            }
          }
          post {
            success {
              cleanWs()
            }
          }
        }
    }
    post {
        success {
          setBuildStatus("Happy noises", "SUCCESS")
        }
        failure {
          setBuildStatus("Sad cry noises", "FAILURE")
        }
    }
}
