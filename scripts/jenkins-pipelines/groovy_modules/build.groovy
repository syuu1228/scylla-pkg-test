// Creates a maven cache (if not already existing) and returns a "docker run"
// flag to mount it in a container
def gitMirrorVolumeMount() {
	def mirror_git_dir = "$env.HOME/mirror.git"
	def mirror_git_opt = ""
	if (fileExists(mirror_git_dir)) {
		mirror_git_opt = "-v ${mirror_git_dir}:${mirror_git_dir}"
	}
	return mirror_git_opt
}

String dockerMounts() {
	// configure ccache
	String dockerMountsEnvs = "-v /var/cache/ccache:/var/cache/ccache:z"
	dockerMountsEnvs += " -e CCACHE_DIR=/var/cache/ccache"
	dockerMountsEnvs += " -e CCACHE_UMASK=002"
	dockerMountsEnvs += " -e PATH=/usr/lib64/ccache:/usr/bin:/usr/sbin"
	return dockerMountsEnvs
}

String dbuildCmd(String architecture = generalProperties.x86ArchName) {
	def gitMirror = ""
	def dockerMountsEnvs = ""

	if (architecture == generalProperties.x86ArchName) {
		gitMirror = gitMirrorVolumeMount()
		dockerMountsEnvs = dockerMounts()
	}

	return "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/tools/toolchain/dbuild $gitMirror $dockerMountsEnvs "
}

def calcNumberOfJobs () {
	echo "Calc number of available jobs for compilation |${gitProperties.scyllaCheckoutDir}/scripts/jobs|"
	def numberJobs = 16
	numberJobs = sh (
		script: "${gitProperties.scyllaCheckoutDir}/scripts/jobs",
		returnStdout: true
	).trim()
	echo "Number of jobs according to script is $numberJobs"
	return numberJobs
}

def createDistBuildMetadataFile (Map args) {
	// Create a build metadatafile.
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): scyllaSha - The sha you've got when checking out scylla git repo
	// String (default null for all): buildMode Which mode(s) to build: dev|release|debug
	// String (default branchProperties.relocPackageCloudPathPrefix): baseRelocCloudStorageUrl - Base URL for reloc artifacts on cloud storage
	// String (default branchProperties.buildPackagesCloudPathPrefix(:) baseBuildPackagesCloudStorageUrl - Base URL for RPMs and Debs (build artifacts)
	// String (mandatory): urlId - ID for the cloud URL. Created by artifact.cloudBuildIdPath
	// String (mandatory): utcTextTimeStamp - Created by artifact.cloudBuildIdPath just before building
	// String (default $WORKSPACE/generalProperties.buildMetadataFile) buildMetadataFile - file to write
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("build.createDistBuildMetadataFile", args)
	general.errorMissingMandatoryParam ("build.createDistBuildMetadataFile",
		[scyllaSha: "$args.scyllaSha",
		 urlId: "$args.urlId",
		 utcTextTimeStamp: "$args.utcTextTimeStamp"])

	// If we decide to add Scylla version to the ID, this is the place to add it.
 	String urlId = args.urlId
	String buildMode = args.buildMode ?: ""
	String baseRelocCloudStorageUrl = args.baseRelocCloudStorageUrl ?: "${branchProperties.relocPackageCloudPathPrefix}/$urlId"
	String baseBuildPackagesCloudStorageUrl = args.baseBuildPackagesCloudStorageUrl ?: "${branchProperties.buildPackagesCloudPathPrefix}/$urlId"
	boolean dryRun = args.dryRun ?: false
	String buildMetadataFile = args.buildMetadataFile ?: "$WORKSPACE/${generalProperties.buildMetadataFile}"
	String architecture = args.architecture ?: ""
	if (architecture) {
		architecture = "${architecture}-"
	}

	String scyllaId

	if (dryRun) {
		scyllaId = "dryRun"
		scyllaVersion = "dryRun"
	} else {
		scyllaId = general.runOrDryRunShOutput (dryRun, "cat $WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/SCYLLA-RELEASE-FILE", "Get Scylla Id value from SCYLLA-RELEASE-FILE to write on metadata file.")
		scyllaVersion = general.runOrDryRunShOutput (dryRun, "cat $WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/SCYLLA-VERSION-FILE", "Get Scylla Version value from SCYLLA-VERSION-FILE to write on metadata file.")
	}

	sh """
		echo \"timestamp: $args.utcTextTimeStamp\" > $buildMetadataFile
		echo \"${gitProperties.scyllaRepoUrl}-sha: $args.scyllaSha\" >> $buildMetadataFile
		echo \"jenkins-job-path: ${env.JOB_NAME}\" >> $buildMetadataFile
		echo \"jenkins-job-number: ${env.BUILD_NUMBER}\" >> $buildMetadataFile
		echo \"url-id: $urlId\" >> $buildMetadataFile
		echo \"reloc-pack-url: $baseRelocCloudStorageUrl/\" >> $buildMetadataFile
		echo \"rpm-deb-pack-url: $baseBuildPackagesCloudStorageUrl/\" >> $buildMetadataFile
		printf \"scylla-product: \" >> $buildMetadataFile
	"""

	general.runOrDryRunSh (dryRun, "cat $WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/SCYLLA-PRODUCT-FILE >> $buildMetadataFile", "write scylla-product from SCYLLA-PRODUCT-FILE to metadata file")
	sh ("echo \"scylla-release: $scyllaId\" >> $buildMetadataFile")
	sh ("echo \"scylla-version: $scyllaVersion\" >> $buildMetadataFile")
	if (buildMode.contains("release")) {
		String scyllaShaFile = "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/release/scylla"
		boolean exists = dryRun || fileExists("$scyllaShaFile")
		if (exists) {
			sh ("printf \"scylla-${architecture}\" >> $buildMetadataFile")
			if (dryRun) {
				sh ("echo BuildID[sha1]: dryRun >> $buildMetadataFile")
			} else {
				general.runOrDryRunSh (dryRun, "file $scyllaShaFile | sed -r -e 's/^.*(BuildID.*)=/\\1: /' -e 's/,.*\$//' >> $buildMetadataFile", "write SHA from scylla file to metadata file")
			}
		} else {
			error ("Could not find $scyllaShaFile to get SHA of Scylla for metadata file")
		}
	}
	if (buildMode.contains("debug")) {
		String scyllaShaFile = "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/debug/scylla"
		boolean exists = dryRun || fileExists("$scyllaShaFile")
		if (exists) {
			sh ("printf \"scylla-debug-${architecture}\" >> $buildMetadataFile")
			if (dryRun) {
				sh ("echo BuildID[sha1]: dryRun >> $buildMetadataFile")
			} else {
				general.runOrDryRunSh (dryRun, "file $scyllaShaFile | sed -r -e 's/^.*(BuildID.*)=/\\1: /' -e 's/,.*\$//' >> $buildMetadataFile", "write debug SHA from scylla file to metadata file")
			}
		} else {
			error ("Could not find $scyllaShaFile to get SHA of Scylla for metadata file")
		}
	}
	sh ("echo \"unified-pack-url: $baseRelocCloudStorageUrl/${branchProperties.productName}-unified-package-${scyllaVersion}.${scyllaId}.tar.gz\" >> $buildMetadataFile")
	return [scyllaId, scyllaVersion]
}

