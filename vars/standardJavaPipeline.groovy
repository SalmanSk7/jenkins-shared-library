def call(Map config = [:]) {
    pipeline {
        agent any

        tools {
            jdk 'jdk17'
            maven 'maven3'
        }

        environment {
            SCANNER_HOME = tool 'sonar-scanner'
        }

        stages {

            stage('gitCheckout') {
                steps {
                    git branch: config.repoBranch ?: 'main',
                        credentialsId: 'git-cred',
                        url: config.repoUrl
                }
            }

            stage('compileCode') {
                steps {
                    sh "mvn compile"
                }
            }

            stage('testCases') {
                steps {
                    sh "mvn test"
                }
            }

            stage('fileSystemScanTrivy') {
                steps {
                    sh "trivy fs --format table -o trivy-fs-report.html ."
                }
            }

            stage('sonarQubeAnalysis') {
                steps {
                    withSonarQubeEnv(config.sonarServer ?: 'sonar-boardgame') {
                        sh """${SCANNER_HOME}/bin/sonar-scanner \
                            -Dsonar.projectName=${config.sonarProjectName} \
                            -Dsonar.projectKey=${config.sonarProjectKey} \
                            -Dsonar.java.binaries=."""
                    }
                }
            }

            stage('buildApp') {
                steps {
                    sh "mvn package"
                }
            }

            stage('buildTagDockerImage') {
                steps {
                    withDockerRegistry(credentialsId: 'docker', url: 'https://index.docker.io/v1/') {
                        sh "docker build -t ${config.dockerImage} ."
                    }
                }
            }

            stage('dockerImageScan') {
                steps {
                    sh "trivy image --format table -o trivy-image-report.html ${config.dockerImage}"
                }
            }

            stage('pushDockerImage') {
                steps {
                    withDockerRegistry(credentialsId: 'docker', url: 'https://index.docker.io/v1/') {
                        sh "docker push ${config.dockerImage}"
                    }
                }
            }

            // Optional future: Add Kubernetes deploy here
        }

        post {
            always {
                script {
                    def jobName = env.JOB_NAME
                    def buildNumber = env.BUILD_NUMBER
                    def pipelineStatus = currentBuild.result ?: 'UNKNOWN'
                    def bannerColor = pipelineStatus.toUpperCase() == 'SUCCESS' ? 'green' : 'red'

                    def body = """
                        <html>
                        <body>
                        <div style="border: 4px solid ${bannerColor}; padding: 10px;">
                        <h2>${jobName} - Build ${buildNumber}</h2>
                        <div style="background-color: ${bannerColor}; padding: 10px;">
                        <h3 style="color: white;">Pipeline Status: ${pipelineStatus.toUpperCase()}</h3>
                        </div>
                        <p>Check the <a href="${env.BUILD_URL}">console output</a>.</p>
                        </div>
                        </body>
                        </html>
                    """

                    emailext(
                        subject: "${jobName} - Build ${buildNumber} - ${pipelineStatus.toUpperCase()}",
                        body: body,
                        to: config.emailRecipients,
                        from: 'jenkins@example.com',
                        replyTo: 'jenkins@example.com',
                        mimeType: 'text/html',
                        attachmentsPattern: 'trivy-image-report.html'
                    )
                }
            }
        }
    }
}
