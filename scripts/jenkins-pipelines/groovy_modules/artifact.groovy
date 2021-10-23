
def getArtifact (Map args) {
	// get artifacts from jenkins or cloud

	// Parameters:
	// boolean (default false): downloadFromCloud - Whether to publish to cloud storage (S3) or not.
	// boolean (default false): ignoreMissingArtifact - whether to ignore error if file is missing
	// boolean (default false): recursiveCloudCopy - whether to copy recursive tree
	// String (default: WORKSPACE): targetPath where to put the downloaded the artifacts.
	// String (mandatory if downloadFromCloud is false): artifactSourceJob From where to download the artifacts.
	// String (mandatory if downloadFromCloud is false): artifactSourceJobNum (number) to download from
	// String (mandatory if downloadFromCloud): sourceUrl - From where to download the artifacts.
	// String (mandatory): artifact - The Needed artifact.

	general.traceFunctionParams ("artifact.getArtifact", args)

	boolean ignoreMissingArtifact = args.ignoreMissingArtifact ?: false
	boolean downloadFromCloud = args.downloadFromCloud ?: false
	boolean recursiveCloudCopy = args.recursiveCloudCopy ?: false
	String targetPath = args.targetPath ?: WORKSPACE

	def mandatoryArgs = general.setMandatoryList (downloadFromCloud, [artifact: "$args.artifact", sourceUrl: "$args.sourceUrl"], [artifact: "$args.artifact", artifactSourceJob: "$args.artifactSourceJob"])
	general.errorMissingMandatoryParam ("artifact.getArtifact", mandatoryArgs)

	if (downloadFromCloud) {
		echo "Download artifacts from cloud"
		String url = general.addTrailingSlashIfMissing(args.sourceUrl)
		url = general.removeHttpFromUrl(url)
		url = general.addS3PrefixIfMissing(url)

		String fullUrl = "$url${args.artifact}"
		echo "Download $fullUrl to $targetPath/$args.artifact"
		withCredentials([string(credentialsId: 'jenkins2-aws-secret-key-id', variable: 'AWS_ACCESS_KEY_ID'),
		string(credentialsId: 'jenkins2-aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')]) {
			def lsStatus = sh (
				script: "aws s3 ls $fullUrl --recursive",
				returnStdout: true
			).trim()
		}
		if (ignoreMissingArtifact && lsStatus) {
			echo "Could not find artifact on S3. Ignoring as user request"
		} else {
			boolean recursiveFlag = false
			if (recursiveCloudCopy) {
				recursiveFlag = true
			}

			general.runS3Cp (
					recursive: recursiveFlag,
					cpSource: fullUrl,
					cpTarget: "$targetPath/$args.artifact",
					description: "Copy artifact $fullUrl from cloud storage to local dir $args.artifact"
				)
				general.lsPath (targetPath, "content after getting artifacts from cloud")
		}
	} else {
		echo "Download artifacts from Jenkins"
		general.errorMissingMandatoryParam ("artifact.getArtifact download from jenkins:", [artifactSourceJobNum: "$args.artifactSourceJobNum"])
		step([  $class: 'CopyArtifact',
			filter: "${args.artifact}",
			fingerprintArtifacts: true,
			projectName: "${args.artifactSourceJob}",
			selector: [$class: 'SpecificBuildSelector', buildNumber: "$args.artifactSourceJobNum"],
			target: "${targetPath}",
			optional: "${ignoreMissingArtifact}"
		])
	}
}

def getAmiArtifacts (Map args) {
	// get RPMs for AMI from jenkins or cloud
	//
	// Parameters:
	// boolean (default false): downloadFromCloud - Whether to publish to cloud storage (S3) or not.
	// String (mandatory if downloadFromCloud is false): artifactSourceJob From where to download the artifacts.
	// String (default: last success run): artifactSourceJobNum (number) to download from
	// String (mandatory if downloadFromCloud): sourceUrl - From where to download the artifacts.
	// String: (default: ubuntu:20.04) amiOS - set the AMI based OS option: ubuntu:20.04|centos:7 - that is kept for special needs - manual runs

	general.traceFunctionParams ("artifact.getAmiArtifacts", args)

	boolean downloadFromCloud = args.downloadFromCloud ?: false
	String amiOS = args.amiOS ?: "ubuntu:20.04"
	String sourceUrl = args.sourceUrl ?: ""

	def mandatoryArgs = general.setMandatoryList (downloadFromCloud, [sourceUrl: "$args.sourceUrl"], [artifactSourceJob: "$args.artifactSourceJob"])
	general.errorMissingMandatoryParam ("artifact.getAmiArtifacts", mandatoryArgs)

	def artifacts = []
	if (amiOS == "centos:7") {
		if (downloadFromCloud) {
			artifacts = ["${branchProperties.generalProductName}/${generalProperties.x86ArchName}",
									 "${branchProperties.generalProductName}/noarch",]
		} else {
			artifacts = ["${branchProperties.generalProductName}/${generalProperties.x86ArchName}/*.rpm",
									 "${branchProperties.generalProductName}/noarch/*.rpm",]
		}
	} else {
		artifacts = [
			"${branchProperties.scyllaUnifiedPkgRepo}/pool/main/s/"
		]
	}
	artifacts.each { artifact ->
		getArtifact(artifact: artifact,
			targetPath: WORKSPACE,
			artifactSourceJob: args.artifactSourceJob,
			artifactSourceJobNum: args.artifactSourceJobNum,
			sourceUrl: sourceUrl,
			downloadFromCloud: downloadFromCloud,
			recursiveCloudCopy: true,
			ignoreMissingArtifact: false)

		getArtifact(artifact: generalProperties.buildMetadataFile,
			targetPath: WORKSPACE,
			artifactSourceJob: args.artifactSourceJob,
			artifactSourceJobNum: args.artifactSourceJobNum,
			sourceUrl: sourceUrl,
			downloadFromCloud: downloadFromCloud,
			recursiveCloudCopy: false,
			ignoreMissingArtifact: false)
	}
	general.lsPath (WORKSPACE, "content after getting artifacts for ami")
}