def setConfigurePyParams (Map args) {
	// Create the command parameters for configure.py script that defines scylla build

	// Parameters:
	// boolean (default true): debugInfoFlag - Debug option
	// boolean (default false): testsDebugInfoFlag - Debug option for tests
	// String (default null): withArtifacts - will be used on --with parameter to ninja
	// String (default null): buildMode Which mode(s) to build: null for all, or dev|release|debug
	// String (default null): userRequestedParam - Any strings of parameters to ninja (take as is).
	general.traceFunctionParams ("build.setConfigurePyParams", args)

	boolean debugInfoFlag = args.debugInfoFlag != null ? args.debugInfoFlag : true
	boolean testsDebugInfoFlag = args.testsDebugInfoFlag ?: false
	String withArtifacts = args.withArtifacts ?: ""
	String buildMode = args.buildMode ?: ""
	String userRequestedParam = args.userRequestedParam ?: ""

	String configurePyParams = ""

	if (debugInfoFlag) {
		configurePyParams += " --debuginfo 1"
	}

	if (testsDebugInfoFlag) {
		configurePyParams += " --tests-debuginfo 1"
	} else {
		configurePyParams += " --tests-debuginfo 0"
	}

	if (withArtifacts) {
		configurePyParams += " --with=${args.withArtifacts}"
	}

	if (userRequestedParam) {
		configurePyParams += " $userRequestedParam"
	}

	if (buildMode) {
		configurePyParams += " --mode=$buildMode"
	}

	return configurePyParams
}

def scyllaDistBuild (Map args) {
	// Build scylla and packages with ninja

	// Parameters:
	// Map of parameters sent to setConfigurePyParams function that will set defaults. Documented there.
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): buildDocker - build Scylla docker image
	// String (default x86_64): architecture Which architecture to publish x86_64|aarch64
	// String (default generalProperties.jobSummaryFile) - jobSummaryFile File name to write result line on (as property).
	general.traceFunctionParams ("build.scyllaDistBuild", args)

	boolean dryRun = args.dryRun ?: false
	String architecture = args.architecture ?: generalProperties.x86ArchName
	String jobSummaryFile = args.jobSummaryFile ?: generalProperties.jobSummaryFile
	boolean buildDocker= args.buildDocker?: false

	jobSummaryFile = "$WORKSPACE/$jobSummaryFile"
	String buildOutputFile = "output-build-${architecture}.txt"

	String configurePyParams = setConfigurePyParams (args)

	def numberJobs = calcNumberOfJobs()

	def dbuildBaseCommand = dbuildCmd(architecture)

	String dockerImageId

	dir("${gitProperties.scyllaCheckoutDir}") {
		try {
			general.runOrDryRunSh (dryRun, "bash -c set -o pipefail; $dbuildBaseCommand -- ./configure.py $configurePyParams 2>&1 | tee $buildOutputFile", "Building Scylla with dist")
			general.runOrDryRunSh (dryRun, "bash -c set -o pipefail; $dbuildBaseCommand -- ninja -j $numberJobs 2>&1 | tee --append $buildOutputFile", "Building Scylla unit test")
			if (buildDocker) {
				general.runOrDryRunSh (dryRun, "bash -c set -o pipefail; ./${generalProperties.dockerInstallationScript} 2>&1 | tee --append $buildOutputFile", "Building Scylla Docker image")
				localDockerImageName = general.runOrDryRunShOutput (dryRun, "cat $buildOutputFile | grep image= | sed -e 's/.*image=//'", "Get local image name")
				general.runOrDryRunSh (dryRun, "$generalProperties.defaultContainerTool pull $localDockerImageName", "Pull new docker image")
				dockerImageId = general.runOrDryRunShOutput (dryRun, "$generalProperties.defaultContainerTool images -q | head -1", "Get docker image id")
			}
			sh "echo 'build_${architecture}=Success' >> $jobSummaryFile"
		} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interruptEx) {
			currentBuild.result = 'ABORTED'
			sh "echo 'build_${architecture}=Aborted' >> $jobSummaryFile"
			error ("Build $architecture aborted. Aborting the job. Error: |$interruptEx|")
		} catch (error) {
			artifact.publishArtifactsStatus(buildOutputFile, "$WORKSPACE/${gitProperties.scyllaCheckoutDir}", "", dryRun)
			sh "echo 'build_${architecture}=Error' >> $jobSummaryFile"
			error ("Build $architecture failed, error: |$error|.")
		} finally {
			echo "Build $architecture passed"
			artifact.publishArtifactsStatus(buildOutputFile, "$WORKSPACE/${gitProperties.scyllaCheckoutDir}", "", dryRun)
			return dockerImageId
		}
	}
}

def machineImageBuild (boolean dryRun=false, boolean buildRpm=true) {
	def dbuildBaseCommand = dbuildCmd()
	dir (gitProperties.scyllaMachineImageCheckoutDir) {
		if (buildRpm) {
			env.build_rpm_flags="-t centos"
			general.runOrDryRunSh (dryRun, "$dbuildBaseCommand -- ./dist/redhat/build_rpm.sh $build_rpm_flags", "Building Machine Image CentOS RPM")
		} else {
			general.runOrDryRunSh (dryRun, "$dbuildBaseCommand -- ./dist/debian/build_deb.sh", "Building Machine Image Ubuntu DEB")
		}
		if (dryRun) {
			echo "machineImageBuild: dry run. Skipping build of yaml file"
		} else {
			scyllaMachineImageId = sh (
				script: "cat $WORKSPACE/${gitProperties.scyllaMachineImageCheckoutDir}/build/SCYLLA-RELEASE-FILE",
				returnStdout: true
			).trim()
			scyllaMachineImageVersion = sh (
				script: "cat $WORKSPACE/${gitProperties.scyllaMachineImageCheckoutDir}/build/SCYLLA-VERSION-FILE",
				returnStdout: true
			).trim()
		}
	}
}

def createRpmRepo (Map args) {
	// Create a tmp repo and upload to CentOS RPMs to cloud
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): buildArm - Whether to create ARM repo.
	// String (mandatory): rpmCloudID ID for the could URL
	// String (default release): buildMode - debug or release
	// String (mandatory): rpmUrl - URL to put artifacts on. "None" if not needed to upload (byo)

	general.traceFunctionParams ("build.createRpmRepo", args)
	general.errorMissingMandatoryParam ("build.createRpmRepo", [rpmCloudID: "$args.rpmCloudID", rpmUrl: "$args.rpmUrl"])

	boolean dryRun = args.dryRun ?: false
	boolean buildArm = args.buildArm ?: false
	String buildMode = args.buildMode ?: "release"

	String dpackagerCommand = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}"
	dpackagerCommand += " -e build_mode=\"$buildMode\""
	dpackagerCommand += " -e artifacts_prefix=\"${branchProperties.productName}\""
	dpackagerCommand += " -e s3_repo_path_prefix=\"${args.rpmUrl}\""

	if (buildArm) {
		dpackagerCommand += " -e build_arm=true"
	}

	String scriptsPath = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.pipelinesShellPath}"

	general.runOrDryRunSh (dryRun, "$dpackagerCommand -- ${scriptsPath}/centos-rpm-unstable-repo.sh", "Building Unstable (dev) CentOS RPM repo")
}

