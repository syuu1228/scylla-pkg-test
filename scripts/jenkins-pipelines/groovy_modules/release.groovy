def initPromotePipeline () {
	// This function is to avoid having a too long prepare stage. It sets variables as globals
	promoteEc2AmiDone=false
	promoteGceDone = false
	passedInstallationTestOSes = ""
	failedInstallationTestOSes = ""
	promoteArtifactsDone=false
	dockerPromoted=false
	tagCreated=false
	houseKeepingUpdated=false
	amiAllIdsFile = "ami_ids.csv"
	promoteAddressesFileName = "promote_addresses"
	promoteAddressesFile = "${promoteAddressesFileName}.txt"
	promoteAllOutputFileBaseName = "promote_relocatable_all_output"
	disableSubmodules = true // for pkg. Does not work without it. We need it to run shell scripts from pkg.
	promoteToLatest = false
	unstableRelocUrl = ""
	stableRelocUrl = ""
	unstableCentosUrl = ""
	stableCentosUrl = ""
	unstableUnifiedDebUrl = ""
	stableUnifiedDebUrl = ""
	centosScyllaReleaseId = ""
	unifiedDebScyllaReleaseId = ""
	relocScyllaRelease = ""
	gceImageSourceId = ""
	gceScyllaVersion = ""
	gceImageName = ""
	promotedGceImageId = ""
	gceInfoText = ""

	generalProperties = readProperties file: 'scripts/jenkins-pipelines/general.properties'
	// On debug run, this will be over-written later
	branchProperties = readProperties file: "scripts/jenkins-pipelines/branch-specific.properties"
	jenkins = load "${generalProperties.groovyPath}/jenkins.groovy"
	install = load "${generalProperties.groovyPath}/install.groovy"

	(debugRun, versionId, releaseNameFromVersionId) = release.setVersionRelease (params.VERSION_ID, params.DRY_RUN)
	isRcVersion = release.isRcVersion(versionId)
	debugOrDryRun = debugRun || params.DRY_RUN
	debugOrDryRunText = ""
	if (debugOrDryRun) {
		debugOrDryRunText = " (dry run or debug build)"
	}

	mail = load "${generalProperties.groovyPath}/mail.groovy"
	git = load "${generalProperties.groovyPath}/git.groovy"
	general = load "${generalProperties.groovyPath}/general.groovy"
	artifact = load "${generalProperties.groovyPath}/artifact.groovy"
	build    = load "${generalProperties.groovyPath}/build.groovy"
	test    = load "${generalProperties.groovyPath}/test.groovy"

	git.createGitProperties()
	gitProperties = readProperties file: "${generalProperties.gitPropertiesFileName}"

	preserveWorkspace=false // At the beginning - we always clean, as this is a release.
	git.cleanWorkSpaceUponRequest(preserveWorkspace)

	//override default generalProperties.defaultContainerTool since podman can't work on our qa-builders since they are running centos7
	if (NODE_NAME.contains("qa-builder")) {
		env.DPACKAGER_TOOL = "docker"
	} else {
		env.DPACKAGER_TOOL=generalProperties.defaultContainerTool
	}
}

def initPromoteBuilds () {
	// This function is to avoid having a too long prepare stage. It sets variables as globals
	(packageReleaseJobPath, packageReleaseBuildNum) = release.jobPathAndNum(
		jobName: branchProperties.packageReleaseJob,
		buildNum: params.PACKAGE_RELEASE_BUILD_NUM,
		calledBuildsDir: calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)

	(relocJobPath, relocJobNum) = release.jobPathAndNum(
		jobName: branchProperties.buildJobName,
		buildNum: params.RELOC_BUILD_NUM,
		calledBuildsDir: branchProperties.calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)

	(centosJobPath, centosBuildNum) = release.jobPathAndNum(
		jobName: branchProperties.centosJobName,
		buildNum: params.CENTOS_BUILD_NUM,
		calledBuildsDir: branchProperties.calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)

	(amiJobPath, amiBuildNum) = release.jobPathAndNum(
		jobName: branchProperties.amiJobName,
		buildNum: params.AMI_BUILD_NUM,
		calledBuildsDir: branchProperties.calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)

	(gceJobPath, gceBuildNum) = release.jobPathAndNum(
		jobName: branchProperties.gceJobName,
		buildNum: params.GCE_IMAGE_BUILD_NUM,
		calledBuildsDir: branchProperties.calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)

	(unifiedDebJobPath, unifiedDebBuildNum) = release.jobPathAndNum(
		jobName: branchProperties.unifiedDebJobName,
		buildNum: params.UNIFIED_DEB_BUILD_NUM,
		calledBuildsDir: branchProperties.calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)

	(dockerJobPath, dockerBuildNum) = release.jobPathAndNum(
		jobName: branchProperties.dockerJobName,
		buildNum: params.DOCKER_BUILD_NUM,
		calledBuildsDir: branchProperties.calledBuildsDir,
		dryRun: params.DRY_RUN,
		debugRun: debugRun)
}