def getUnifiedDebPackages (Map args) {
	// get unified-deb artifact from jenkins or cloud
	//
	// Parameters:
	// boolean (default true): downloadFromCloud - publish to cloud storage (S3).
	// String (mandatory): artifactSourceJob - From where to download the artifacts.
	// String (default: last success run): artifactSourceJobNum (number) to download from
	// String (default: release) artifactsMode: Which artifacts to get: release or debug
	// String (mandatory): cloudUrl - From where to download the artifacts.

	general.traceFunctionParams ("artifact.getUnifiedDebPackages", args)

	boolean downloadFromCloud = args.downloadFromCloud ?: false
	String artifactsMode = args.artifactsMode ?: "release"
	String cloudUrl = args.cloudUrl ?: ""

	general.errorMissingMandatoryParam ("artifact.getUnifiedDebPackages", [cloudUrl: "$args.cloudUrl", artifactSourceJob: "$args.artifactSourceJob"])
	sh ("rm -rf $generalProperties.buildDistDir")

	def debianArtifacts = []
	if (downloadFromCloud) {
		if (artifactsMode == "release") {
			debianArtifacts = [ "deb" ]
		} else {
			debianArtifacts = [ "deb_debug" ]
		}
	} else {
		debianArtifacts = [
			"${generalProperties.buildDistDir}/$artifactsMode/debian/*.deb",
			"scylla/tools/jmx/build/debian/${branchProperties.productName}-jmx*.deb",
			"scylla/tools/java/build/debian/${branchProperties.productName}-tools*.deb",
			"scylla/tools/python3/build/debian/${branchProperties.productName}-python3*.deb"
		]
	}

	debianArtifacts.each { artifact ->
		getArtifact(artifact: artifact,
			targetPath: WORKSPACE,
			artifactSourceJob: args.artifactSourceJob,
			artifactSourceJobNum: args.artifactSourceJobNum,
			downloadFromCloud: downloadFromCloud,
			sourceUrl: cloudUrl,
			recursiveCloudCopy: true,
			ignoreMissingArtifact: false
		)
	}
}

def getCentosRpmPackages (Map args) {
	// get RPMs from jenkins or cloud
	//
	// Parameters:
	// boolean (default true): downloadFromCloud - Whether to publish to cloud storage (S3) or not.
	// String (default: release) artifactsMode: Which artifacts to geT: release or debug
	// String (mandatory): artifactSourceJob - From where to download the artifacts.
	// String (default: last success run): artifactSourceJobNum (number) to download from
	// String (mandatory): sourceUrl - From where to download the artifacts.

	general.traceFunctionParams ("artifact.getCentosRpmPackages", args)

	boolean downloadFromCloud = args.downloadFromCloud ?: false
	String artifactsMode = args.artifactsMode ?: "release"

	general.errorMissingMandatoryParam ("artifact.getCentosRpmPackages", [sourceUrl: "$args.sourceUrl", artifactSourceJob: "$args.artifactSourceJob"])

	String sourceUrl = args.sourceUrl ?: ""

	sh ("rm -rf $generalProperties.buildDistDir")

	def rpmArtifacts = []
	if (downloadFromCloud) {
		if (artifactsMode == "release") {
			rpmArtifacts = [ "rpm" ]
		} else {
			rpmArtifacts = [ "rpm_debug" ]
		}
	} else {
		rpmArtifacts = [
			"${generalProperties.buildDistDir}/$artifactsMode/redhat/RPMS/${generalProperties.x86ArchName}/*.rpm",
			"scylla/tools/jmx/build/redhat/RPMS/noarch/${branchProperties.productName}-jmx*.rpm",
			"scylla/tools/java/build/redhat/RPMS/noarch/${branchProperties.productName}-tools*.rpm",
			"scylla/tools/python3/build/redhat/RPMS/${generalProperties.x86ArchName}/${branchProperties.productName}-python3*.rpm"
		]
	}

	rpmArtifacts.each { artifact ->
		getArtifact(artifact: artifact,
			targetPath: WORKSPACE,
			artifactSourceJob: args.artifactSourceJob,
			artifactSourceJobNum: args.artifactSourceJobNum,
			sourceUrl: args.sourceUrl,
			downloadFromCloud: downloadFromCloud,
			recursiveCloudCopy: true,
			ignoreMissingArtifact: false)
	}

	general.lsPath ("$WORKSPACE/${generalProperties.buildDistDir}/$artifactsMode/redhat/RPMS/${generalProperties.x86ArchName}", "Scylla CentOS RPMs")
	general.lsPath ("$WORKSPACE/${gitProperties.scyllaMachineImageCheckoutDir}/build", "Scylla machine image dir")
}