def createUnifiedDebRepo (Map args) {
	// Create a tmp repo and upload to cloud
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): buildArm - Whether to create ARM repo.
	// boolean (default false): uploadToCloudStorage - Whether to publish to cloud storage (S3) or not.
	// String (mandatory): unifiedDebCloudID ID for the could URL
	// String (default release): buildMode - debug or release
	// String (mandatory): unifiedDebUrl - URL to put artifacts on. "None" if not needed to upload (byo)

	general.traceFunctionParams ("build.createUnifiedDebRepo", args)
	general.errorMissingMandatoryParam ("build.createUnifiedDebRepo", [unifiedDebCloudID: "$args.unifiedDebCloudID",
		unifiedDebUrl: "$args.unifiedDebUrl"])

	boolean dryRun = args.dryRun ?: false
	boolean uploadToCloudStorage = args.uploadToCloudStorage ?: false
	String buildMode = args.buildMode ?: "release"
	boolean buildArm = args.buildArm ?: false

	String scriptsPath = "$WORKSPACE/$gitProperties.scyllaPkgCheckoutDir/$generalProperties.pipelinesShellPath"

	dpackagerCommand = general.setGpgDpackagerCommand ("", "$WORKSPACE/$gitProperties.scyllaPkgCheckoutDir")
	if (buildMode == "release") {
		dpackagerCommand += " -e scylla_pkg_repo=\"$branchProperties.scyllaUnifiedPkgRepo\""
		dpackagerCommand += " -e list_file=\"$branchProperties.listFileName\""
	} else {
		dpackagerCommand += " -e scylla_pkg_repo=\"$branchProperties.scyllaDebugUnifiedPkgRepo\""
		dpackagerCommand += " -e list_file=\"$branchProperties.debugListFileName\""
	}
	if (buildArm) {
		dpackagerCommand += " -e build_arm=true"
	}
	dpackagerCommand += " -e s3_repo_path_prefix=\"$args.unifiedDebUrl\""

	general.runOrDryRunSh (dryRun, "$dpackagerCommand -- $scriptsPath/unified-deb-unstable-repo.sh", "Building Unstable (dev) Unified Deb repo for mode $buildMode")
}

def publishScyllaDistArtifacts(Map args) {
	// Publish Artifact to cloud
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false) uploadGeneralModeArtifacts - Upload all artifacts, including the generic mode items.
	// String (default release): buildMode Which mode(s) to build: dev|release|debug
	// String (default x86_64): architecture Which architecture to publish x86_64|aarch64
	// String (default null): archPackDeb Which architecture to publish tars and deb files x86_64|aarch64.
	//        This is a temp solution, till we integrate arm to  all processes.
	// String (Mandatory) scyllaRelocFullUrl - where on cloud to put the artifacts.
	// String (Mandatory) scyllaBuildPackagesFullUrl - where on cloud to put rpms and debians
	// String (default generalProperties.jobSummaryFile) - jobSummaryFile File name to write result line on (as property).
	general.traceFunctionParams ("build.publishScyllaDistArtifacts", args)

	String buildMode = args.buildMode ?: "release"
	String architecture = args.architecture ?: generalProperties.x86ArchName
	String archPackDeb = args.archPackDeb ?: ""
	boolean dryRun = args.dryRun ?: false
	String jobSummaryFile = args.jobSummaryFile ?: generalProperties.jobSummaryFile
	jobSummaryFile = "$WORKSPACE/$jobSummaryFile"
	String scyllaDistTarPath = "${gitProperties.scyllaCheckoutDir}/build/$buildMode/dist/tar/"
	String scyllaDistTarFullPath = "$WORKSPACE/$scyllaDistTarPath"
	String rpmsPath = "${gitProperties.scyllaCheckoutDir}/build/dist/$buildMode/redhat/RPMS/$architecture/*.rpm"
	String debsPath = "${gitProperties.scyllaCheckoutDir}/build/dist/$buildMode/debian/*.deb"
	String pythonPath = "${gitProperties.scyllaCheckoutDir}/tools/python3/build/"

	if (buildMode.contains("dev")) {
		echo "No need to publish artifacts when running in dev mode"
		return
	}
	boolean releaseBuild = false
	if (buildMode.contains("release")){
		releaseBuild = true
	}

	boolean uploadGeneralModeArtifacts = args.uploadGeneralModeArtifacts ?: false
	general.errorMissingMandatoryParam ("build.publishScyllaDistArtifacts",
		[scyllaRelocFullUrl: "$args.scyllaRelocFullUrl",
		scyllaBuildPackagesFullUrl: "$args.scyllaBuildPackagesFullUrl"]
	)

	boolean flattenDirs = true
	boolean status = false

	if (archPackDeb) {
		// Todo: Set it in ninja, so they will be created like this. https://github.com/scylladb/scylla/issues/8675
		archPackDeb = "-${archPackDeb}"
		general.renameIfOldNameExists ("$scyllaDistTarFullPath${branchProperties.productName}-python3-package.tar.gz", "$scyllaDistTarFullPath${branchProperties.productName}-python3${archPackDeb}-package.tar.gz", dryRun)
	}

	String unifiedTarName = general.runOrDryRunShOutput (dryRun, "basename \$(ls $scyllaDistTarFullPath${branchProperties.productName}-unified-package*.tar.gz)", "Get basename for unified-package")
	String modeUnifiedTarName = unifiedTarName.replace("${branchProperties.productName}", "${branchProperties.productName}${archPackDeb}")
	if (releaseBuild){
		// we only upload the perf test files to Jenkins, as microbenchmark jobs uses it and runs on a local machine.
		artifact.publishArtifactsStatus("${gitProperties.scyllaCheckoutDir}/${branchProperties.releaseTestDir}/perf/**", WORKSPACE, "", dryRun)
		if (archPackDeb) {
			general.renameIfOldNameExists ("$scyllaDistTarFullPath${branchProperties.productName}-package.tar.gz", "$scyllaDistTarFullPath${branchProperties.productName}${archPackDeb}-package.tar.gz", dryRun)
		}
	} else {
		general.renameIfOldNameExists ("$scyllaDistTarFullPath${branchProperties.productName}-package.tar.gz", "$scyllaDistTarFullPath${branchProperties.productName}-${buildMode}${archPackDeb}-package.tar.gz", dryRun)
		unifiedTarName = general.runOrDryRunShOutput (dryRun, "basename \$(ls $scyllaDistTarFullPath${branchProperties.productName}-unified-package*.tar.gz)", "Get basename for unified-package")
		modeUnifiedTarName = unifiedTarName.replace("${branchProperties.productName}", "${branchProperties.productName}-${buildMode}${archPackDeb}")
	}
	if (archPackDeb || ! releaseBuild) {
		general.renameIfOldNameExists ("${scyllaDistTarFullPath}$unifiedTarName", "${scyllaDistTarFullPath}$modeUnifiedTarName", dryRun)
	}

	def cloudPublicArtifacts = [
		"$scyllaDistTarPath${branchProperties.productName}-jmx-package.tar.gz",
		"$scyllaDistTarPath${branchProperties.productName}-tools-package.tar.gz",
		"$scyllaDistTarPath${branchProperties.productName}-python3${archPackDeb}-package.tar.gz",
	]

	def rpmArtifacts = [
		rpmsPath,
		"${gitProperties.scyllaCheckoutDir}/tools/jmx/build/redhat/RPMS/noarch/*.rpm",
		"${gitProperties.scyllaCheckoutDir}/tools/java/build/redhat/RPMS/noarch/*.rpm",
		"${pythonPath}redhat/RPMS/$architecture/*.rpm",
	]

	def debianArtifacts = [
		debsPath,
		"${gitProperties.scyllaCheckoutDir}/tools/jmx/build/debian/*.deb",
		"${gitProperties.scyllaCheckoutDir}/tools/java/build/debian/*.deb",
		"${pythonPath}debian/*.deb"
	]

	def localArtifacts = []
	if (uploadGeneralModeArtifacts) {
		// Need those artifact locally for microbenchmark job, which use local server only
		localArtifacts += [
			"${gitProperties.scyllaCheckoutDir}/${generalProperties.versionFileName}",
			"${gitProperties.scyllaCheckoutDir}/reloc/**",
			"${gitProperties.scyllaCheckoutDir}/tools/toolchain/**",
	 	]
	}
	if ( dryRun) {
		echo "Skipping publish section on publishScyllaDistArtifacts as running on dry run"
		return
	}
	localArtifacts.each { localArtifact ->
		status |= artifact.publishArtifactsStatus(localArtifact, WORKSPACE)
	}

	def artifactsTargets = [:]
	// If we build both or given architecture, we need "merge" the metadata file, so do the merge and publish on pipeline
	if (! archPackDeb) {
		artifact.publishMetadataFile (generalProperties.buildMetadataFile, args.scyllaRelocFullUrl, args.scyllaBuildPackagesFullUrl)
	}
	modeSubfolder = "_${buildMode}"
	if (releaseBuild){
		modeSubfolder = ""
		cloudPublicArtifacts += [
			"$scyllaDistTarPath${branchProperties.productName}${archPackDeb}-package.tar.gz",
			"$scyllaDistTarPath${branchProperties.productName}${archPackDeb}-unified-package*.tar.gz",
		]
	} else {
		cloudPublicArtifacts += [
			"$scyllaDistTarPath${branchProperties.productName}-${buildMode}${archPackDeb}-package.tar.gz",
			"$scyllaDistTarPath${branchProperties.productName}-${buildMode}${archPackDeb}-unified-package*.tar.gz",
		]
	}

	artifactsTargets.cloudPublicArtifacts =
		[artifactsList: cloudPublicArtifacts,
		target: args.scyllaRelocFullUrl]
	artifactsTargets.rpmArtifacts =
		[artifactsList: rpmArtifacts,
		target: "${args.scyllaBuildPackagesFullUrl}/rpm${modeSubfolder}"]
	artifactsTargets.debianArtifacts =
		[artifactsList: debianArtifacts,
		target: "${args.scyllaBuildPackagesFullUrl}/deb${modeSubfolder}"]

	def parallelCloudPublish = [:]
		boolean parallelPublishStatus = false
		artifactsTargets.each { key, val ->
			val.artifactsList.each { eachArtifact ->
				echo "artifact: |$eachArtifact|, target: |$val.target|"
				parallelCloudPublish[eachArtifact] = { ->
					parallelPublishStatus = artifact.publishS3Artifact(eachArtifact, WORKSPACE, val.target, flattenDirs)
					if (parallelPublishStatus) {
						status = true
					}
				}
			}
		}
	parallel parallelCloudPublish

	if (status) {
		sh "echo 'publish_${architecture}=Failed' >> $jobSummaryFile"
		stash(name: jobSummaryFile, includes: jobSummaryFile)
		error("Could not publish some artifacts. Check log for details")
	}
}