def initMachineImageMetadataItems () {
	// This function is to avoid having a too long prepare stage. It sets variables as globals
	if (params.SKIP_EC2AMI || params.AMI_BUILD_NUM.toLowerCase() == "none") {
		artifact.getAndPublishFileFromLastBuild (
			artifact: amiAllIdsFile,
			ignoreMissingArtifact: true,
		)
	} else {
		general.errorIfMissingParam(amiBuildNum, "AMI_BUILD_NUM")
		step([  $class: 'CopyArtifact',
			filter: generalProperties.amiIdFile,
			fingerprintArtifacts: true,
			projectName: amiJobPath,
			selector: [$class: 'SpecificBuildSelector', buildNumber: amiBuildNum],
			target: WORKSPACE
		])
		amiProperties = readProperties file: "$WORKSPACE/${generalProperties.amiIdFile}"
		amiId = amiProperties.scylla_ami_id
		echo "AMI ID from ami $params.AMI_BUILD_NUM build: $amiId"
	}

	if (params.SKIP_GCE || params.GCE_IMAGE_BUILD_NUM.toLowerCase() == "none") {
		gceImageSourceId = "Skipped"
		gceScyllaVersion = "Skipped"
		gceImageName = "Skipped"
	} else {
		gceImageSourceId = artifact.fetchMetadataValue (
			artifactSourceJob: gceJobPath,
			artifactSourceJobNum: gceBuildNum,
			fieldName: "scylla-gce-image-id:"
		)
		gceImageDbUrl = artifact.fetchMetadataValue (
			artifactSourceJob: gceJobPath,
			artifactSourceJobNum: gceBuildNum,
			fieldName: "scylla-gce-image-db:"
		)

		gceScyllaVersion = artifact.fetchMetadataValue (
			artifactSourceJob: gceJobPath,
			artifactSourceJobNum: gceBuildNum,
			fieldName: "scylla-version:"
		)
		gceScyllaVersion = gceScyllaVersion.replaceAll("\\.", "-")
		gceImageName = "$branchProperties.productName-$gceScyllaVersion"
	}
}

def initPackageMetadataItems () {
	// This function is to avoid having a too long prepare stage. It sets variables as globals
	if (centosBuildNum) {
		unstableCentosUrl = artifact.fetchMetadataValue (
			artifactSourceJob: centosJobPath,
			artifactSourceJobNum: centosBuildNum,
			fieldName: "centos-rpm-url:",
			fileSuffix: "centos-rpm"
		)
		centosScyllaReleaseId = artifact.fetchMetadataValue (
			artifactSourceJob: centosJobPath,
			artifactSourceJobNum: centosBuildNum,
			fieldName: "scylla-release:",
			fileSuffix: "centos-rpm"
		)
		stableCentosUrl = "${branchProperties.stableCentosUrl}scylladb-${releaseNameFromVersionId}"
	}

	if (unifiedDebBuildNum) {
		unstableUnifiedDebUrl = artifact.fetchMetadataValue (
			artifactSourceJob: unifiedDebJobPath,
			artifactSourceJobNum: unifiedDebBuildNum,
			fieldName: "unified-deb-url:",
			fileSuffix: "unified-deb"
		)
		unifiedDebScyllaReleaseId = artifact.fetchMetadataValue (
			artifactSourceJob: unifiedDebJobPath,
			artifactSourceJobNum: unifiedDebBuildNum,
			fieldName: "scylla-release:",
			fileSuffix: "unified-deb"
		)
		unstableUnifiedDebUrl += "${branchProperties.scyllaUnifiedPkgRepo}/"
		stableUnifiedDebUrl = branchProperties.stableUnifiedDebUrl
	}

	if (params.SKIP_PROMOTE) {
		artifact.getAndPublishFileFromLastBuild (
			artifact: promoteAddressesFile,
			ignoreMissingArtifact: true,
		)
	}
	if (!params.SKIP_PROMOTE || !params.SKIP_PROMOTE_TEST) {
		if (relocJobNum) {
			unstableRelocUrl = artifact.fetchMetadataValue (
				artifactSourceJob: relocJobPath,
				artifactSourceJobNum: relocJobNum,
				fieldName: "reloc-pack-url:",
				fileSuffix: "build"
			)
			relocScyllaRelease = artifact.fetchMetadataValue (
				artifactSourceJob: relocJobPath,
				artifactSourceJobNum: relocJobNum,
				fieldName: "scylla-release:",
				fileSuffix: "build"
			)
			stableRelocUrl = "${branchProperties.baseDownloadsUrl}/downloads/${branchProperties.productName}/relocatable/scylladb-${releaseNameFromVersionId}"
		}
		if (!unstableRelocUrl && !unstableCentosUrl && !unstableUnifiedDebUrl) {
			error ("Could not get artifacts URLs for any artifact. Check log and parameters.")
		}

		release.errorIfReleaseIdsAreNotEqual (
			dryRun: debugOrDryRun,
			centosBuildNum: centosBuildNum,
			centosScyllaReleaseId: centosScyllaReleaseId,
			relocJobNum: relocJobNum,
			relocScyllaReleaseId: relocScyllaRelease,
			unifiedDebBuildNum: unifiedDebBuildNum,
			unifiedDebScyllaReleaseId: unifiedDebScyllaReleaseId,
		)
	}

	if (!(params.SKIP_DOCKER || params.SKIP_DOCKER_VERSION_TEST || params.SKIP_PROMOTE_LATEST_DOCKER || params.DOCKER_BUILD_NUM.toLowerCase() == "none")) {
		if (dockerBuildNum) {
			containerImageName = artifact.fetchMetadataValue (
				artifactSourceJob: dockerJobPath,
				artifactSourceJobNum: dockerBuildNum,
				fieldName: "docker-image-name:",
				fileSuffix: "docker"
			)
			(containerRepositoryFromMetadata, containerTagName) = containerImageName.tokenize( ':' )
		}
	}
	centosScyllaRepoUrl  = "${branchProperties.baseDownloadsUrl}/rpm/centos/scylla-${releaseNameFromVersionId}.repo"
}

