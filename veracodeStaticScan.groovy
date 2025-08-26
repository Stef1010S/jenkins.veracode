// vars/veracodeStaticScan.groovy
import org.bcbsaz.VeracodeStaticScan
import org.bcbsaz.VeracodeStaticScanBuilder

def call(Map params = [:]) {
  // defaults
  Map config = [
    veracodeVersion      : "24.10.15.0",
    veracodeAPIcredential: "Veracode_API_CREDENTIAL",
    action               : "UploadAndScan",
    createProfile        : 'true',
    appName              : (params.appName ?: env.JOB_BASE_NAME),
    version              : params.version,          // may be null; class will default
    arguments            : (params.arguments ?: [:]),
    files                : (params.files ?: null),  // explicit artifacts (absolute or relative)
    autoPackage          : (params.autoPackage ?: false), // NEW: run "veracode package"
    autoPackageOutputDir : (params.autoPackageOutputDir ?: 'verascan'), // where CLI writes
  ]
  config += params  // allow overrides

  // Prepare arguments map that the class will turn into CLI options
  Map argMap = [:] + (config.arguments ?: [:])

  // If no explicit files and autoPackage requested, run the CLI packager now
  List<String> filesForUpload = []
  if (!config.files && config.autoPackage) {
    // Ensure output dir exists & is clean
    sh "rm -rf '${config.autoPackageOutputDir}' && mkdir -p '${config.autoPackageOutputDir}'"

    // Run auto-packager against the CURRENT WORKSPACE (CWD)
    // This will build/collect artifacts per language and drop them into output dir
    // See: veracode package --source <path> --output <dir> --trust
    sh """
      set -e
      echo "[AutoPack] Packaging repo in \$(pwd) -> ${config.autoPackageOutputDir}"
      veracode package --source . --output '${config.autoPackageOutputDir}' --trust
    """

    // Collect absolute artifact paths from the output dir
    def out = sh(returnStdout: true, script: "ls -1 '${config.autoPackageOutputDir}' 2>/dev/null || true").trim()
    if (out) {
      out.readLines().each { fn ->
        def abs = sh(returnStdout: true, script: "readlink -f '${config.autoPackageOutputDir}/${fn}' || realpath '${config.autoPackageOutputDir}/${fn}'").trim()
        if (abs) filesForUpload << abs
      }
    }

    if (!filesForUpload) {
      echo "[AutoPack] No artifacts produced in ${config.autoPackageOutputDir}. Will return without upload."
    }
  }

  // If caller provided explicit files, prefer those
  if (config.files) {
    // Resolve to absolute paths for uploadandscan (wrapper requires real files)
    (config.files as List).each { fp ->
      def abs = sh(returnStdout: true, script: "readlink -f '${fp}' || realpath '${fp}'").trim()
      if (abs) filesForUpload << abs
    }
  }

  // Pass the files to the class as filepaths, if any
  if (filesForUpload) {
    argMap.filepaths = filesForUpload
  }

  // --- Docker run exactly like before ---
  String nexusRegistry  = 'http://lp-nex-a01.corp.net.bcbsaz.com:7000'
  String dockerRegistry = nexusRegistry.replace('7000', '5443')
  String veracodeImage  = "devops/veracode-static-scan:${config.veracodeVersion}"

  String volumes = ""
  if (volumesExists()) {
    volumes += "-v ${BUILD_TAG}-tmp:/tmp -v ${BUILD_TAG}-local:/home/jenkins/.local"
  }

  Integer statusCode = fetchDockerImage(dockerRegistry, veracodeImage)

  withCredentials([usernamePassword(
    credentialsId   : config.veracodeAPIcredential,
    usernameVariable: 'VERACODE_API_KEY_ID',
    passwordVariable: 'VERACODE_API_KEY_SECRET'
  )]) {
    if (statusCode == 0) {
      return docker.withRegistry(dockerRegistry) {
        docker.image(veracodeImage).inside(volumes) {
          VeracodeStaticScan scan = new VeracodeStaticScanBuilder(this)
            .setAction(config.action)
            .setVersion(config.version)
            .setArguments(argMap)          // includes filepaths if any
            .setAppName(config.appName)
            .setCreateProfile(config.createProfile)
            .build()
          return scan.executeStaticScan()  // <— keep your pattern
        }
      }
    } else {
      def built = buildDockerImage(nexusRegistry, veracodeImage, config.veracodeVersion)
      def result = built.inside(volumes) {
        VeracodeStaticScan scan = new VeracodeStaticScanBuilder(this)
          .setAction(config.action)
          .setVersion(config.version)
          .setArguments(argMap)
          .setAppName(config.appName)
          .setCreateProfile(config.createProfile)
          .build()
        return scan.executeStaticScan()
      }
      String regNoProto = (nexusRegistry - ~'^http.*://')
      sh("docker image rm ${veracodeImage} ${regNoProto}/${veracodeImage} || true")
      return result
    }
  }
}