def buildTestDtestPublish (Map args) {
	// Build, Test, Optional dtest and publish artifacts
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): preserveWorkspace - For git.cleanWorkSpaceUponRequest
	// String (mandatory): scyllaSha - SHA for Scylla, so we are sure that all calls will use the same SHA
	// String (mandatory): pkgSha - SHA for scylla-pkg, so we are sure that all calls will use the same SHA
	// boolean (default false): testsDebugInfoFlag
	// String (default null for all): buildMode Which mode(s) to build: dev|release|debug
	// String (none - for backwards compatibility): architecture Which architecture to publish x86_64|aarch64
	// String (mandatory): urlId - ID for cloud storage
	// String (mandatory): utcTextTimeStamp - for cloud storage
	// String (mandatory): targetRelocUrl - for cloud storage
	// String (mandatory): targetBuildPackagesUrl - for cloud storage
	// String (default: 1) numOfUnittestsRepeats - How many times to run unit tests. 0 to skip tests
	// String (no default, just use as is) - includeTests
	// boolean (default true): publishArtifacts
	// boolean (default: false): buildDocker
	// String (mandatory): jobTitle - subjext for flaky mail
	// String (mandatory): devAddress - To who send flaky mail
	// Returns: list of: [boolean: testsFailed, String: allTestResultDetails]

	general.traceFunctionParams ("build.buildTestDtestPublish", args)
	general.errorMissingMandatoryParam ("build.buildTestDtestPublish", [
		scyllaSha: "$args.scyllaSha",
		pkgSha: "$args.pkgSha",
		urlId: "$args.urlId",
		utcTextTimeStamp: "$args.utcTextTimeStamp",
		targetRelocUrl: "$args.targetRelocUrl",
		targetBuildPackagesUrl: "$args.targetBuildPackagesUrl",
		jobTitle: "$args.jobTitle",
		devAddress: "$args.devAddress",
	])
	boolean dryRun = args.dryRun ?: false
	boolean buildDocker = args.buildDocker ?: false
	boolean preserveWorkspace = args.preserveWorkspace ?: false
	boolean testsDebugInfoFlag = args.testsDebugInfoFlag ?: false
	String buildMode = args.buildMode ?: ""
	String architecture = args.architecture ?: ""
	String numOfUnittestsRepeats = args.numOfUnittestsRepeats ?: "1"
	boolean publishArtifacts = args.publishArtifacts != null ? args.publishArtifacts : true
	int numberOfTestedModes = 0
	boolean testsFailed = false
	String failedTestModes = ""
	int allModesNumberOfTestFails = 0
	int numberOfFailingModes = 0
	String allTestResultDetails = ""
	def buildTestModesList = general.buildModesList(buildMode) // test all: release, debug, dev
	String metadataFileName = generalProperties.buildMetadataFile
	String jobSummaryFile = generalProperties.jobSummaryFile
	if (architecture == generalProperties.x86ArchName) {
		metadataFileName = generalProperties.x86MetadataFile
		jobSummaryFile = generalProperties.x86JobSummaryFile
	} else if (architecture == generalProperties.armArchName) {
		metadataFileName = generalProperties.armMetadataFile
		jobSummaryFile = generalProperties.armJobSummaryFile
	} else if (architecture == "") {
		metadataFileName = generalProperties.buildMetadataFile
		jobSummaryFile = generalProperties.jobSummaryFile
	}

	git.cleanWorkSpaceUponRequest(preserveWorkspace)
	git.checkoutToDir(gitProperties.scyllaRepoUrl, args.scyllaSha, gitProperties.scyllaCheckoutDir)
	git.checkoutToDir(relengRepo, args.pkgSha, gitProperties.scyllaPkgCheckoutDir, disableSubmodules)

	dockerImageId = build.scyllaDistBuild(
		testsDebugInfoFlag: testsDebugInfoFlag,
		dryRun: dryRun,
		buildMode: buildMode,
		architecture: architecture,
		jobSummaryFile: jobSummaryFile,
		buildDocker: buildDocker,
	)

	buildTestModesList = general.buildModesList(buildMode) // test all: release, debug, dev
	buildTestModesString = buildTestModesList.join(", ")
	(scyllaId, scyllaVersion) = build.createDistBuildMetadataFile (
		dryRun: dryRun,
		scyllaSha: args.scyllaSha,
		urlId: args.urlId,
		buildMode: buildTestModesString,
		utcTextTimeStamp: args.utcTextTimeStamp,
		baseRelocCloudStorageUrl: args.targetRelocUrl,
		baseBuildPackagesCloudStorageUrl: args.targetBuildPackagesUrl,
		buildMetadataFile: "$WORKSPACE/$metadataFileName",
		architecture: architecture,
	)
	if (buildDocker) {
		String containerImageName = "${branchProperties.containerRepository}:${scyllaVersion}-${scyllaId}-${architecture}"
		build.publishScyllaDocker (
			sourceDockerImage: dockerImageId,
			containerImageName: containerImageName,
			architecture: architecture,
			metadataFileName: metadataFileName,
			dryRun: dryRun,
		)
	}
	artifact.publishMetadataFile(metadataFileName, args.targetRelocUrl, args.targetBuildPackagesUrl)
	stash(name: metadataFileName, includes: metadataFileName)

	if (publishArtifacts) {
		boolean uploadGeneralModeArtifacts = true
		buildTestModesList.each { mode ->
			publishScyllaDistArtifacts (
				dryRun: dryRun,
				scyllaRelocFullUrl: args.targetRelocUrl,
				scyllaBuildPackagesFullUrl: args.targetBuildPackagesUrl,
				buildMode: mode,
				uploadGeneralModeArtifacts: uploadGeneralModeArtifacts,
				uploadToCloudStorage: true,
				architecture: architecture,
				archPackDeb: architecture,
				jobSummaryFile: jobSummaryFile,
			)
			uploadGeneralModeArtifacts = false
		}
	}

	if (numOfUnittestsRepeats != "0") {
		(numberOfTestedModes, testsFailed, failedTestModes, allModesNumberOfTestFails, numberOfFailingModes, allTestResultDetails) = test.testModes(
			dryRun: dryRun,
			numOfRepeat: numOfUnittestsRepeats,
			modes2Test: buildTestModesList,
			includeTests: args.includeTests,
			architecture: architecture,
			jobSummaryFile: jobSummaryFile,
		)

		test.isflakyUnittest (
			dryRun: dryRun,
			testFailed: testsFailed,
			numberOfTestedModes: numberOfTestedModes,
			numberOfTestFails: allModesNumberOfTestFails,
			numberOfFailingModes: numberOfFailingModes,
			allTestResultDetails: allTestResultDetails,
			jobTitle: args.jobTitle,
			devAddress: args.devAddress,
			architecture: architecture,
			jobSummaryFile: jobSummaryFile,
		)
	}
	stash(name: jobSummaryFile, includes: jobSummaryFile)
	git.cleanWorkSpaceUponRequest(preserveWorkspace)
	echo "buildTestDtestPublish going to return:"
	echo "testsFailed: $testsFailed"
	echo "allTestResultDetails: $allTestResultDetails"
	return [testsFailed, allTestResultDetails]
}