String relocPackageName (Map args) {
	// returns Scylla package name on cloud
	// Assuming a package naming convention: "${package name}-${build mode not on release}-${optional architecture}-package.tar.gz"
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false) checkLocal - true to check on local disk, false to check on cloud
	// boolean (default true) mustExist - If must exist - error if not found, else, return ""
	// String (mandatory): packagePrefix - The name of the package with no mode, architecture, package and tar.
	// String (default local): urlOrPath - From where to download the artifacts - local path or a URL.
	// String (default: none): buildMode (release | debug | dev)
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("artifact.relocPackageName", args)
	general.errorMissingMandatoryParam ("artifact.relocPackageName", [urlOrPath: "$args.urlOrPath", packagePrefix: "$args.packagePrefix"])

	boolean checkLocal = args.checkLocal ?: false
	boolean dryRun = args.dryRun ?: false
	boolean mustExist = args.mustExist != null ? args.mustExist : true
	if (dryRun) {
		mustExist = false
	}
	String buildMode = args.buildMode ?: ""
	String architecture = args.architecture ?: ""
	if (architecture){
		architecture += "-"
	}
	String packageName = "${args.packagePrefix}-${architecture}package.tar.gz"
	String packageNameNoArch = "${args.packagePrefix}-package.tar.gz"
	String packageNameX86 = "${args.packagePrefix}-${generalProperties.x86ArchName}-package.tar.gz"
	String lsOutput = ""
	if (buildMode.contains("debug") && args.packagePrefix == branchProperties.productName) {
		packageName = "${args.packagePrefix}-${buildMode}-${architecture}package.tar.gz"
		packageNameNoArch = "${args.packagePrefix}-${buildMode}-package.tar.gz"
		packageNameX86 = "${args.packagePrefix}-${buildMode}-${generalProperties.x86ArchName}-package.tar.gz"
	}

	if ((checkLocal && general.fileExistsOnPath(packageName, args.urlOrPath)) ||
			(! checkLocal && general.fileExistsOnCloud("${args.urlOrPath}${packageName}"))) {
		echo "Found package $packageName with architecure if given, or with no architecure if not given"
		return packageName
	}
	echo "Could not find package with architecture if given, or no arch if not given. Trying another way."

	String packageNameToReturn = ""
	if (architecture) {
		packageNameToReturn = packageNameNoArch
	} else {
		packageNameToReturn = packageNameX86
	}
	if ((checkLocal && general.fileExistsOnPath(packageNameToReturn, args.urlOrPath)) ||
			(! checkLocal && general.fileExistsOnCloud("${args.urlOrPath}${packageNameToReturn}"))) {
		echo "Found package $packageNameToReturn with architecure"
		return packageNameToReturn
	}
	if (mustExist) {
		error ("No package (with or without architecture) found")
	} else {
		echo "Didn't find any package"
		return ""
	}
}