def initReleaseSteps () {
	// This function is to avoid having a too long prepare stage. It sets variables as globals
	release.errorIfBranchFormatIsIllegal(releaseNameFromVersionId, gitProperties.scyllaRepoUrl)

	isOpenSourceRelease = release.isOpenSourceRelease(gitProperties.scyllaRepoUrl)
	release.getShaArtifact(packageReleaseJobPath, packageReleaseBuildNum)

	if (params.SKIP_PROMOTE_LATEST_DOCKER || params.DOCKER_BUILD_NUM.toLowerCase() == "none"){
		echo "Skipping promote latest docker due to user request"
		promoteToLatest = false
	} else if (isRcVersion) {
		echo "This is an RC release, We are not promoting latest docker"
		promoteToLatest = false
	} else if ("${branchProperties.latestRelease}".toBoolean()) {
		echo "This is a latest release, testing version of latest docker"
		promoteToLatest = true
	}
}

def initExpectedVersions () {
	// This function is to avoid having a too long prepare stage. It sets variables as globals
	expectedCentVersion = versionId
	expectedUnifiedVersion = versionId
	expectedRelocVersion = versionId
	if (debugOrDryRun) {
		if (centosBuildNum) {
			expectedCentVersion = artifact.fetchMetadataValue (
				artifactSourceJob: centosJobPath,
				artifactSourceJobNum: centosBuildNum,
				fieldName: "scylla-version:",
				fileSuffix: "centos-rpm"
			)
		}
		if (unifiedDebBuildNum) {
			expectedUnifiedVersion = artifact.fetchMetadataValue (
				artifactSourceJob: unifiedDebJobPath,
				artifactSourceJobNum: unifiedDebBuildNum,
				fieldName: "scylla-version:",
				fileSuffix: "unified-deb"
			)
		}
		if (relocJobNum) {
			expectedRelocVersion = artifact.fetchMetadataValue (
				artifactSourceJob: relocJobPath,
				artifactSourceJobNum: relocJobNum,
				fieldName: "scylla-version:",
				fileSuffix: "build"
			)
		}
	}
}

def errorIfReleaseIdsAreNotEqual (Map args) {
	// Check that all needed Release IDs are the same. Error if not.
	// boolean (default false): dryRun - Don't foce check, as IDs could be different
	// String (mandatory) centosBuildNum: A number of build or empty if not aplicable.
	// String (mandatory) centosScyllaReleaseId: An ID that was read from metadata (if exists).
	// String (mandatory): relocJobNum:  A number of build or empty if not aplicable.
	// String (mandatory): relocScyllaReleaseId: An ID that was read from metadata (if exists).
	// String (mandatory): unifiedDebBuildNum:  A number of build or empty if not aplicable.
	// String (mandatory): unifiedDebScyllaReleaseId: An ID that was read from metadata (if exists).

	general.traceFunctionParams ("release.errorIfReleaseIdsAreNotEqual", args)
	boolean dryRun = args.dryRun ?: false

	if (dryRun) {
		echo "This is a dry run, no need to enforce release IDs, as they could be different upon last success builds"
	} else {
		if (args.centosBuildNum && args.relocJobNum) {
			if (args.centosScyllaReleaseId != args.relocScyllaReleaseId) {
				error("Given CentOS build ID ($args.centosScyllaReleaseId) is for release ($args.centosScyllaReleaseId), while given Reloc build ID ($args.relocJobNum) is for release (${args.relocScyllaReleaseId}). They should be the same. Looks like bad build ID(s)")
			}
		}

		if (args.centosBuildNum && args.unifiedDebBuildNum) {
			if (args.centosScyllaReleaseId != args.unifiedDebScyllaReleaseId) {
				error("Given CentOS build ID ($args.centosScyllaReleaseId) is for release ($args.centosScyllaReleaseId), while given Unified-deb build ID ($args.unifiedDebBuildNum) is for release (${args.unifiedDebScyllaReleaseId}). They should be the same. Looks like bad build ID(s)")
			}
		}

		if (args.unifiedDebBuildNum && args.relocJobNum) {
			if (args.unifiedDebScyllaReleaseId != args.relocScyllaReleaseId) {
				error("Given Unified-deb build ID ($args.unifiedDebBuildNum) is for release ($args.unifiedDebScyllaReleaseId), while given Reloc build ID ($args.relocJobNum) is for release (${args.relocScyllaReleaseId}). They should be the same. Looks like bad build ID(s)")
			}
		}
	}
}