def buildAmi (Map args) {
	// Build AMI
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): amiWorkDir - Full path of machine image repo dir
	// String (Mandatory) amiPropertiesFile - Full path to write the created AMI ID
	// String (Mandatory): dpackagerAwsCommand - Full path of dpackager command
	// String (Mandatory): repoUrl - URL of CentOS repo file or ubuntu:20.04
	// String (default: ubuntu:20.04) osDistro: [centos:7|ubuntu:20.04]

	general.traceFunctionParams ("build.buildAmi", args)
	general.errorMissingMandatoryParam ("build.buildAmi",
		[amiWorkDir: "$args.amiWorkDir",
		 amiPropertiesFile: "$args.amiPropertiesFile",
		 dpackagerAwsCommand: "$args.dpackagerAwsCommand",
		 repoUrl: "$args.repoUrl",
		])

	String amiVariablesJsonFile = "${args.amiWorkDir}/variables.json"
	String osDistro = args.osDistro ?: "ubuntu:20.04"
	String dpackagerAwsCommand = args.dpackagerAwsCommand
	boolean dryRun = args.dryRun ?: false

	relengDebugFlag = jenkins.debugBuild() ? "--debug" : ""

	String logFile = "ami.log"
	dryRunFlag = ""
	general.runOrDryRunSh (false, "cp -av ${WORKSPACE}/scylla-pkg/scripts/jenkins-pipelines/json_files/ami_variables.json $amiVariablesJsonFile", "Copy AMI json file")

	if (dryRun) {
		dryRunFlag = " --dry-run"
	}

	String scriptName = "build_deb_ami.sh"
	env.DOCKER_IMAGE = "image_ubuntu20.04"
	if (args.osDistro == "centos:7") {
		scriptName = "build_ami.sh"
		env.DOCKER_IMAGE = "image_fedora-33"
	}

	dir("$args.amiWorkDir") {
		general.runOrDryRunSh (false, "bash -c set -o pipefail ; $dpackagerAwsCommand -- ./$scriptName --product $branchProperties.productName --repo $args.repoUrl $dryRunFlag --build-id $env.BUILD_ID --log-file $logFile $relengDebugFlag", "Build AMI Ubuntu:20.04 based using dpackager")
		artifact.publishArtifactsStatus(logFile, args.amiWorkDir)
		String scyllaAmiId = general.runOrDryRunShOutput (dryRun, "cat $logFile | grep ^us-east-1 | awk '{print \$2}'", "Get AMI ID from log")
		echo "scyllaAmiId: |$scyllaAmiId|"
		if (scyllaAmiId) {
			sh "echo \"scylla_ami_id=${scyllaAmiId}\" > $args.amiPropertiesFile"
		} else {
			error("AMI build failed")
		}
	}

	if (artifact.publishArtifactsStatus(generalProperties.amiIdFile, WORKSPACE)) {
		error("Could not publish AMI ID file. See log for details")
	}
}

