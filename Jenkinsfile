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

pipeline {
    agent { label 'build' }
		environment {
			GRADLE_USER_HOME = '/build'
			ANDROID_HOME = '/opt/android-sdk-linux'
			ANDROID_SDK_ROOT = "$ANDROID_HOME"
			TARGET_VERSION = 31
			EMULATOR_PID = "/build/$BUILD_ID-emu.pid"
		}
    stages {
        stage('Build') {
            steps {
                echo 'Building..'
								recursiveCheckout()
								withGradle {
									sh './gradlew build --stacktrace'
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
					steps {
						echo 'Testing'
						recursiveCheckout()
						unstash name: 'build'
						unstash name: 'sdkbuild'
						withGradle {
							sh "$ANDROID_HOME/cmdline-tools/tools/bin/sdkmanager \"system-images;android-${TARGET_VERSION};google_apis;x86_64\" --sdk_root=$ANDROID_HOME"
							sh "echo no | $ANDROID_HOME/cmdline-tools/tools/bin/avdmanager create avd --force -n testavd -k \"system-images;android-${TARGET_VERSION};google_apis;x86_64\""
							sh """
							$ANDROID_HOME/emulator/emulator -memory 2048 -avd testavd  -skin 768x1280 -no-boot-anim -no-audio -no-window -no-snapshot -no-snapshot-load -no-snapstorage -no-cache -verbose > ${EMULATOR_PID}.stdout &
							$ANDROID_HOME/platform-tools/adb wait-for-device shell 'while [[ -z \$(getprop sys.boot_completed) ]]; do sleep 1; done;'
							./gradlew jacocoTestReport --stacktrace
							"""
							jacoco()
						}
					}
					post {
						success {
							cleanWs()
						}
					}
				}
        stage('Deploy') {
            steps {
                echo 'Deploying....'
            }
        }
    }
}