boolean isOpenSourceRelease(String gitURL = gitProperties.scyllaRepoUrl) {
	String url = gitURL.toLowerCase()
	return !url.contains("enterprise")
}

boolean isRcVersion(String versionId) {
	return versionId.contains("rc")
}

def errorIfVersionIdFormatIsIllegal(String versionId) {
	if (versionId) {
		String versionRegex3 = /(?<major>\d+).(?<minor>\d+).(?<description>[a-z0-9]+)/
		String versionRegex4 = /(?<major>\d+).(?<minor>\d+).(?<patch>\d+).(?<description>[a-z0-9]+)/
		if (versionId =~ versionRegex3 || versionId =~ versionRegex4) {
			echo "Version ID |$versionId| format is OK"
		} else {
			error("wrong version ID |$versionId| format. Should be n.n.n or n.n.rcn of n.n.n.rcn. Examples: 3.0.rc1, 3.1.0.rc0, 2018.1.8, 2.3.0")
		}
	} else {
		error("Missing mandatory parameter Release Name")
	}
}

def errorIfBranchFormatIsIllegal(String branchName, String gitRepo) {
	if (branchName) {
		errorIfReleaseVersionIsIllegal(branchName)
		String  branch = "branch-${branchName}"

		sshagent([generalProperties.gitUser]) {
			def branchExistsAnswer = sh(returnStdout: true, script: "git ls-remote --heads $gitRepo $branch | wc -l").trim()
			if (branchExistsAnswer != "1") {
				error("Branch $branchName does not exist on git repo: $gitRepo")
			}
		}
	} else {
		error("Missing mandatory parameter branch / version")
	}
}

def errorIfReleaseVersionIsIllegal (String branchName) {
	// Looking for n.n pattern ex. 4.0, 2018.1
	String  versionRegex = /(?<major>\d+).(?<minor>\d+)/
	if (branchName =~ versionRegex) {
		echo "Version name ($branchName) is OK"
	} else {
		error("Wrong version name ($branchName). should be n.n ex. 4.0, 2018.1")
	}
}

def errorIfTagIsIllegal(String tag, String gitRepo) {
	if (tag) {
		sshagent([generalProperties.gitUser]) {
			def tagExistsAnswer = sh(returnStdout: true, script: "git ls-remote --tags $gitRepo $tag | wc -l").trim()
			if (tagExistsAnswer != "1") {
				error("Tag $tag does not exist on git repo: $gitRepo")
			}
		}
	} else {
		error("Missing mandatory parameter last tag (for creating release notes)")
	}
}

String getReleaseNameFromVersionId(String versionId) {
	def (first, second, third) = versionId.tokenize( '.' )
	String releaseName = "${first}.${second}"
	echo "Release name from version ID: |$releaseName|"
	return releaseName
}

String getGitUser(boolean dryRun=false) {
	String gitUser = generalProperties.gitUser
	if (dryRun) {
		gitUser = generalProperties.dryRunGitUser
	}
	return gitUser
}

def getReleaseNoteEmailAddresses (boolean dryRun=false) {
	if (dryRun) {
		return generalProperties.debugMail
	} else {
		return generalProperties.releaseNotesMail
	}
}

def getStartingPromoteEmailAddresses (boolean dryRun=false) {
	if (dryRun) {
		return generalProperties.debugMail
	} else {
		return generalProperties.startReleaseMail
	}
}

def promotedEmailAddress (boolean dryRun=false, boolean successAll=true) {
	if (dryRun || ! successAll || currentBuild.currentResult != 'SUCCESS') {
		return generalProperties.debugMail
	} else {
		return generalProperties.releasePromotionMail
	}
}

def setupGitCommitter(boolean dryRun=false) {
	String runningUserEmail = ""
	String runningUserName = ""
	if (dryRun) {
		echo "This is a dryRun. Setting git user to be a non-privileged user"
		runningUserEmail = "$generalProperties.dryRunGitUserMail"
		runningUserName = "$generalProperties.dryRunGitUserName"
	} else {
		runningUserEmail = "$generalProperties.gitUserMail"
		runningUserName = "$generalProperties.gitUserName"
	}

	if (currentBuild.getBuildCauses().toString().contains("Started by user")) {
		// The wrap fails in case the build was triggered by an scm change / timer.
		wrap([$class: 'BuildUser']) {
			// https://wiki.jenkins-ci.org/display/JENKINS/Build+User+Vars+Plugin variables available inside this block
			runningUserEmail = "$BUILD_USER_EMAIL"
			runningUserName = "$BUILD_USER"
		}
	}

	env.GIT_AUTHOR_NAME = runningUserName
	env.GIT_AUTHOR_EMAIL = runningUserEmail
	echo "setupGitCommitter: env.GIT_AUTHOR_EMAIL: |$env.GIT_AUTHOR_NAME|, env.GIT_AUTHOR_EMAIL: |$env.GIT_AUTHOR_EMAIL|"
}