def getRelocArtifacts (Map args) {
	// get Scylla package artifacts from cloud
	//
	// Parameters:
	// String (mandatory): cloudUrl - From where to download the artifacts.
	// String (mandatory): buildMode release|debug
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("artifact.getRelocArtifacts", args)
	general.errorMissingMandatoryParam ("artifact.getRelocArtifacts", [cloudUrl: "$args.cloudUrl", buildMode: "$args.buildMode"])
	String cloudUrl = general.addTrailingSlashIfMissing(args.cloudUrl)
	String architecture = args.architecture ?: ""

	def artifactsTargets = [:]
	String packageName = relocPackageName (
		checkLocal: false,
		mustExist: true,
		urlOrPath: cloudUrl,
		packagePrefix: branchProperties.productName,
		buildMode: args.buildMode,
		architecture: architecture,
	)
	String pythonPackageName = relocPackageName (
		checkLocal: false,
		mustExist: true,
		urlOrPath: cloudUrl,
		packagePrefix: "${branchProperties.productName}-python3",
		buildMode: args.buildMode,
		architecture: architecture,
	)

	artifactsTargets.scyllaReloc =    [artifact: packageName,
                                    target: "$WORKSPACE/scylla/build/${args.buildMode}/dist/tar"]
	artifactsTargets.jmxReloc =       [artifact: "${branchProperties.productName}-jmx-package.tar.gz",
                                    target: "$WORKSPACE/scylla/build/${args.buildMode}/dist/tar"]
	artifactsTargets.toolsJavaReloc = [artifact: "${branchProperties.productName}-tools-package.tar.gz",
                                    target: "$WORKSPACE/scylla/build/${args.buildMode}/dist/tar"]
	artifactsTargets.python3Reloc =   [artifact: pythonPackageName,
                                    target: "$WORKSPACE/scylla/build/${args.buildMode}/dist/tar"]
	artifactsTargets.metadataFile =   [artifact: generalProperties.buildMetadataFile,
                                    target: WORKSPACE]

	artifactsTargets.each { key, val ->
		getArtifact(artifact: val.artifact,
			targetPath: val.target,
			sourceUrl: cloudUrl,
			downloadFromCloud: true,
			ignoreMissingArtifact: false,
		)
	}
	publishMetadataFile ()
	general.lsPath (WORKSPACE, "content after getting artifacts of relocatable packages")
}

def getDbuildArtifacts (String artifactSourceJob, String artifactSourceJobNum) {
	if (!jenkins.runningOnAwsInstance()) {
		echo "Get dbuild artifact from the ${artifactSourceJob}, build number: |${artifactSourceJobNum}|"
		getArtifact(artifact: "scylla/tools/toolchain/**",
			targetPath: WORKSPACE,
			artifactSourceJob: artifactSourceJob,
			artifactSourceJobNum: artifactSourceJobNum,
			ignoreMissingArtifact: false)
	} else {
		echo "Running on an AWS machine, scylla/tools/toolchain/** should be on workspace from source control"
	}
}

def getTestArtifacts  (Map args) {
	// get Test artifacts from jenkins or cloud
	//
	// Parameters:
	// boolean (default false): downloadFromCloud - Whether to publish to cloud storage (S3) or not.
	// String (mandatory if downloadFromCloud is false): artifactSourceJob - From where to download the artifacts.
	// String (default: last success run): artifactSourceJobNum (number) to download from
	// String (mandatory if downloadFromCloud): cloudUrl - From where to download the artifacts.

	general.traceFunctionParams ("artifact.getTestArtifacts", args)

	boolean downloadFromCloud = args.downloadFromCloud ?: false
	String cloudUrl = args.cloudUrl ?: ""

	def mandatoryArgs = general.setMandatoryList(downloadFromCloud, [cloudUrl: "$args.cloudUrl"], [artifactSourceJob: "$args.artifactSourceJob"])
	general.errorMissingMandatoryParam ("artifact.getTestArtifacts", mandatoryArgs)

	String testArtifact = "scylla/${branchProperties.releaseTestDir}/**"
	if (downloadFromCloud) {
		testArtifact = "scylla/${branchProperties.releaseTestDir}"
	}

	getArtifact(artifact: testArtifact,
		targetPath: WORKSPACE,
		artifactSourceJob: args.artifactSourceJob,
		artifactSourceJobNum: args.artifactSourceJobNum,
		sourceUrl: cloudUrl,
		downloadFromCloud: downloadFromCloud,
		recursiveCloudCopy: true,
		ignoreMissingArtifact: false)
}

boolean publishArtifactsStatus (String wildcardFiles = "*.txt", String baseDir = WORKSPACE, String excludeWildcardFiles = "", boolean dryRun = false) {
	// This function gets baseDir as full path or as relative path based on the default dir when you call it.
	// It does cd on the baseDir.
	// The wildcardFiles should be a path, relative to the baseDir (or a file). This path (file) is the path to publihs.
	// So if you give a as baseDir and b/c.txt as File, you will see b/c.txt as the artifact.
	boolean status = false
	echo "Going to publish artifacts: |$wildcardFiles| from dir |$baseDir|, excluding |$excludeWildcardFiles|, dryRun: $dryRun"
	if (!dryRun) {
		boolean dirExists = fileExists "$baseDir"
		if (dirExists) {
			dir(baseDir) {
				def files = findFiles glob: "$wildcardFiles"
				boolean exists = files.length > 0
				if (exists) {
					try {
						archiveArtifacts artifacts: "$wildcardFiles", excludes: "$excludeWildcardFiles"
					} catch (error) {
						echo "Error: Could not publish |$wildcardFiles|. Error: |$error|"
						status = true
					}
				} else {
					echo "Nothing to publish. No |$wildcardFiles| under |$baseDir|"
					status = true
				}
			}
		} else {
			echo "Nothing to publish (no baseDir |$baseDir|)"
			status = true
		}
	}
	return status
}

