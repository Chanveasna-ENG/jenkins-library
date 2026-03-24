def call(Map config = [:]) {
    String repoUrl = config.get('repoUrl', 'https://github.com/Chanveasna-ENG/jenkins-library')
    String branch = config.get('branch', 'main')
    String projectDir = config.get('projectDir', '/home/user/project')
    String serviceName = config.get('serviceName', 'my-app')
    String appSubdir = config.get('appSubdir', 'jenkins-library')
    String targetPort = config.get('targetPort', '6000') 
    String testPort = config.get('testPort', '5000') 
    String cacheDir = config.get('cacheDir', '/home/user/cache')

    pipeline {
        agent any

        environment {
            TRIVY_IMAGE = 'aquasec/trivy:0.69.3'
            SEMGREP_IMAGE = 'returntocorp/semgrep'
            ZAP_IMAGE = 'owasp/zap2docker-stable'
            FULL_APP_PATH = "${projectDir}/${appSubdir}"
			STAGING_DIR = "${projectDir}/jenkins_staging/${env.JOB_NAME}"
            SEMGREP_APP_TOKEN = credentials('SEMGREP_APP_TOKEN')
        }

        stages {
            stage('Checkout') {
                steps {
                    retry(3) {
                        git url: repoUrl, branch: branch
                    }
                }
            }

			stage('Staging') {
                steps {
                    sh """
                    mkdir -p ${STAGING_DIR}
                    rsync -a --delete ./ ${STAGING_DIR}/
                    """
                }
            }

            stage('Security: Static Analysis (Semgrep Pro)') {
                steps {
                    sh """
                    docker run --rm \\
                      -v "${STAGING_DIR}:/src" \\
                      -v "${cacheDir}/semgrep:/root/.cache/semgrep" \\
                      -e SEMGREP_APP_TOKEN=\${SEMGREP_APP_TOKEN} \\
					  -w /src \\
					  ${env.SEMGREP_IMAGE} semgrep ci
                    """
                }
            }

            stage('Security: Dependency Scan (Trivy FS)') {
                steps {
                    sh """
                    docker run --rm \\
                      -v "${STAGING_DIR}:/src" \\
                      -v "${cacheDir}/trivy:/root/.cache/trivy" \\
					  -w /src \\
                      ${env.TRIVY_IMAGE} fs \\
					  --scanners vuln,secret,misconfig \\
                      --severity HIGH,CRITICAL \\
                      --exit-code 1 \\
                      .
                    """
                }
            }

            stage('Build Image') {
                steps {
                    sh """
                    cd ${STAGING_DIR}
                    docker build -t ${serviceName}:test .
                    """
                }
            }

            stage('Security: Image Scan (Trivy Image)') {
                steps {
                    sh """
                    docker run --rm \\
                      -v /var/run/docker.sock:/var/run/docker.sock \\
                      -v ${cacheDir}/trivy:/root/.cache/trivy \\
                      ${env.TRIVY_IMAGE} image \\
                      --severity HIGH,CRITICAL \\
                      --exit-code 1 \\
                      ${serviceName}:test
                    """
                }
            }

            stage('Deploy Test Instance') {
                steps {
                    sh "docker rm -f ${serviceName}-test || true"
                    sh "docker run -d --name ${serviceName}-test -p ${testPort}:${targetPort} ${serviceName}:test"
                    sh "sleep 15" 
                }
            }

            stage('Security: Live Attack (OWASP ZAP)') {
                steps {
                    sh "docker run --rm --network=host ${env.ZAP_IMAGE} zap-baseline.py -t http://localhost:${testPort} -r zap_report.html"
                }
                post {
                    always {
                        sh "docker rm -f ${serviceName}-test || true"
                    }
                }
            }

            stage('Deploy Production') {
                steps {
                    sh """
                    docker tag ${serviceName}:test ${serviceName}:latest
                    mkdir -p ${FULL_APP_PATH}
                    rsync -a --delete --exclude='.git' ${STAGING_DIR}/ ${FULL_APP_PATH}/
                    cd ${projectDir}
                    docker compose up -d ${serviceName}
                    """
                }
            }
        }

        post {
            always {
                cleanWs()
				sh "rm -rf ${STAGING_DIR} || true"
                sh 'docker image prune -f || true'
                sh 'docker container prune -f || true'
            }
        }
    }
}