def pushBranches (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (default branchProperties.nextBranchName) nextBranch
	// String (mandatory) fileToPush - file that was changed. Use "-a" to all changed.
	// String (mandatory) commitMessage
	// String (default branchProperties.stableBranchName) stableBranch
	// String (mandatory) repoCheckoutDir
	// String (mandatory) repoUrl

	general.traceFunctionParams ("release.pushBranches", args)
	general.errorMissingMandatoryParam ("release.pushBranches",
		[fileToPush: "$args.fileToPush",
		 commitMessage: "$args.commitMessage",
		 repoUrl: "$args.repoUrl",
		 repoCheckoutDir: "$args.repoCheckoutDir"])

	boolean dryRun = args.dryRun ?: false
	String nextBranch = args.nextBranch ?: branchProperties.nextBranchName
	String stableBranch = args.stableBranch ?: branchProperties.stableBranchName

	setupGitCommitter(dryRun)
	String gitUser = getGitUser(dryRun)
	boolean sameShaOnRemote = git.sameShaOnRemoteBranches(args.repoUrl, stableBranch, nextBranch)

	sshagent([gitUser]) {
		general.runOrDryRunSh (dryRun, "git commit $args.fileToPush $args.commitMessage", "Commit |$args.fileToPush|")
		general.runOrDryRunSh (dryRun, "git push origin $stableBranch", "Push to repo: |$args.repoCheckoutDir| stable branch: |$stableBranch|")

		if (sameShaOnRemote) {
			general.runOrDryRunSh (dryRun, "git push origin ${stableBranch}:${nextBranch}", "Commit and Push repo: |$args.repoCheckoutDir| branch: |$nextBranch|")
		} else if (!dryRun){
			String jobDetails = "Build Job '${env.JOB_NAME} [${env.BUILD_NUMBER}] ${env.BUILD_URL}\n"
			String line1 = "The push to $nextBranch branch on repo ${args.repoCheckoutDir}.git failed because it does not match $stableBranch branch sha.\nRelease process can go on, but next run of next will fail.\n"
			String mergeWiki = "Please see the following URL for detailed instructions on how to resolve the issue: https://github.com/scylladb/scylla-pkg/wiki/Releng-merge-and-push-branches\n"
			echo "Warning: ${line1}${mergeWiki}"
			String mailText = "${jobDetails}${line1}${mergeWiki}"
			mail.mailResults("${relengAddress}", "Action Required: Push to ${args.repoCheckoutDir}.git branch $nextBranch failed", "${mailText}")
		}
	}
}

def versionFromVersionFile() {
	String versionLine = sh(returnStdout: true, script: "cat $WORKSPACE/${gitProperties.scyllaCheckoutDir}/${generalProperties.versionFileName} | grep ^VERSION=").trim()
	echo "Version from version file: |$versionLine|"
	def version = versionLine.split(/=/)[1]
	return version
}

def updateVersionFile (Map args) {
	/* Parameters:
	boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	boolean (default false): updateDockerUrl - Set to update URL on dockerfile
	String (default: null): pushToSpecificBranch - Use to push changes to a specific branch ex. next-*.*, manager-*.*
	boolean (default false): pushOnlyToStable - Set to push only to next
	boolean (default false): skipNextMasterVerification - Set to force this step, even if next it running.
	                         this is for the rare case we may want to run the release, and treat next later.
                             Mandatory for scylla-manager since it doesn't have next branch
	String (mandatory) repoCheckoutDir
	String (mandatory) repoUrl
	String (branchProperties.nextBranchName) nextBranch
	String (branchProperties.stableBranchName) stableBranch
	String (mandatory) versionId - Version ID to write on version file.Examples: 4.3.1, 2020.1.5
	String (mandatory if updateDockerUrl) releaseName version fro dockerfile URL
	String (default generalProperties.versionFileName) versionFilePath - Path for the file contains the product VERSION
	*/

	general.traceFunctionParams ("release.updateVersionFile", args)
	general.errorMissingMandatoryParam ("release.updateVersionFile",
		[repoCheckoutDir: "$args.repoCheckoutDir",
		repoUrl: "$args.repoUrl",
		versionId: "$args.versionId"
		])

	boolean dryRun = args.dryRun ?: false
	boolean updateDockerUrl = args.updateDockerUrl ?: false
	boolean pushToSpecificBranch = args.pushToSpecificBranch ?: ''
	boolean skipNextMasterVerification = args.skipNextMasterVerification ?: false
	String nextBranch = args.nextBranch ?: branchProperties.nextBranchName
	String versionFilePath = args.versionFilePath ?: generalProperties.versionFileName
	String stableBranch = args.stableBranch ?: branchProperties.stableBranchName
	if (updateDockerUrl) {
		general.errorMissingMandatoryParam ("release.updateVersionFile",
			[releaseName: "$args.releaseName"]
		)
	}

	String baseBranch = stableBranch
	if (pushToSpecificBranch) {
		baseBranch = nextBranch
	} else if (! skipNextMasterVerification){
		if (!git.sameShaOnRemoteBranches(args.repoUrl, baseBranch, nextBranch)) {
			error ("The SHA on $baseBranch is not equal to $nextBranch, updating version files will cause conflicts. You may skip this test by checking the RUN_EVEN_IF_NEXT_IS_RUNNING parameter")
		}
	}

	String gitUser = getGitUser(dryRun)

	dir ("$WORKSPACE/${args.repoCheckoutDir}") {
		sshagent([gitUser]) {
			sh "git checkout $baseBranch"
			sh "git status"
		}
		if (branchProperties.productName == 'scylla-manager') {
			sh "sed -i -e \"0,/^VERSION.*/s/^VERSION.*/VERSION	     ?={args.versionId}/g\" ${versionFilePath}"
		} else {
			sh "sed -i -e \"s/^VERSION.*/VERSION=${args.versionId}/g\" ${versionFilePath}"
		}



		echo "version file after sed: ---------------------------------"
		sh "cat $versionFilePath"
		echo "---------------------------------------------------------"
		String filesToCommit = versionFilePath
		String commitMessage = "-m \"${generalProperties.commitPrefix} $args.versionId\""
		if (pushToSpecificBranch) {
			sshagent([gitUser]) {
				general.runOrDryRunSh (false, "git commit $filesToCommit $commitMessage", "Commit files to $pushToSpecificBranch only")
				general.runOrDryRunSh (dryRun, "git push origin $pushToSpecificBranch", "Push to $pushToSpecificBranch branch")
			}
		} else {
			pushBranches (
				repoCheckoutDir: args.repoCheckoutDir,
				repoUrl: args.repoUrl,
				fileToPush: filesToCommit,
				commitMessage: commitMessage,
				dryRun: dryRun,
				nextBranch: nextBranch,
				stableBranch: stableBranch,
			)
		}
	}
}

