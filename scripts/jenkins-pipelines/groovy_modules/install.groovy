boolean expectedVersion (Map args) {
	// Check if Scylla version is as expected
	//
	// Parameters:
	// String (mandatory): os2Test - Name for OS
	// String (mandatory): scyllaExpectedRelease - The version we expect, such as 2020.1.0 or 4.2.1
	// String (mandatory): scyllaExpectedVersionId - Scylla SHA ID, such as 0.20200815.8cc2a9cc98
	// String (mandatory): dockerLogFilePath path of log to write (will be published as an artifact).
	// String (mandatory): installedMode: The installed mode ((release|debug|dev|sanitize))
	// String (default: release) scyllaExpectedMode: The mode we expect (release|debug|dev|sanitize)
	// String (mandatory) installedVersion: The installed version (output of scylla --version)

	general.traceFunctionParams ("install.expectedVersion", args)
	general.errorMissingMandatoryParam ("install.expectedVersion",
		[os2Test: "$args.os2Test",
		 scyllaExpectedRelease: "$args.scyllaExpectedRelease",
		 scyllaExpectedVersionId: "$args.scyllaExpectedVersionId",
		 dockerLogFilePath: "$args.dockerLogFilePath"])

	String scyllaExpectedMode = args.scyllaExpectedMode ?: "release"
	String os2Test = args.os2Test
	String scyllaExpectedRelease = args.scyllaExpectedRelease
	String scyllaExpectedVersionId = args.scyllaExpectedVersionId
	String dockerLogFilePath = args.dockerLogFilePath
	String installedMode = args.installedMode
	String installedVersion = args.installedVersion

	boolean expectedVersion = false
	String msgPrefix = "Installation of Scylla on $os2Test version |$installedVersion| mode |$installedMode|"
	String msgExpected = "|$scyllaExpectedRelease| and |$scyllaExpectedVersionId| mode |$scyllaExpectedMode|"
	if (installedVersion.contains(scyllaExpectedRelease) && installedVersion.contains(scyllaExpectedVersionId) && scyllaExpectedMode == installedMode) {
		expectedVersion = true
		general.writeTextToLogAndFile ("Success: $msgPrefix (contains: $msgExpected as expected).", dockerLogFilePath)
	} else {
		String errorVersionText = "$msgPrefix, while expecting it to contain: $msgExpected"
		general.writeTextToLogAndFile ("Error: $errorVersionText", dockerLogFilePath)
	}
	return expectedVersion
}