def cloudBuildIdPath () {
	String specialSignature = ""
	if (JOB_NAME.contains("byo")) {
		specialSignature += "byo/"
	}
	def now = new Date()
	def textUtcTimeStamp = now.format("yyyy-MM-dd'T'HH:mm:ss'Z'")
	String buildID = "${specialSignature}${textUtcTimeStamp}"
	echo "Build ID for cloud Path: |$buildID|"
	return [textUtcTimeStamp, buildID]
}

String fetchMetadataValue (Map args) {
	// get a value from metadata file as artifact from jenkins or cloud
	// Download the file if does not exist locally
	//
	// Parameters:
	// boolean (default false): downloadFromCloud - Whether to publish to cloud storage (S3) or not.
	// String (mandatory if downloadFromCloud is false): artifactSourceJob - From where to download the artifacts.
	// String (default: last success run): artifactSourceJobNum (number) to download from
	// String (mandatory if downloadFromCloud): cloudUrl - From where to download the artifacts.
	// String (mandatory): fieldName - Name of field to take value from.
	// String (default: null): fileSuffix - extension for the metadata file name. Used on promotion, as it uses few files)

	general.traceFunctionParams ("artifact.fetchMetadataValue", args)

	boolean downloadFromCloud = args.downloadFromCloud ?: false
	String cloudUrl = args.cloudUrl ?: ""
	String fileSuffix = args.fileSuffix ?: ""

	def mandatoryArgs = general.setMandatoryList(downloadFromCloud, [cloudUrl: "$args.cloudUrl"], [artifactSourceJob: "$args.artifactSourceJob"])
	general.errorMissingMandatoryParam ("artifact.fetchMetadataValue", mandatoryArgs)

	// FixMe: We should improve this in the future to return a Metadata object that the callers can query.
	String metaDataFileName=generalProperties.buildMetadataFile
	if (fileSuffix) {
		metaDataFileName="${generalProperties.buildMetadataFile}-${fileSuffix}"
	}
	String metaDataFilePath="$WORKSPACE/${metaDataFileName}"
	if (! fileExists (metaDataFileName)) {
		echo "Could not find local $metaDataFilePath, download from build |$args.artifactSourceJob| number: |$args.artifactSourceJobNum| or from cloudUrl |$cloudUrl|"
		getArtifact(artifact: generalProperties.buildMetadataFile,
			targetPath: WORKSPACE,
			artifactSourceJob: args.artifactSourceJob,
			artifactSourceJobNum: args.artifactSourceJobNum,
			downloadFromCloud: downloadFromCloud,
			sourceUrl: cloudUrl,
			ignoreMissingArtifact: false
		)

		if (fileSuffix) {
			sh "mv ${generalProperties.buildMetadataFile} $metaDataFileName"
		}
	} else {
		echo "Found a local $metaDataFilePath"
	}

	fieldValue = general.runOrDryRunShOutput (false, "grep '$args.fieldName' $metaDataFilePath | awk '{print \$2}'", "Get metadata Value")
	echo "Value of field |$args.fieldName| from metadatafile of job |$args.artifactSourceJob|: |$fieldValue|"
	if (! fieldValue) {
		error ("Could not get $args.fieldName from relocatable metadata file")
	}
	return fieldValue
}