String buildGce (Map args) {
	// Build GCE image
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): baseUrl - repo file location for installing scylla deb URL
	// String (default scylla-machine-image script location) gceWorkDir

	general.traceFunctionParams ("build.buildGce", args)

	String gceWorkDir = args.gceWorkDir ?: "$WORKSPACE/$gitProperties.scyllaMachineImageCheckoutDir/gce/image"
	String gceVariablesJsonFile = "${gceWorkDir}/variables.json"
	boolean dryRun = args.dryRun ?: false
	general.errorMissingMandatoryParam ("build.buildGce", [baseUrl: "$args.baseUrl"])

	String gceImageName = ""
	String gceImageId = ""
	String logFile = "gce-image.log"
	String dryRunFlag = ""
	if (dryRun) {
		dryRunFlag = "--dry-run"
	}

	relengDebugFlag = jenkins.debugBuild() ? "--debug" : ""

	String scriptName = "build_deb_image.sh"

	withCredentials([file(credentialsId: generalProperties.jenkinsGoogleApplicationKey, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
		dpackagerGceCommand = general.setGceDpackagerCommand ("",  "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}")
		general.runOrDryRunSh (false, "cp -av ${WORKSPACE}/scylla-pkg/scripts/jenkins-pipelines/json_files/gce_variables.json $gceVariablesJsonFile", "Copy GCE json file")
		dir("$gceWorkDir") {
			general.runOrDryRunSh (false, "set -o pipefail ; $dpackagerGceCommand -- ./$scriptName $dryRunFlag --product $branchProperties.productName --repo $args.baseUrl  --build-id $env.BUILD_ID --log-file $logFile $relengDebugFlag", "Build GCE image using dpackager")
			artifact.publishArtifactsStatus(logFile, gceWorkDir)
			gceImageName = general.runOrDryRunShOutput (dryRun, "cat $logFile | grep \"googlecompute: A disk image was created\" | awk -F ': ' '{print \$4}'", "Get gceImageName from log")
			echo "gceImageName: |$gceImageName|"
			setCloudAutoCmd = dpackagerGceCommand + ' -- gcloud auth activate-service-account --key-file $GOOGLE_APPLICATION_CREDENTIALS'
			general.runOrDryRunSh (dryRun, setCloudAutoCmd, "Setting cloud auth")
			general.runOrDryRunSh (dryRun, "$dpackagerGceCommand -- gcloud config set project $branchProperties.gceImagesProjectName", "Config project")
			gceImageId = general.runOrDryRunShOutput (dryRun, "$dpackagerGceCommand -- gcloud compute images describe $gceImageName | grep ^id | awk '{print \$2}'", "Get gceImageId")
			echo "gceImageId: |$gceImageId|"
			gceImageId = gceImageId.replaceAll("'","")
			general.runOrDryRunSh (dryRun, "$dpackagerGceCommand -- gcloud compute images add-iam-policy-binding $gceImageId --member='$generalProperties.gceTestsServiceAccount' --role='roles/compute.imageUser' --project $branchProperties.gceImagesProjectName", "compute GCE image")
		}
	}
	echo "Created gceImageId: |$gceImageId|"
	return gceImageId
}

String buildAzure (Map args) {
	// Build Azure image
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): listFileUrl - repo file location for installing scylla debs URL based on ubuntu:20.04

	general.traceFunctionParams ("build.buildAzure", args)

	String azureWorkDir = "$WORKSPACE/$gitProperties.scyllaMachineImageCheckoutDir/azure/image"
	boolean dryRun = args.dryRun ?: false
	general.errorMissingMandatoryParam ("build.buildAzure", [listFileUrl: "$args.listFileUrl"])

	String azureImageId = ""
	String logFile = "azure-image.log"
	String dryRunFlag = ""
	if (dryRun) {
		dryRunFlag = "--dry-run"
	}

	relengDebugFlag = jenkins.debugBuild() ? "--debug" : ""

	String scriptName = "build_azure_image.sh"

	env.DOCKER_IMAGE = "image_ubuntu20.04"

	withCredentials([azureServicePrincipal(credentialsId: 'azure_credentials')]) {
		dpackagerAzureCommand = general.setAzureDpackagerCommand ()
		dir("$azureWorkDir") {
			general.runOrDryRunSh (false, "set -o pipefail ; $dpackagerAzureCommand -- ./$scriptName $dryRunFlag --product $branchProperties.productName --repo $args.listFileUrl --build-id $env.BUILD_ID --log-file $logFile $relengDebugFlag", "Build Azure image Ubuntu:20.04 based using dpackager")
			artifact.publishArtifactsStatus(logFile, "$azureWorkDir/build")
			azureImageId = general.runOrDryRunShOutput (dryRun, "cat build/$logFile | grep ^ManagedImageId |awk '{print \$2}'", "Get Azure imange ID")
			azureImageName = general.runOrDryRunShOutput (dryRun, "cat build/$logFile | grep ^ManagedImageName |awk '{print \$2}'", "Get Azure imange name")
		}
		return [azureImageId, azureImageName]
	}
}

def publishDocker (Map args) {
	// Optional create and Publish docker
	// Parameters:
	// boolean (default false): testingEnv - Run Under testing credentials (didn't debug)
	// String (mandatory): defaultDir - Full path - where to create the docker
	// String (mandatory): scriptsDir - Full path of publish-docker.sh script
	// String (mandatory): scriptParams - Parameters for the publish-docker.sh script

	general.traceFunctionParams ("build.publishDocker", args)
	general.errorMissingMandatoryParam ("build.publishDocker",
		[defaultDir: "$args.defaultDir",
		 scriptsDir: "$args.scriptsDir",
		 scriptParams: "$args.scriptParams"])
	boolean testingEnv = args.testingEnv ?: false

	String docker_username
	String docker_password
	(docker_username,docker_password)=general.dockerCredentials(testingEnv)
	dir (args.defaultDir) {
		withCredentials([string(credentialsId: docker_username, variable: 'DOCKER_USERNAME'),
		string(credentialsId: docker_password, variable: 'DOCKER_PASSWORD')]) {
			sh "${args.scriptsDir}/publish-docker.sh  ${args.scriptParams}"
		}
	}
}

def prepareBuildRpm (Map args) {
	// Get the needed artifacts, build RPM, publish artifacts
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): buildArm - Whether to create ARM repo.
	// boolean (default: true): mainMode - True if this is the first mode we build.
	// boolean (mandatory): downloadFromCloud - True if needed to get artifacts from cloud.
	// String (default: latest): cloudUrl - URL to take artifacts from
	//                                      None if no need to download artifacts (for byo, use local compiled).
	// String (default: next job): artifactSourceJob - Parameters for the publish-docker.sh script
	//                                      None if no need to download artifacts (for byo, use local compiled).
	// String (default: take from last success): artifactSourceJobNum - Number of base build
	//                                      None if no need to download artifacts (for byo, use local compiled).
	// String (default release): buildMode - debug or release
	// String (mandatory): scyllaUrlID - ID of the Build (such as 2021-02-09T15:21:47Z)
	// String (mandatory): rpmUrl - URL to put artifacts on.

	general.traceFunctionParams ("build.prepareBuildRpm", args)
	general.errorMissingMandatoryParam ("build.prepareBuildRpm", [
	  downloadFromCloud: "$args.downloadFromCloud",
	 	scyllaUrlID: "$args.scyllaUrlID",
		rpmUrl: "$args.rpmUrl",
	])
	boolean dryRun = args.dryRun ?: false
	boolean buildArm = args.buildArm ?: false
	boolean mainMode = args.mainMode != null ? args.mainMode : true
	String cloudUrl = args.cloudUrl ?: branchProperties.latestRpmBuildUrl
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String artifactSourceJob = args.artifactSourceJob ?: "${branchProperties.calledBuildsDir}${branchProperties.relocUploaderJobName}"
	String buildMode = args.buildMode ?: "release"

	if (cloudUrl.toLowerCase() == "none" || artifactSourceJobNum.toLowerCase() == "none" || artifactSourceJob.toLowerCase() == "none") {
		echo "Using local compiled artifacts"
	} else {
		echo "Get Artifacts RPMs (Created by ninja)"
		echo "====================================="
		artifact.getCentosRpmPackages (
			downloadFromCloud: true,
			sourceUrl:  cloudUrl,
			artifactSourceJob: artifactSourceJob,
			artifactSourceJobNum: artifactSourceJobNum,
			artifactsMode: buildMode,
		)

		if (mainMode) {
			build.machineImageBuild(dryRun)
		}
	}

	echo "Build $buildMode phase"
	echo "======================"
	build.createRpmRepo (
		dryRun: dryRun,
		rpmCloudID: args.scyllaUrlID,
		rpmUrl: args.rpmUrl,
		buildMode: buildMode,
		buildArm: buildArm,
	)

	artifact.publishCentOSRpmArtifacts(
		dryRun: dryRun,
		rpmCloudStoragePath: args.rpmUrl,
		buildMode: buildMode,
		mainMode: mainMode,
	)
}