boolean installScyllaVersion (Map args) {
	// Install latest Scylla in docker, for each supported OS,
	// Verify that the version we get, is the one we promoted.
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): testGetScylladb - Set true to test the test get scylladb script. Default will get a URL to test.
	// String (default generalProperties.scyllaWebInstallUrl): scyllaWebInstallUrl - Change when debugging the script.
	// String (mandatory): os2Test - Name for OS - dir of dockerfile, and to be used as part of docker tag
	// String (mandatory): scyllaExpectedRelease - The version we expect, such as 2020.1.0 or 4.2.1
	// String (mandatory): scyllaExpectedVersionId - Scylla SHA ID, such as 0.20200815.8cc2a9cc98
	// String (mandatory): dockerLogFile path of log to write (will be published as an artifact).
	// String (mandatory): urlToInstall - download url of scylla.list for deb/ubuntu or scylla.repo for CentOS.
	//                     test-get-scylladb needs a version such as nightly-2021.1 or nightly-master or latest-official keyword
	// String (default: release): scyllaExpectedMode - Expected installation mode (release|debug|dev|sanitize)
	// String (default: branchProperties.productName) productName For test repo

	general.traceFunctionParams ("install.installScyllaVersion", args)
	general.errorMissingMandatoryParam ("install.installScyllaVersion",
		[os2Test: "$args.os2Test",
		 scyllaExpectedRelease: "$args.scyllaExpectedRelease",
		 scyllaExpectedVersionId: "$args.scyllaExpectedVersionId",
		 dockerLogFile: "$args.dockerLogFile",
		 urlToInstall: "$args.urlToInstall"])

	boolean dryRun = args.dryRun ?: false
	boolean testGetScylladb = args.testGetScylladb ?: false
	String scyllaExpectedMode = args.scyllaExpectedMode ?: "release"
	String productName = args.productName ?: branchProperties.productName
	String scyllaWebInstallUrl = args.scyllaWebInstallUrl ?: generalProperties.scyllaWebInstallUrl
	String os2Test = args.os2Test
	String scyllaExpectedRelease = args.scyllaExpectedRelease
	String scyllaExpectedVersionId = args.scyllaExpectedVersionId
	String dockerLogFile = args.dockerLogFile
	String dockerLogFilePath = "$WORKSPACE/$dockerLogFile"
	String urlToInstall = args.urlToInstall

	String dockerDirName = ""
	String dockerDir = ""
	String containerImageName = ""
	String containerImageNameTag = ""
	String dockerArgList = ""
	if (testGetScylladb) {
		dockerDirName = generalProperties.getScylladbDockersPath
		containerImageName = "${os2Test}-get-scylladb-${urlToInstall}"
		dockerArgList += " --build-arg SCYLLA_PRODUCT_NAME=${branchProperties.productName}"
		if (jenkins.debugBuild() && !	(JOB_NAME.contains(branchProperties.promoteReleaseJob))) {
			String testUrlHttp = general.addHttpPrefixIfMissing (generalProperties.debugBaseDownloadsUrl)
			dockerArgList += " --build-arg SCYLLA_BASE_URL=\"--scylla-base-url $testUrlHttp\""
		} else {
			dockerArgList += " --build-arg SCYLLA_BASE_URL=\"\""
		}
		dockerArgList += " --build-arg SERVER_TEST_BASE_URL=$scyllaWebInstallUrl"
		if (urlToInstall != "latest-official") {
			dockerArgList += " --build-arg SCYLLA_VERSION=$urlToInstall"
		}
		if (dryRun) {
			dockerArgList += " --build-arg DRY_RUN=--dry-run"
		} else {
			dockerArgList += " --build-arg DRY_RUN=\"\""
		}
	} else {
		dockerDirName = generalProperties.dockersPath
		containerImageName = "${os2Test}-scylla-version-testing"
		dockerArgList += " --build-arg SCYLLA_REPO_URL=$urlToInstall"
		dockerArgList += " --build-arg SCYLLA_PRODUCT_NAME=${branchProperties.productName}"

		if (!os2Test.contains("centos")) {
			dockerArgList += " --build-arg SCYLLA_GPG_KEY=${branchProperties.scyllaGpgPublicKey}"
			dockerArgList += " --build-arg KEY_SERVER=${generalProperties.ubuntuDebianKeyServer}"
			if (os2Test.contains("debian")) {
				dockerArgList += " --build-arg FETCH_KEYS_URL=${generalProperties.debianFetchKeysUrl}"
			}
		}
	}
	dockerDir = "$WORKSPACE/${gitProperties.scyllaPkgScriptsCheckoutDir}/$dockerDirName/${os2Test}"
	boolean dirExists = fileExists "$dockerDir"
	if (!dirExists) {
		dockerDir = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/$dockerDirName/${os2Test}"
	}

	boolean installVersionStatus = true
	boolean dockerCreated = false
	boolean dockerFailed = false
	String dockerErrorString = ""
	String installedVersion = ""
	String installedMode = "none"
	containerImageNameTag = "${containerImageName}:1"
	dpackagerTool = env.DPACKAGER_TOOL
	String buildContainerCmd = "bash -c set -o pipefail; $dpackagerTool build $dockerArgList --no-cache -t $containerImageNameTag . 2>&1 | tee $dockerLogFilePath"
	String getVersionCmd = "$dpackagerTool run --rm --cap-add SYS_PTRACE $containerImageNameTag scylla --version"
	String getModeCmd = "$dpackagerTool run --rm --cap-add SYS_PTRACE $containerImageNameTag scylla --build-mode"

	dir (dockerDir) {
		try {
			(docker_username,docker_password) = general.dockerCredentials(dryRun)
			withCredentials([
				string(credentialsId: docker_username, variable: 'DOCKER_USERNAME'),
				string(credentialsId: docker_password, variable: 'DOCKER_PASSWORD')
			]) {
				dockerCredentials = '-u=$DOCKER_USERNAME -p=$DOCKER_PASSWORD'
				general.runOrDryRunSh (dryRun, "$generalProperties.defaultContainerTool login $generalProperties.containerRegistry $dockerCredentials", "Docker login")
				general.runOrDryRunSh (dryRun, buildContainerCmd, "Build Docker for Scylla ${scyllaExpectedRelease} on ${os2Test}")

				general.writeTextToLogAndFile ("\n===================\nbuild docker Command: |$buildContainerCmd|\nInstallation was done from URL: $urlToInstall", dockerLogFilePath)
				dockerCreated = true
				installedVersion = general.runOrDryRunShOutput (dryRun, getVersionCmd, "Get Scylla version")
				if (scyllaExpectedMode != "none") {
					installedMode = general.runOrDryRunShOutput (dryRun, getModeCmd, "Get Scylla mode")
				}
			}
		} catch (error) {
			dockerErrorString = "Could not get version or mode of Scylla. error: $error"
			general.writeTextToLogAndFile (dockerErrorString, dockerLogFilePath)
			artifact.publishArtifactsStatus(dockerLogFile, WORKSPACE)
			dockerFailed = true
		} finally {
			if (dockerCreated) {
				general.cleanDockerImage (containerImageNameTag)
			}
			if (dockerFailed) {
				error (dockerErrorString)
			}
		}

		if (!dryRun) {
			installVersionStatus = expectedVersion (
				installedVersion: installedVersion,
				scyllaExpectedRelease: scyllaExpectedRelease,
				scyllaExpectedVersionId: scyllaExpectedVersionId,
				installedMode: installedMode,
				scyllaExpectedMode: scyllaExpectedMode,
				os2Test: os2Test,
				dockerLogFilePath: dockerLogFilePath
			)
			artifact.publishArtifactsStatus(dockerLogFile, WORKSPACE)
		}
	}
	return installVersionStatus
}