def uploadMetaData (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory) pkgCheckoutDir - Dir name to update repo files on
	// String (mandatory) scriptsDir - pkg full path to run scripts from
	// String (mandatory) releaseName - 2 numbers such as 3.4 or 2020.1
	// String (mandatory) productName - scylla or scylla-enterprise

	general.traceFunctionParams ("release.uploadMetaData", args)
	general.errorMissingMandatoryParam ("release.uploadMetaData",
		[pkgCheckoutDir: "$args.pkgCheckoutDir",
		 scriptsDir: "$args.scriptsDir",
		 releaseName: "$args.releaseName",
		 productName: "$args.productName"])

	boolean dryRun = args.dryRun ?: false

	dir ("$WORKSPACE/${args.pkgCheckoutDir}") {
		echo "Starting uploadAllMetaData, OSes: |${branchProperties.supportedOS}|"
		String osUploadRepoParams = ""
		String generalUploadRepoParams = "--version=${args.releaseName}"
		generalUploadRepoParams += " --product=${args.productName}"
		generalUploadRepoParams += " --base_url=${branchProperties.baseDownloadsUrl}"
		if (dryRun) {
			generalUploadRepoParams += " --dry_run"
		}

		def osString = branchProperties.supportedOS
		ArrayList osList = osString.split('\\,')
		osList.each { os ->
			if (os == "ubuntu") {
				osUploadRepoParams = " --platform=$os --target=$branchProperties.ubuntuScyllaListLocation"
				osUploadRepoParams += " --dist_names=$branchProperties.supportedUbuntuDistNames"
			} else if (os == "debian") {
				osUploadRepoParams = " --platform=$os --target=$branchProperties.debianScyllaListLocation"
				osUploadRepoParams += " --dist_names=$branchProperties.supportedDebianDistNames"
			} else {
				osUploadRepoParams = " --platform=$os --target=$branchProperties.centosScyllaRepoLocation"
			}
			sh "${args.scriptsDir}/upload-repo-file.sh $generalUploadRepoParams $osUploadRepoParams"
		};
	}
}

def setPkgRepos (String pkgCheckoutDir, boolean dryRun=false, String versionString) {
	echo "setPkgRepos: Update Repo Sub Projects on PKG |$pkgCheckoutDir|, version string is: |$versionString|"
	def submoduleSummaryPath = "$WORKSPACE/${generalProperties.submoduleSummaryFile}"

	def gitUser = getGitUser(dryRun)

	dir ("$WORKSPACE/${pkgCheckoutDir}") {
		sh "echo '${generalProperties.commitPrefix} $versionString' > $submoduleSummaryPath"

		sshagent([gitUser]) {
			def submodulesString = branchProperties.pkgGitSubmodules
			ArrayList submoduleList = submodulesString.split('\\,')
			submoduleList.each { submodule ->
				echo "Updating submodule ${submodule}"
				dir ("$WORKSPACE/${pkgCheckoutDir}/${submodule}") {
					echo "Before fetch, Running in dir"
					sh "pwd"
					sh "git fetch"
					sh "git reset --hard origin/$branchProperties.stableBranchName"
				}
			};

			if (versionString.contains("rc0")) {
				echo "This is RC0, skipping submodules summary on commit message"
			} else {
				sh "git submodule summary >> $submoduleSummaryPath"
			}
			echo "------------ submodule summary file ----------"
			sh "cat $submoduleSummaryPath"
			echo "---------------------------------------------"

			boolean publishStatus = artifact.publishArtifactsStatus(generalProperties.submoduleSummaryFile, WORKSPACE)
			if (publishStatus && !dryRun){
				error("Could not publish the submodules file")
			}
		}
		pushBranches (
			repoCheckoutDir: pkgCheckoutDir,
			fileToPush: "-a",
			commitMessage: "--file $submoduleSummaryPath",
			dryRun: dryRun,
			nextBranch: branchProperties.nextBranchName,
			stableBranch: branchProperties.stableBranchName)
	}
}