def prepareBuildDebPkg (Map args) {
	// Get the needed artifacts, build Deb packages, publish artifacts
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default: true): mainMode - True if this is the first mode we build.
	// boolean (default: true): downloadFromCloud - get artifacts from cloud.
	// boolean (default false): buildArm - Whether to create ARM repo.
	// String (default: latest): cloudUrl - URL to take artifacts from
	//                                      None if no need to download artifacts (for byo, use local compiled).
	// String (default: next job): artifactSourceJob - Parameters for the publish-docker.sh script
	//                                      None if no need to download artifacts (for byo, use local compiled).
	// String (default: take from last success): artifactSourceJobNum - Number of base build
	//                                      None if no need to download artifacts (for byo, use local compiled).
	// String (default release): buildMode - debug or release
	// String (mandatory): scyllaUrlID - ID of the Build (such as 2021-02-09T15:21:47Z)
	// String (mandatory): scyllaUnifiedDebUrl - URL to put artifacts on. "None" if not needed to upload (byo)

	general.traceFunctionParams ("build.prepareBuildDebPkg", args)
	general.errorMissingMandatoryParam ("build.prepareBuildDebPkg", [
	  downloadFromCloud: "$args.downloadFromCloud",
	 	scyllaUrlID: "$args.scyllaUrlID",
		scyllaUnifiedDebUrl: "$args.scyllaUnifiedDebUrl",
		cloudUrl: "$args.cloudUrl",
	])
	boolean dryRun = args.dryRun ?: false
	boolean mainMode = args.mainMode != null ? args.mainMode : true
	String cloudUrl = args.cloudUrl ?: ""
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String artifactSourceJob = args.artifactSourceJob ?: "${branchProperties.calledBuildsDir}${branchProperties.relocUploaderJobName}"
	String buildMode = args.buildMode ?: "release"
	boolean buildArm = args.buildArm ?: false
	boolean buildRpm = false

	if (cloudUrl.toLowerCase() == "none" || artifactSourceJobNum.toLowerCase() == "none" || artifactSourceJob.toLowerCase() == "none") {
		echo "Using local compiled artifacts"
	} else {
		echo "Get Artifacts Debs (Created by ninja)"
		echo "====================================="
		artifact.getUnifiedDebPackages (
			downloadFromCloud: args.downloadFromCloud,
			cloudUrl:  cloudUrl,
			artifactSourceJob: artifactSourceJob,
			artifactSourceJobNum: artifactSourceJobNum,
			artifactsMode: buildMode,
		)
	}

	echo "Build $buildMode phase"
	echo "======================"
	if (mainMode) {
		build.machineImageBuild(dryRun, buildRpm)
	}

	build.createUnifiedDebRepo (
		dryRun: dryRun,
		unifiedDebCloudID: args.scyllaUrlID,
		buildMode: buildMode,
		unifiedDebUrl: args.scyllaUnifiedDebUrl,
		buildArm: buildArm,
	)

	artifact.publishUnifiedDebArtifacts(
		dryRun: dryRun,
		unifiedDebCloudStoragePath: args.scyllaUnifiedDebUrl,
		buildMode: buildMode,
		mainMode: mainMode,
	)
}

def createManagerBuildMetadataFile (Map args) {
	// Create a Manager metadatafile.
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): urlId - ID for the cloud URL. Created by artifact.cloudBuildIdPath
	// String (mandatory): utcTextTimeStamp - Created by artifact.cloudBuildIdPath just before building
	// String (default $WORKSPACE/generalProperties.buildMetadataFile) buildMetadataFile - file to write

	general.traceFunctionParams ("build.createDistBuildMetadataFile", args)
	general.errorMissingMandatoryParam ("build.createDistBuildMetadataFile", [urlId: "$args.urlId", utcTextTimeStamp: "$args.utcTextTimeStamp"])

 	String urlId = args.urlId
	boolean dryRun = args.dryRun ?: false
	String buildMetadataFile = args.buildMetadataFile ?: "$WORKSPACE/${generalProperties.buildMetadataFile}"

	if (dryRun) {
		scyllaId = "dryRun"
		scyllaVersion = "dryRun"
	}

	sh """
		set -e
		echo \"timestamp: $args.utcTextTimeStamp\" > $buildMetadataFile
		echo \"jenkins-job-path: ${env.JOB_NAME}\" >> $buildMetadataFile
		echo \"jenkins-job-number: ${env.BUILD_NUMBER}\" >> $buildMetadataFile
		echo \"url-id: $urlId\" >> $buildMetadataFile
	"""
}

def buildScyllaManager (Map args) {
	// Build Manager Rpm,Deb and Docker
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): managerUrlID - ID of the Build (such as 2021-02-09T15:21:47Z)
	// String (mandatory): managerRpmUrl - URL to put rpm packages artifacts on.
	// String (mandatory): managerDebUrl - URL to put deb packages artifacts on.

	general.traceFunctionParams ("build.buildScyllaManager", args)
	general.errorMissingMandatoryParam (
		"build.buildScyllaManager", [
			managerRpmRepoUrl: "$args.managerRpmRepoUrl",
			managerRpmUrl: "$args.managerRpmUrl",
			managerDebListUrl: "$args.managerDebListUrl",
			managerDebUrl: "$args.managerDebUrl",
		]
	)
	String managerLogfile = "manager_build.log"

	boolean dryRun = args.dryRun ?: false

	dir("$gitProperties.scyllaManagerCheckoutDir/dist") {
		general.runOrDryRunSh (dryRun, "set -o pipefail; make ${branchProperties.buildTarget} 2>&1 | tee $managerLogfile", "Build Manager packages and docker")
		if (! dryRun) {
			artifact.publishArtifactsStatus(managerLogfile, "$WORKSPACE/$gitProperties.scyllaManagerCheckoutDir/dist")
		}

		managerRelease = general.runOrDryRunShOutput (dryRun, "cat ${branchProperties.managerReleaseFile}", "Get Scylla Manager release value from ${branchProperties.managerReleaseFile} to write on metadata file.")
		managerVersion = general.runOrDryRunShOutput (dryRun, "cat ${branchProperties.managerVersionFile}", "Get Scylla Manager version value from ${branchProperties.managerVersionFile} to write on metadata file.")

		build.createRpmRepo (
			dryRun: dryRun,
			rpmCloudID: args.managerUrlID,
			rpmUrl: args.managerRpmUrl,
			buildMode: "release",
		)

		artifact.publishCentOSRpmArtifacts(
			dryRun: dryRun,
			rpmCloudStoragePath: args.managerRpmUrl,
			buildMode: "release",
		)

		build.createUnifiedDebRepo (
			dryRun: dryRun,
			unifiedDebCloudID: args.managerUrlID,
			unifiedDebUrl: args.managerDebUrl,
			buildMode: "release",
		)

		artifact.publishUnifiedDebArtifacts(
			dryRun: dryRun,
			unifiedDebCloudStoragePath: args.managerDebUrl,
			buildMode: "release",
		)
	}
	return [managerVersion, managerRelease]
}