def parallelTestScyllaVersion (Map args) {
	// Run installation tests on supported CentOS distributions,
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): justPrintErrors - Don't fail on errors
	// boolean (default false): testGetScylladb - Set true to test using the test get scylladb script. Default will get a URL to test.
	// String (default generalProperties.scyllaWebInstallUrl): scyllaWebInstallUrl - Change when debugging the script.
	// String (default branchProperties supportedCentOsToTest + supportedUnifiedOsToTest) oses2Test - List of OS to check
	// String (mandatory): scyllaExpectedRelease - The version we expect, such as 2020.1.0 or 4.2.1
	// String (mandatory): scyllaExpectedVersionId - Scylla SHA ID, such as 0.20200815.8cc2a9cc98
	// String (mandatory): scyllaRepoOrListUrl - download url of scylla.list for deb/ubuntu or scylla.repo for CentOS.
	//                     test-get-scylladb needs a version such as nightly-2021.1 or nightly-master or latest-official keyword
	// String (default: release): scyllaExpectedMode - Expected installation mode (release|debug|dev|sanitize)
	// String (default: null): logFileWord - a word to add to docker log file name
	// String (default: branchProperties.productName) productName For test repo

	general.traceFunctionParams ("install.parallelTestScyllaVersion", args)
	general.errorMissingMandatoryParam ("install.parallelTestScyllaVersion",
		[scyllaExpectedRelease: "$args.scyllaExpectedRelease",
		 scyllaExpectedVersionId: "$args.scyllaExpectedVersionId",
		 scyllaRepoOrListUrl: "$args.scyllaRepoOrListUrl"]
	)

	boolean dryRun = args.dryRun ?: false
	boolean justPrintErrors = args.justPrintErrors ?: false
	boolean testGetScylladb = args.testGetScylladb ?: false
	String scyllaExpectedMode = args.scyllaExpectedMode ?: "release"
	String scyllaWebInstallUrl = args.scyllaWebInstallUrl ?: generalProperties.scyllaWebInstallUrl
	String scyllaExpectedRelease = args.scyllaExpectedRelease
	String scyllaExpectedVersionId = args.scyllaExpectedVersionId
	String scyllaRepoOrListUrl = args.scyllaRepoOrListUrl
	String oses2Test = args.oses2Test ?: "$branchProperties.supportedCentOsToTest,$branchProperties.supportedUnifiedOsToTest"
	String productName = args.productName ?: branchProperties.productName
	String logFileWord = args.logFileWord ?: ""
	String errorText = ""
	String modeDescription = "mode: $scyllaExpectedMode (description: $logFileWord)"
	if (logFileWord) {
		modeDescription = "$modeDescription ($logFileWord)"
		logFileWord = "_${logFileWord}"
	}

	String passedInstallationTestOSes = ""
	String failedInstallationTestOSes = ""

	ArrayList oses2TestList =  oses2Test.split('\\,')
	def parallelCheckPromotedArtifacts = [:]
		oses2TestList.each { os2Test ->
			parallelCheckPromotedArtifacts["${os2Test}"] = { ->
				String dockerLogFile = "${os2Test}_${scyllaExpectedMode}${logFileWord}_docker_build.log"
				try {
					install.installScyllaVersion (
						os2Test: os2Test,
						scyllaExpectedVersionId: scyllaExpectedVersionId,
						scyllaExpectedRelease: scyllaExpectedRelease,
						scyllaExpectedMode: scyllaExpectedMode,
						dockerLogFile: dockerLogFile,
						urlToInstall: scyllaRepoOrListUrl,
						dryRun: params.DRY_RUN,
						productName: productName,
						testGetScylladb: testGetScylladb,
						scyllaWebInstallUrl: scyllaWebInstallUrl,
					)
					echo "Success installation check: $os2Test $modeDescription"
					passedInstallationTestOSes += "$os2Test, "
				} catch (error) {
					echo "Error installation check: $os2Test $modeDescription error: |$error|"
					failedInstallationTestOSes += "$os2Test, "
				}
			}
		};
	parallel parallelCheckPromotedArtifacts
	if (passedInstallationTestOSes && !failedInstallationTestOSes) {
		echo "Installation tests for all distributions on $modeDescription passed: |$passedInstallationTestOSes|"
	} else if (failedInstallationTestOSes && !passedInstallationTestOSes) {
		errorText = "Installation tests for all distributions on $modeDescription failed: |$failedInstallationTestOSes| passed: |$passedInstallationTestOSes|"
	} else {
		errorText = "Installation tests for some distributions on $modeDescription failed: |$failedInstallationTestOSes| and for some passed: |$passedInstallationTestOSes|"
	}

	if (errorText) {
		if (justPrintErrors) {
			echo "Installation Warning: $errorText"
			currentBuild.description = "install tests failed"
		} else {
			error (errorText)
		}
	}
}