boolean publishS3Artifact (String fileToPublish, String baseDir = WORKSPACE, String destinationS3Bucket, boolean flattenDirs, String fileToExclude = "") {
	// This function gets baseDir as full path or as relative path based on the default dir when you call it.
	// It does cd on the baseDir.
	// The wildcardFiles should be a path, relative to the baseDir (or a file). This path (file) is the path to publihs.
	boolean status = false
	destinationS3Bucket = general.removeTrailingSlash (destinationS3Bucket)
	echo "Going to publish artifact |$fileToPublish| from dir |$baseDir| to S3 bucket: |$destinationS3Bucket|, flattenDirs: |$flattenDirs|, Excluding: |$fileToExclude|"
	boolean dirExists = fileExists "$baseDir"
	if (dirExists) {
		dir(baseDir) {
			def files = findFiles glob: "$fileToPublish"
			boolean exists = files.length > 0
			if (exists) {
				try {
					step([
						$class: 'S3BucketPublisher',
						entries: [[
							sourceFile: "$fileToPublish",
							excludedFile: "$fileToExclude",
							bucket: "$destinationS3Bucket",
							selectedRegion: "$generalProperties.amiRegion",
							noUploadOnFailure: false,
							managedArtifacts: false,
							uploadFromSlave: true,
							flatten: "$flattenDirs",
							showDirectlyInBrowser: false,
							keepForever: false,
						]],
						profileName: "$generalProperties.s3Profile",
						dontWaitForConcurrentBuildCompletion: false,
						dontSetBuildResultOnFailure: true,
					])
				} catch (error) {
					String strError = error.toString()
					if (strError.contains("java.io.IOException: Failed to upload files")) {
						echo "First try of publish failed. Retry:"
						try {
							step([
								$class: 'S3BucketPublisher',
								entries: [[
									sourceFile: "$fileToPublish",
									excludedFile: "$fileToExclude",
									bucket: "$destinationS3Bucket",
									selectedRegion: "$generalProperties.amiRegion",
									noUploadOnFailure: false,
									managedArtifacts: false,
									uploadFromSlave: true,
									flatten: "$flattenDirs",
									showDirectlyInBrowser: false,
									keepForever: false,
								]],
								profileName: "$generalProperties.s3Profile",
								dontWaitForConcurrentBuildCompletion: false,
								dontSetBuildResultOnFailure: true,
							])
							currentBuild.result = 'SUCCESS'
						} catch (error2ndTry) {
							echo "Error: Could not publish |$fileToPublish| from dir |$baseDir| to S3 bucket |$destinationS3Bucket| Second try. Error: |$error|"
							status = true
						}
					} else {
						echo "Error: Could not publish |$fileToPublish| from dir |$baseDir| to S3 bucket |$destinationS3Bucket|. Error: |$error|"
						status = true
					}
				}
			} else {
				echo "Nothing to publish to S3 bucket, file |$fileToPublish| not found in dir |$baseDir|"
				status = true
			}
		}
	} else {
		echo "Nothing to publish to S3 bucket (no |$baseDir| dir)"
		status = true
	}
	return status
}

def promoteNightlyLatest (boolean dryRun = false, String cloudPath) {
	cloudPath = general.removeTrailingSlash (cloudPath)
	cloudPath = general.addS3PrefixIfMissing(cloudPath)
	String destinationCloudRepoPath="$cloudPath/../latest"
	destinationCloudRepoPath = general.setTestingUrlIfNeeded (destinationCloudRepoPath)
	destinationCloudRepoPath = general.runOrDryRunShOutput (dryRun, "echo $destinationCloudRepoPath | sed s/'[^\\/]*\\/\\.\\.\\/'//g", "Get destination latest path")

	if (!dryRun && !general.fileExistsOnCloud (cloudPath)) {
		error("artifact.promoteNightlyLatest: Cloud URL to copy to latest unstable: $cloudPath does not exist.")
	}
	
	dir ("${gitProperties.scyllaPkgCheckoutDir}") {
		if (general.fileExistsOnCloud (destinationCloudRepoPath)) {
			general.runS3Rm (
				dryRun: dryRun,
				recursive: true,
				rmItem: destinationCloudRepoPath,
				description: "Remove old content from latest folder before promoting the new repo"
			)
		}

		general.runS3Cp (
			dryRun: dryRun,
			recursive: true,
			cpSource: cloudPath,
			cpTarget: destinationCloudRepoPath,
			description: "Copy repo to latest"
		)
	}
}

def addLineToBuildMetadataFile(String fieldToAdd = "", String valueToAdd = "", String buildMetadataFile = generalProperties.buildMetadataFile) {
	buildMetadataFile = "$WORKSPACE/$buildMetadataFile"
	sh "printf \"$fieldToAdd: $valueToAdd\n\" >> $buildMetadataFile"
}

def publishMetadataFile (String buildMetadataFile = generalProperties.buildMetadataFile, String cloudUrl1 = "", String cloudUrl2 = "") {
	boolean flattenDirs = false
	def publishStatus = publishArtifactsStatus(buildMetadataFile, WORKSPACE)
	if (cloudUrl1) {
		publishStatus |= artifact.publishS3Artifact(buildMetadataFile, WORKSPACE, cloudUrl1, flattenDirs)
	}
	if (cloudUrl2) {
		publishStatus |= artifact.publishS3Artifact(buildMetadataFile, WORKSPACE, cloudUrl2, flattenDirs)
	}
	if (publishStatus) {
		error("Could not publish $buildMetadataFile")
	}
}