def genReleaseNotes (String checkoutDir, String pkgDir, String versionString, String lastTag) {
	echo "genReleaseNotes: checkoutDir |$checkoutDir|, pkgDir: |$pkgDir|, versionString: |$versionString|, oldTag: |$lastTag|"
	dir (WORKSPACE) {
		Date date = new Date()
		String datePart = date.format("dd/MM/yyyy")
		def dpackagerCommand = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}"

		String genReleaseNotesScriptCall  = "${pkgDir}/scripts/gen-release-notes"
			genReleaseNotesScriptCall += ' --gh-access-token=$GITHUB_ACCESS_TOKEN'
			genReleaseNotesScriptCall += " --prev-commit=$lastTag"
			genReleaseNotesScriptCall += " --curr-commit=HEAD"

		sh "echo \"Release Notes for $versionString, date: $datePart\" > ${generalProperties.releaseNotesFile}"
		sh "echo \"Please note: The script generates the list of items by searching for some keyword strings on commit logs.\" >> ${generalProperties.releaseNotesFile}"
		sh "echo \"Keywords (case insensitive) fix, fixes, fixed, close, closes, closed, resolve, resolves, resolved: \" >> ${generalProperties.releaseNotesFile}"
		sh "echo \"Commits that do not have these keywords followed by #number will not be reported.\" >> ${generalProperties.releaseNotesFile}"

		if (!isOpenSourceRelease) {
			sh "echo \"Please also note that as this is an Enterprise release, issue numbers might be of Enterprise and of Open source Repos.\" >> ${generalProperties.releaseNotesFile}"
			sh "echo \"Usually the little numbers are of Enterprise, and the higher numbers are of Open Source\" >> ${generalProperties.releaseNotesFile}"
			sh "echo \"The script looks for issue numbers on Enterprise first (only if github access token was given). If found, print them, if not - look on Open Source Repo too.\" >> ${generalProperties.releaseNotesFile}"
		}

		ArrayList repos = branchProperties.gitRepositoriesForReleaseNotes.split('\\,')
		repos.each { repo ->
			sh "$dpackagerCommand -- ${genReleaseNotesScriptCall} --git-repo=$repo >> ${generalProperties.releaseNotesFile}"
		}

		echo "Release notes file on dir $WORKSPACE: -----------"
		sh "cat ${generalProperties.releaseNotesFile}"
		echo "-----------------------------------------------------"
	}
}

def getShaArtifact (String baseBuildPath, String artifactSourceJobNum) {
	echo "Get SHAs artifact from the $baseBuildPath, build number: |$artifactSourceJobNum|"
	def shasArtifact = generalProperties.gitRepoShaFileName

	if (artifactSourceJobNum) {
		step([  $class: 'CopyArtifact',
			filter: "$shasArtifact",
			fingerprintArtifacts: true,
			projectName: "$baseBuildPath",
			selector: [$class: 'SpecificBuildSelector', buildNumber: "$artifactSourceJobNum"],
			target: "$WORKSPACE"
		])
	} else {
			step([  $class: 'CopyArtifact',
			filter: "$shasArtifact",
			fingerprintArtifacts: true,
			projectName: "$baseBuildPath",
			target: "$WORKSPACE"
		]);
	}
	shaProperties = readProperties file: "$WORKSPACE/$shasArtifact"
}

boolean setVersionRelease (String versionId, boolean dryRun) {
	boolean debugRun = (jenkins.debugBuild () && !dryRun)
	if (!versionId) {
		if (dryRun || debugRun) {
			echo "As this build runs on dry-run or releng job, and didn't get versionId, set it to default dry-run value."
			versionId = "${branchProperties.defaultDryRunReleaseName}.999"
		} else {
			error ("When running build not on dry-run or releng job, you must give a value on VERSION_ID parameter")
		}
	}

	String releaseNameFromVersionId = getReleaseNameFromVersionId(versionId)
	errorIfVersionIdFormatIsIllegal(versionId)
	return [ debugRun, versionId, releaseNameFromVersionId ]
}

def setDebugProperties (boolean debugRun) {
	if (debugRun) {
		String propertiesPath = "$WORKSPACE/${gitProperties.scyllaPkgScriptsCheckoutDir}/scripts/jenkins-pipelines"
		echo "Setting properties for debug run"
		sh "sed -i \"s/PUT_VERSION_HERE/$releaseNameFromVersionId/g\" $propertiesPath/branch-specific-debug.properties"
		sh "cat $propertiesPath/branch-specific-debug.properties >> $propertiesPath/branch-specific.properties"
		branchProperties = readProperties file: "$propertiesPath/branch-specific.properties"
		echo "After loading debug properties: nextBranchName: |${branchProperties.nextBranchName}|, debugVersionNameForReleaseNotes: |${branchProperties.debugVersionNameForReleaseNotes}"
	}
}

