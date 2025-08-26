import org.bcbsaz.VeracodeStaticScan
import org.bcbsaz.VeracodeStaticScanBuilder

void call(Map params) {

  Map config = [
    veracodeVersion: "24.10.15.0",
    veracodeAPIcredential: "Veracode_API_CREDENTIAL",
    action: "UploadAndScan",
    createProfile: 'true'
  ]

  config += params

  String nexusRegistry = 'http://lp-nex-a01.corp.net.bcbsaz.com:7000'

  VeracodeStaticScan veracodeStaticScan = new VeracodeStaticScanBuilder(this)
    .setAction(config.action)
    .setVersion(config.version)
    .setArguments(config.arguments)
    .setAppName(config.appName)
    .setCreateProfile(config.createProfile)
    .build()

  String volumes = ""

  Boolean volumesFound = volumesExists()

  if (volumesFound) {
    volumes += "-v ${BUILD_TAG}-tmp:/tmp -v ${BUILD_TAG}-local:/home/jenkins/.local"
  }

  String veracodeStaticScanImage = "devops/veracode-static-scan:${config.veracodeVersion}"
  String dockerRegistry = nexusRegistry.replace('7000', '5443')

  Integer statusCode = fetchDockerImage(dockerRegistry, veracodeStaticScanImage)

  withCredentials([usernamePassword(
                    credentialsId: config.veracodeAPIcredential,
                    usernameVariable: 'VERACODE_API_KEY_ID', passwordVariable: 'VERACODE_API_KEY_SECRET'
                   )])
  {
    if (statusCode == 0) {
      docker.withRegistry(dockerRegistry) {
        docker.image(veracodeStaticScanImage).inside(volumes) {
          return veracodeStaticScan.executeStaticScan()
        }
      }
    }

    else if (statusCode == 1) {
      buildDockerImage(nexusRegistry, veracodeStaticScanImage, config.veracodeVersion).inside(volumes) {
        return veracodeStaticScan.executeStaticScan()
      }

      dockerRegistry = nexusRegistry - ~'^http.*://'

      sh("docker image rm $veracodeStaticScanImage ${dockerRegistry}/$veracodeStaticScanImage")
    }
  }
}

Integer fetchDockerImage(String dockerRegistry, String image) {
  dockerRegistry = dockerRegistry - ~'^http.*://'

  return sh(
    script: "docker image pull ${dockerRegistry}/$image",
    returnStatus: true
  )
}

def buildDockerImage(String dockerRegistry, String image, String veracodeStaticScanImage) {
  writeFile(
    file: 'Dockerfile.veracodestaticscan',
    text: libraryResource('org/bcbsaz/Dockerfile.veracodestaticscan')
  )
  String dockerBuildArgs = "--build-arg VERACODE_VERSION=$veracodeStaticScanImage -f ./Dockerfile.veracodestaticscan ."

  docker.withRegistry(dockerRegistry) {
    def dockerImage = docker.build(image, dockerBuildArgs)
    dockerImage.push()

    return dockerImage
  }
}

String volumesExists() {
  sh(
    returnStatus: true,

    script: """#!/usr/bin/env bash

      TMP_VOLUME=`
        docker volume ls \
          --filter name=\${BUILD_TAG}-tmp \
          --format '{{.Name}}'
      `

      LOCAL_VOLUME=`
        docker volume ls \
          --filter name=\${BUILD_TAG}-local \
          --format '{{.Name}}'
      `

      VOLUMES_FOUND=1

      [[ -z \$TMP_VOLUME || -z \$LOCAL_VOLUME ]] && VOLUMES_FOUND=0

      exit \$VOLUMES_FOUND
    """
  )
}