def getDbuildAsArtifactOrFromSourceControl (Map args) {
	// Get dbuild files as artifacts or from source control.
	// Parameters:
	// string (default null): webUrl - From where to get the artifact (when you run on a cloud machine)
	// String (default none): artifactSourceJob - Name of job to get artifacts from, on local machine
	// String (default null): artifactSourceJobNum - From where to get the artifact (when you run on a local machine)
	// boolean downloadFromCloud (default false) false to download from Kenkins
	// String (default the stable branch): scyllaBranch - branch to get dbuild from source control

	general.traceFunctionParams ("artifact.getDbuildAsArtifactOrFromSourceControl", args)

	String webUrl = args.webUrl ?: ""
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String scyllaBranch = args.scyllaBranch ?: branchProperties.stableBranchName
	boolean downloadFromCloud = args.downloadFromCloud ?: false

	if (downloadFromCloud) {
		git.checkoutToDir (gitProperties.scyllaRepoUrl, scyllaBranch, gitProperties.scyllaCheckoutDir)
	} else {
		getDbuildArtifacts(args.artifactSourceJob, artifactSourceJobNum)
	}

	general.lsPath (WORKSPACE, "Workspace content after check-outs")
}

def publishCentOSRpmArtifacts (Map args) {
	// Publish RPMs and related items to jenkins and to cloud storage
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default: true): mainMode - True if this is the first mode we build.
	// String (mandatory): rpmCloudStoragePath The full URL to upload
	// String (default release): buildMode - debug or release

	general.traceFunctionParams ("artifact.publishCentOSRpmArtifacts", args)
	general.errorMissingMandatoryParam ("artifact.publishCentOSRpmArtifacts", [
		scyllaSha: "$args.scyllaSha",
		urlId: "$args.urlId",
		utcTextTimeStamp: "$args.utcTextTimeStamp",
	])

	boolean dryRun = args.dryRun ?: false
	boolean mainMode = args.mainMode != null ? args.mainMode : true
	String buildMode = args.buildMode ?: "release"
	String tmpDir = generalProperties.artifactsTempDir

	if ("${branchProperties.productName}".contains("manager")) {
		tmpDir = "$gitProperties.scyllaManagerCheckoutDir/dist/$generalProperties.artifactsTempDir"
	}
	String tmpModeDir = tmpDir

	String modeDirName = branchProperties.generalProductName
	String repoNameExt = branchProperties.repoFileName
	if (buildMode == "debug") {
		tmpModeDir = "$tmpDir/${branchProperties.generalProductName}-debug"
		modeDirName = "${branchProperties.generalProductName}-debug"
		repoNameExt = branchProperties.debugRepoFileName
	}
	boolean flattenDirs = false
	boolean status = false

	if (dryRun) {
		echo "Skipping CentOS RPM publish, as this is a dry run."
		return
	}

	String cloudPath = general.addS3PrefixIfMissing(args.rpmCloudStoragePath)

	if (mainMode) {
		general.runS3Rm (
			dryRun: dryRun,
			recursive: true,
			rmItem: cloudPath,
			description: "Remove old content from cloud storage if exists"
		)

		if (! "${branchProperties.productName}".contains("manager")) {
			status |= publishS3Artifact(generalProperties.buildMetadataFile, WORKSPACE, args.rpmCloudStoragePath, flattenDirs)
			status |= publishArtifactsStatus(generalProperties.buildMetadataFile, WORKSPACE)
		}
	}
	if (buildMode == "release") {
		status |= publishS3Artifact("$tmpModeDir/**/*.*", WORKSPACE, args.rpmCloudStoragePath, flattenDirs)
	} else {
		status |= publishS3Artifact("$tmpModeDir/**/*.*", WORKSPACE, "${args.rpmCloudStoragePath}${modeDirName}/", flattenDirs)
	}
	status |= publishS3Artifact(repoNameExt, "$WORKSPACE/$tmpDir", args.rpmCloudStoragePath, flattenDirs)

	if (status) {
		error("Error: Could not publish some artifacts. Check log for details")
	}
}

def publishUnifiedDebArtifacts (Map args) {
	// Publish Deb packages and related items to jenkins and to cloud storage
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default: true): mainMode - True if this is the first mode we build.
	// String (mandatory): unifiedDebCloudStoragePath The full URL to upload
	// String (default release): buildMode - debug or release

	general.traceFunctionParams ("artifact.publishUnifiedDebArtifacts", args)

	boolean dryRun = args.dryRun ?: false
	boolean mainMode = args.mainMode != null ? args.mainMode : true
	String buildMode = args.buildMode ?: "release"

	general.errorMissingMandatoryParam (
		"artifact.publishUnifiedDebArtifacts",
		[unifiedDebCloudStoragePath: "$args.unifiedDebCloudStoragePath"]
	)

	boolean flattenDirs = false
	boolean status = false
	String tmpDir = generalProperties.artifactsTempDir

	if (branchProperties.productName.contains("manager")) {
		tmpDir = "$gitProperties.scyllaManagerCheckoutDir/dist/$generalProperties.artifactsTempDir"
	}

	String tmpModeDir = tmpDir
	String unifiedPkgMainDir = branchProperties.scyllaUnifiedPkgRepo
	if (buildMode == "debug") {
		unifiedPkgMainDir = branchProperties.scyllaDebugUnifiedPkgRepo
	}

	if (dryRun) {
		echo "Skipping Unified deb publish, as this is a dry run."
		return
	}

	String cloudPath = general.addS3PrefixIfMissing(args.unifiedDebCloudStoragePath)

	if (mainMode) {
		general.runS3Rm (
			dryRun: dryRun,
			recursive: true,
			rmItem: cloudPath,
			description: "Remove old content from cloud storage if exists"
		)
		if (! branchProperties.productName.contains("manager")) {
			status |= publishS3Artifact(generalProperties.buildMetadataFile, WORKSPACE, args.unifiedDebCloudStoragePath, flattenDirs)
			status |= publishArtifactsStatus(generalProperties.buildMetadataFile, WORKSPACE)
		}
	}

	status |= publishS3Artifact("${tmpDir}/**/*.*", WORKSPACE, "${args.unifiedDebCloudStoragePath}${unifiedPkgMainDir}/", flattenDirs)
	status |= publishS3Artifact("${tmpDir}/**", WORKSPACE, "${args.unifiedDebCloudStoragePath}${unifiedPkgMainDir}/", flattenDirs)

	if (status) {
		error("Error: Could not publish some artifacts. Check log for details")
	}
}