def jobPathAndNum (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false) debugRun - true if job is running from releng testing dir
	// String (mandatory) jobName
	// String (mandatory) calledBuildsDir - from where to read the job
	// String (mandatory) buildNum - to promote (can be also "none" for skipping)

	general.traceFunctionParams ("release.jobPathAndNum", args)
	boolean dryRun = args.dryRun ?: false
	boolean debugRun = args.debugRun ?: false

	def mandatoryArgs = general.setMandatoryList ((dryRun || debugRun),
		[jobName: "$args.jobName", calledBuildsDir: "$args.calledBuildsDir"],
		[jobName: "$args.jobName", calledBuildsDir: "$args.calledBuildsDir", buildNum: "$args.buildNum"])
	general.errorMissingMandatoryParam ("release.jobPathAndNum", mandatoryArgs)

	String jobPath = "${branchProperties.calledBuildsDir}${args.jobName}"
	String buildNum = args.buildNum

	if (dryRun) {
		jobPath = "${args.calledBuildsDir}${args.jobName}"
	} else if (debugRun) {
		jobPath = "${branchProperties.relengDebugGetArtifactsBuildsDir}${args.jobName}"
	} else if (args.buildNum.toLowerCase() == "none") {
		buildNum = ""
	}

	if (dryRun || debugRun) {
		if (!args.buildNum) {
			buildNum = Jenkins.instance.getItemByFullName(jobPath).lastSuccessfulBuild.number.toString()
		}
	}
	echo "dryRun: ${dryRun}, debugRun: ${debugRun}, Take artifacts from: $jobPath number: $buildNum"
	return [jobPath, buildNum]
}

String promotedGce (Map args) {
	// Prepare GCE image for test
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (default: "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}"): pkgCheckoutDir - Dir to run command in
	// String (default: empty): dpackagerCommand - An already set cpackager command
	// String (mandatory): gceImageName - Name of souce GCE
	// String (mandatory): gceImageSourceId - ID of source GCE
	// String (mandatory) calledBuildsDir - from where to run artifact test

	general.traceFunctionParams ("release.promotedGce", args)
	general.errorMissingMandatoryParam ("release.promotedGce", [gceImageName: "$args.gceImageName", gceImageSourceId: "$args.gceImageSourceId", calledBuildsDir: "$args.calledBuildsDir"])

	boolean dryRun = args.dryRun ?: false
	String pkgCheckoutDir = args.pkgCheckoutDir ?: "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}"
	String dpackagerCommand = args.dpackagerCommand ?: ""
	String promotedGceImageId = ""

	if (! dpackagerCommand) {
		dpackagerCommand = "$pkgCheckoutDir/${generalProperties.dpackagerPath}"
	}
	dpackagerCommand += ' -e GOOGLE_APPLICATION_CREDENTIALS=$GOOGLE_APPLICATION_CREDENTIALS -v $GOOGLE_APPLICATION_CREDENTIALS:$GOOGLE_APPLICATION_CREDENTIALS'

	dir (pkgCheckoutDir) {
		withCredentials([file(credentialsId: generalProperties.jenkinsGoogleApplicationKey, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
			setCloudAutoCmd = dpackagerCommand + ' -- gcloud auth activate-service-account --key-file $GOOGLE_APPLICATION_CREDENTIALS'
			general.runOrDryRunSh (dryRun, setCloudAutoCmd, "Set cloud auth")

			general.runOrDryRunSh (dryRun, "$dpackagerCommand -- gcloud compute images create $args.gceImageName --family ${branchProperties.productName} --source-image $args.gceImageSourceId --source-image-project ${branchProperties.gceImagesProjectName} --project ${branchProperties.gceImagesProjectName}", "Create GCE image")
			general.runOrDryRunSh (dryRun, "$dpackagerCommand -- gcloud compute images add-iam-policy-binding $args.gceImageName --member='$generalProperties.gceTestsServiceAccount' --role='roles/compute.imageUser' --project ${branchProperties.gceImagesProjectName}", "Set SCT permissions for new Image")

			if (dryRun) {
				promotedGceImageId = "dry-run"
			} else {
				promotedGceImageId = sh(script: "$dpackagerCommand -- gcloud compute images describe $args.gceImageName | grep ^id | awk '{print \$2}'", returnStdout:true).trim()
				promotedGceImageId = promotedGceImageId.replaceAll("'","")

				if (! promotedGceImageId) {
					error ("Could not get GCE image ID needed for test. Could be an environmental issue, please check.")
				}
			}
			general.runOrDryRunSh (dryRun, "$dpackagerCommand -- gcloud compute images remove-iam-policy-binding $args.gceImageName --member='$generalProperties.gceTestsServiceAccount' --role='roles/compute.imageUser' --project ${branchProperties.gceImagesProjectName}", "Remove SCT permissions for new Image")
			general.runOrDryRunSh (dryRun, "$dpackagerCommand -- gcloud compute images add-iam-policy-binding $args.gceImageName --member='allAuthenticatedUsers' --role='roles/compute.imageUser' --project ${branchProperties.gceImagesProjectName}", "Promote GCE image")
		}
	}
	return promotedGceImageId
}

return this