def publishManagerDocker (String managerDockerVersion, boolean dryRun=false) {
	(docker_username,docker_password)=general.dockerCredentials(dryRun)
	withCredentials([string(credentialsId: docker_username, variable: 'DOCKER_USERNAME'),
	string(credentialsId: docker_password, variable: 'DOCKER_PASSWORD')]) {
		managerImage = "${branchProperties.managerDockerImage}:$managerDockerVersion"
		agentImage = "${branchProperties.managerAgentDockerImage}:$managerDockerVersion"
		dockerCredentials = '-u=$DOCKER_USERNAME -p=$DOCKER_PASSWORD'
		general.runOrDryRunSh (dryRun, "docker login $generalProperties.containerRegistry $dockerCredentials", "Docker login")
		general.runOrDryRunSh (dryRun, "docker tag $managerImage $generalProperties.containerRegistry/$managerImage", "Create docker tag for scylla-manager")
		general.runOrDryRunSh (dryRun, "docker push $generalProperties.containerRegistry/$managerImage", "Push scylla-manager docker image")
		general.runOrDryRunSh (dryRun, "docker tag $agentImage $generalProperties.containerRegistry/$agentImage", "Create docker tag for scylla-manager-agent")
		general.runOrDryRunSh (dryRun, "docker push $generalProperties.containerRegistry/$agentImage", "Push scylla-manager-agent docker image")
	}
}

def publishScyllaDocker (Map args) {
	// Publish Scylla Docker
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): sourceDockerImage - Docker image to publish.
	// String (mandatory): containerImageName - Image name to publish (such as scylla-nightly:4.6.dev-0.20210809.4ae6eae00)
	// String (empty - for backwards compatibility): architecture - The architecture to publish x86_64|aarch64. Empty is when we only have x86
	// String (default: generalProperties.buildMetadataFile): metadataFileName - Metadata file name to update

	general.traceFunctionParams ("build.publishScyllaDocker", args)
	general.errorMissingMandatoryParam (
		"build.publishScyllaDocker", [
			sourceDockerImage: "$args.sourceDockerImage",
			containerImageName: "$args.containerImageName",
		]
	)
	String sourceDockerImage = args.sourceDockerImage
	String containerImageName = args.containerImageName
	String metadataFileName = args.metadataFileName ?: generalProperties.buildMetadataFile
	String architecture = args.architecture ?: ""
	boolean dryRun = args.dryRun ?: false

	String imageNameKeyName = "docker-image-name"
	if (architecture) {
		imageNameKeyName = "docker-image-name-$architecture"
	}

	(docker_username,docker_password)=general.dockerCredentials(dryRun)
	withCredentials([string(credentialsId: docker_username, variable: 'DOCKER_USERNAME'),
	string(credentialsId: docker_password, variable: 'DOCKER_PASSWORD')]) {
		containerTool = "${generalProperties.defaultContainerTool}"
		dockerCredentials = '-u=$DOCKER_USERNAME -p=$DOCKER_PASSWORD'
		general.runOrDryRunSh (dryRun, "$containerTool login $generalProperties.containerRegistry $dockerCredentials", "Docker login")
		general.runOrDryRunSh (dryRun, "$containerTool tag $sourceDockerImage $generalProperties.containerRegistry/$generalProperties.containerOrganization/$containerImageName", "Create docker tag for scylla")
		general.runOrDryRunSh (dryRun, "$containerTool push $generalProperties.containerRegistry/$generalProperties.containerOrganization/$containerImageName", "Push scylla docker image")
		artifact.addLineToBuildMetadataFile(imageNameKeyName, containerImageName, metadataFileName)
	}
}

def createDockerManifest (boolean dryRun=false) {
	String armDockerImage = general.runOrDryRunShOutput (dryRun, "grep \"docker-image-name-aarch64\" $generalProperties.armMetadataFile | awk '{print \$2}'", "Get arm docker image")
	String x86DockerImage = general.runOrDryRunShOutput (dryRun, "grep \"docker-image-name-x86_64\" $generalProperties.x86MetadataFile | awk '{print \$2}'", "Get x86 docker image")
	String manifestName = "$branchProperties.productName:$scyllaVersion-$scyllaId"
	String containerImageName = "$branchProperties.containerRepository:$scyllaVersion-$scyllaId"
	(docker_username,docker_password) = general.dockerCredentials(dryRun)
	withCredentials ([
		string(credentialsId: docker_username, variable: 'DOCKER_USERNAME'),
		string(credentialsId: docker_password, variable: 'DOCKER_PASSWORD')
	]) {
		dockerCredentials = '-u=$DOCKER_USERNAME -p=$DOCKER_PASSWORD'
		general.runOrDryRunSh (dryRun, "$containerTool login $generalProperties.containerRegistry $dockerCredentials", "Docker login")
		boolean manifestStatus = sh(script: "buildah manifest inspect $manifestName", returnStatus:true) == 0
		if (!manifestStatus) {
			general.runOrDryRunSh (dryRun, "buildah manifest create $manifestName", "Create manifest")
		}
		general.runOrDryRunSh (dryRun, "buildah --arch=arm64 pull $generalProperties.containerOrganization/$armDockerImage", "Pull arm based docker image")
		general.runOrDryRunSh (dryRun, "buildah --arch=amd64 pull $generalProperties.containerOrganization/$x86DockerImage", "Pull x86 based docker image")
		general.runOrDryRunSh (dryRun, "buildah manifest add $manifestName $armDockerImage", "Add arm docker image to manifest")
		general.runOrDryRunSh (dryRun, "buildah manifest add $manifestName $x86DockerImage", "Add x86 docker image to manifest")
		general.runOrDryRunSh (dryRun, "${generalProperties.defaultContainerTool} manifest push --all $manifestName $generalProperties.containerRegistry/$generalProperties.containerOrganization/$containerImageName", "Push manifest")
		general.runOrDryRunSh (dryRun, "buildah manifest rm $manifestName", "Remove manifest")
	}

	artifact.addLineToBuildMetadataFile("docker-image-name", containerImageName, generalProperties.buildMetadataFile)

}

return this