def getAndPublishFileFromLastBuild (Map args) {
	// Get an artifact from last build (success or not) and Publish it.
	//
	// Parameters:
	// String (mandatory): artifact - The wanted artifact.
	// boolean (default false): ignoreMissingArtifact - whether to ignore error if file is missing

	general.traceFunctionParams ("artifact.getAndPublishFileFromLastBuild", args)
	general.errorMissingMandatoryParam ("artifact.getAndPublishFileFromLastBuild", [artifact: "$args.artifact"])

	if (!args.artifact) {
		error ("getAndPublishFileFromLastBuild: Missing mandatory parameter: $args.artifact")
	}

	boolean ignoreMissingArtifact = args.ignoreMissingArtifact ?: false

	try {
		lastbuildNum = currentBuild.previousBuild.getNumber()
		lastbuildNum = lastbuildNum.toString()
		echo "last args.jobPath number: |$lastbuildNum|"
		getArtifact(artifact: args.artifact,
			targetPath: WORKSPACE,
			artifactSourceJob: JOB_NAME,
			artifactSourceJobNum: lastbuildNum,
			ignoreMissingArtifact: ignoreMissingArtifact)

		if (fileExists(args.artifact)) {
			if (publishArtifactsStatus(args.artifact, WORKSPACE)) {
				echo "Could not publish $args.artifact. See log for details"
			}
		}
	} catch (error) {
		echo "getAndPublishFileFromLastBuild error: $error"
	}
}

String getLastSuccessfulUrl (Map args) {
	// String (default: empty): artifactWebUrl - URL of reloc or packaging items
	// String (default: empty): artifactSourceJob - base build name (next / build) - from where should the called builds take artifacts
	// String (default: empty): artifactSourceJobNum - number of base job (from where should the called builds take artifacts)
	// String (mandatory): fieldName - Name of field to take value from.

	general.traceFunctionParams ("artifact.getLastSuccessfulUrl", args)
	general.errorMissingMandatoryParam ("artifact.getLastSuccessfulUrl", [fieldName: "$args.fieldName"])

	String artifactWebUrl = args.artifactWebUrl ?: ""
	String artifactSourceJob = args.artifactSourceJob ?: ""
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String fieldName = args.fieldName ?: ""

	if (!artifactWebUrl || artifactWebUrl == "latest") {
		cloudUrl = artifact.fetchMetadataValue (
			downloadFromCloud: false,
			artifactSourceJob: artifactSourceJob,
			artifactSourceJobNum: artifactSourceJobNum,
			fieldName: fieldName,
		)
	} else {
		cloudUrl = artifactWebUrl
	}
	if (!general.fileExistsOnCloud (cloudUrl)) {
		error("artifact.getLastSuccessfulUrl: Cloud URL: $cloudUrl does not exist.")
	}
	return cloudUrl
}

def generateCloudformationAndPublish (String dpackagerCommand, String cloudformationGeneratedFile, String repoUrl) {
    dir ("$WORKSPACE/${gitProperties.scyllaMachineImageCheckoutDir}/${generalProperties.cloudformationTemplateFilePath}") {
        boolean flattenDirs = false
        general.runOrDryRunSh (false, "$dpackagerCommand -- jinja2 scylla.yaml.j2 > $WORKSPACE/$cloudformationGeneratedFile", "Generate scylla Cloudformation yaml file")
        publishS3Artifact(cloudformationGeneratedFile, WORKSPACE, repoUrl, flattenDirs)
        publishS3Artifact("${gitProperties.scyllaMachineImageCheckoutDir}/${generalProperties.cloudformationTemplateFilePath}/scylla.yaml", WORKSPACE, repoUrl, flattenDirs)
    }
}

return this