def testDockerVersion (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): containerOrganizationImageName - container organization and image name such as scylladb/scylla-nightly:4.6.dev-0.20210811.e5bb88b69
	// String (mandatory): expectedVersionID - Scylla SHA ID, such as 0.20200815.8cc2a9cc98
	// String (mandatory): expectedRelease - The version we expect, such as 2020.1.0 or 4.2.1

	general.traceFunctionParams ("install.testDockerVersion", args)
	boolean dryRun = args.dryRun ?: false
	general.errorMissingMandatoryParam ("install.testDockerVersion",
		[containerOrganizationImageName: "$args.containerOrganizationImageName",
		expectedVersionID: "$args.expectedVersionID",
		expectedRelease: "$args.expectedRelease",
		])

	String imageName = args.imageName
	String expectedVersionID = args.expectedVersionID
	String expectedRelease = args.expectedRelease
	String dpackagerTool = env.DPACKAGER_TOOL
	boolean expectedVersion=false
	String installedVersion = ""
	String containerName = "scylla-check-version"
	String pullCmd = "$dpackagerTool pull $generalProperties.containerRegistry/$containerOrganizationImageName"
	String runCmd = "$dpackagerTool run --name $containerName -d $containerOrganizationImageName"

	general.runOrDryRunSh (dryRun, pullCmd, "Pull $dpackagerTool to test its version")
	if (dryRun) {
		echo "This is a dry run, skipping $dpackagerTool version test"
	} else {
		try {
			general.runOrDryRunSh (dryRun, runCmd, "Run $dpackagerTool to test the Scylla version it contains")
			installedVersion = general.runOrDryRunShOutput (dryRun, "$dpackagerTool exec $containerName scylla --version", "Get Scylla version")
			if (installedVersion.contains(expectedRelease) && installedVersion.contains(expectedVersionID)) {
				expectedVersion = true
			}
			echo "Installed version: |$installedVersion|"
		} catch (error) {
			echo "Could not install $dpackagerTool for testing version"
		} finally {
			general.cleanDockerImage (containerOrganizationImageName)
		}
		if (expectedVersion) {
			echo "Success: Installed version: |$installedVersion|. Contains |$expectedVersionID| and |$expectedRelease| as expected"
		} else {
			error ("Installed version: |$installedVersion|. Does not contain |$expectedVersionID| and |$expectedRelease| as expected")
		}
	}
}

def testAmiVersionTag (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): amiImageId - AMI ID
	// String (default "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}") dpackagerCommand
	// String (default generalProperties.amiDefaultTestRegion) amiRegion - AMI region.
	// String (mandatory): expectedVersionID - Scylla SHA ID, such as 0.20200815.8cc2a9cc98
	// String (mandatory): expectedRelease - The version we expect, such as 2020.1.0 or 4.2.1

	general.traceFunctionParams ("test.testAmiVersionTag", args)
	boolean dryRun = args.dryRun ?: false
	String dpackagerCommand = args.dpackagerCommand ?: "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}"
	String amiRegion = args.amiRegion ?: generalProperties.amiDefaultTestRegion

	general.errorMissingMandatoryParam ("test.testAmiVersionTag",
		[amiImageId: "$args.amiImageId",
		expectedVersionID: "$args.expectedVersionID",
		expectedRelease: "$args.expectedRelease",
		])

	def tagInfo = ""
	if (dryRun) {
		echo "This is a dry run, skipping AMI version test"
	} else {
		dpackagerEc2Command = general.setAwsDpackagerCommand (dpackagerCommand)
		tagInfo = general.runOrDryRunShOutput (dryRun, "$dpackagerEc2Command -- aws ec2 --region $amiRegion describe-tags --filters \"Name=resource-id,Values=$args.amiImageId\" | grep ${args.expectedRelease} | uniq | cut -d: -f2", "Get version tag info for AMI")
		if (tagInfo.contains(args.expectedRelease) && tagInfo.contains(args.expectedVersionID)) {
			echo "Success: AMI tag version: |$tagInfo|. Contains |$args.expectedVersionID| and |$args.expectedRelease| as expected"
		} else {
			error ("AMI tag version: |$tagInfo|. Does not contain |$args.expectedVersionID| and |$args.expectedRelease| as expected")
		}
	}
}

def testGceVersionLabel (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (mandatory): gceImageDbUrl - GCE Image URL
	// String (default "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}") dpackagerCommand
	// String (mandatory): expectedVersionID - Scylla SHA ID, such as 0.20200815.8cc2a9cc98
	// String (mandatory): expectedRelease - The version we expect, such as 2020.1.0 or 4.2.1

	general.traceFunctionParams ("test.testGceVersionLabel", args)
	boolean dryRun = args.dryRun ?: false
	String dpackagerCommand = args.dpackagerCommand ?: "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}"

	general.errorMissingMandatoryParam ("test.testGceVersionLabel",
		[gceImageDbUrl: "$args.gceImageDbUrl",
		expectedVersionID: "$args.expectedVersionID",
		expectedRelease: "$args.expectedRelease",
		])
	expectedVersionID = args.expectedVersionID
	expectedVersionID = expectedVersionID.replaceAll('\\.','-')
	expectedRelease = args.expectedRelease
	expectedRelease = expectedRelease.replaceAll('\\.','-')

	def labelInfo = ""
	if (dryRun) {
		echo "This is a dry run, skipping GCE version test"
	} else {
		withCredentials([file(credentialsId: generalProperties.jenkinsGoogleApplicationKey, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
			dpackagerGceCommand = general.setGceDpackagerCommand (dpackagerCommand)
			labelInfo = general.runOrDryRunShOutput (dryRun, "$dpackagerGceCommand -- gcloud compute images describe $args.gceImageDbUrl --format=\"csv[no-heading](labels.scylla_version)\"", "Get version label info for GCE")
			if (labelInfo.contains(expectedRelease) && labelInfo.contains(expectedVersionID)) {
				echo "Success: GCE label version: |$labelInfo|. Contains |$expectedVersionID| and |$expectedRelease| as expected"
			} else {
				error ("GCE label version: |$labelInfo|. Does not contain |$expectedVersionID| and |$expectedRelease| as expected")
			}
		}
	}
}

return this
