def initPipeline (boolean useManager = false) {
	if (useManager) {
		branchProperties = readProperties file: 'scripts/jenkins-pipelines/manager/branch-specific.properties'
	} else {
		branchProperties = readProperties file: 'scripts/jenkins-pipelines/branch-specific.properties'
	}
	artifact = load "${generalProperties.groovyPath}/artifact.groovy"
	build = load "${generalProperties.groovyPath}/build.groovy"
	git = load "${generalProperties.groovyPath}/git.groovy"
	install = load "${generalProperties.groovyPath}/install.groovy"
	jenkins = load "${generalProperties.groovyPath}/jenkins.groovy"
	mail = load "${generalProperties.groovyPath}/mail.groovy"
	test = load "${generalProperties.groovyPath}/test.groovy"
	calledBuildsDir = jenkins.prodOrDebugFolders()
	git.createGitProperties()
	gitProperties = readProperties file: generalProperties.gitPropertiesFileName
	echo "========= Git properties file: ==============="
	sh "cat ${generalProperties.gitPropertiesFileName}"
	echo "=============================================="
	boolean preserveWs = params.PRESERVE_WORKSPACE ?: false
	git.cleanWorkSpaceUponRequest(preserveWs)
	disableSubmodules = true // for pkg checkout. Does not work without it
	artifactSourceJob = params.ARTIFACT_SOURCE_JOB_NAME ?: "${branchProperties.calledBuildsDir}${branchProperties.relocUploaderJobName}"

	(jobTitle,logText,runningUserID,devAddress,relengAddress,qaAddress)=mail.setMailParameters(
		dryRun: params.DRY_RUN,
		debugMail: params.DEBUG_MAIL,
		branch: branchProperties.stableBranchName,
	)
	relengTestingRun = (params.DRY_RUN || JOB_NAME.contains(generalProperties.relengJobPath))
	jenkins.checkAndTagAwsInstance(runningUserID)
	lastStage = env.STAGE_NAME
	initPipelineDone = true
	armSupported = branchProperties.armSupported.toBoolean()
	env.DPACKAGER_TOOL=generalProperties.defaultContainerTool

	relengBranch = params.RELENG_BRANCH ?: branchProperties.stableBranchName
	relengRepo = params.RELENG_REPO ?: gitProperties.scyllaPkgRepoUrl
	echo "Running build $JOB_NAME # ${BUILD_NUMBER}. Workspace is ${pwd()}"
}

def errorIfMissingParam (String paramValue, String paramName) {
	if (! paramValue == null){
		currentBuild.result = 'FAILURE'
		error("Error: Missing mandatory parameter $paramName")
	}
}

def errorIfIllegalChoice (String valueParam, String legalValues, String description) {
	ArrayList values = valueParam.split('\\,')
	values.each { value ->
		echo "Checking if value |$value| is in legal values |$legalValues|"
		if (legalValues.contains("${value}")) {
			echo "Value |$value| is legal for $description"
		} else {
			error("Error: $description |$value| should be one of |$legalValues|")
		}
	}
}

boolean versionFormatOK(String versionID) {
    return versionID ==~ /\d+\.\d+\.\d+(\.[a-z0-9]*|)/
}

def runOrDryRunSh (boolean dryRun = false, String cmd, String description) {
	echo "$description"
	if (dryRun) {
		echo "Dry-run: |$cmd|"
	} else {
		echo "Running sh cmd: |$cmd|"
		sh "$cmd"
	}
}

def runOrDryRunShOutput (boolean dryRun = false, String cmd, String description) {
	echo "$description"
	def cmdOutput = ""
	if (dryRun) {
		echo "Dry-run: |$cmd|"
		cmdOutput = "Dry-run"
	} else {
		echo "Running sh cmd: |$cmd|"
		cmdOutput = sh(script: "$cmd", returnStdout:true).trim()
	}
	echo "Command output: |$cmdOutput|"
	return cmdOutput
}

def runS3Cp (Map args) {
	// Run AWS S3 cp command
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): recursive - recursive flag
	// String (mandatory): cpSource - The AWS S3 cp source
	// String (mandatory): cpTarget - The AWS S3 command cp target
	// String (default: none) excludeItems - Comma separated items to exclude
	// String (default: copy source to destination): description - Text describes what you are doing

	general.traceFunctionParams ("general.runS3Cp", args)
	general.errorMissingMandatoryParam ("general.runS3Cp",
		[cpSource: "$args.cpSource",
		 cpTarget: "$args.cpTarget",
		]
	)

	boolean dryRun = args.dryRun ?: false
	boolean recursive = args.recursive ?: false
	String excludeItems = args.excludeItems ?: ""
	String description = args.description ?: "AWS S3 copy of $args.cpSource to $args.cpTarget"

	general.runOrDryRunS3Cmd (
		dryRun: dryRun,
		baseCmd: generalProperties.awsS3CpCommand,
		recursive: recursive,
		excludeItems: excludeItems,
		cmdArtifacts: "$args.cpSource $args.cpTarget",
		description: description
	)
}

def runS3Rm (Map args) {
	// Run AWS S3 cp command
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): recursive - recursive flag
	// String (mandatory): rmItem - The AWS S3 item to remove
	// String (default: none) excludeItems - Comma separated items to exclude
	// String (default: remove item from S3): description - Text describes what you are doing

	general.traceFunctionParams ("general.runS3Rm", args)
	general.errorMissingMandatoryParam ("general.runS3Rm",
		[cpSource: "$args.rmItem"]
	)

	boolean dryRun = args.dryRun ?: false
	boolean recursive = args.recursive ?: false
	String excludeItems = args.excludeItems ?: ""
	String description = args.description ?: "AWS S3 copy of $args.cpSource to $args.cpTarget"

	general.runOrDryRunS3Cmd (
		dryRun: dryRun,
		baseCmd: generalProperties.awsS3RmCommand,
		recursive: recursive,
		excludeItems: excludeItems,
		cmdArtifacts: args.rmItem,
		description: description,
	)
}

def runOrDryRunS3Cmd (Map args) {
	// Run an AWS S3 command
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): recursive - recursive flag
	// String (default: none) excludeItems - Comma separated items to exclude
	// String (mandatory): baseCmd - The AWS S3 command such as rm, cp
	// String (Mandatory) cmdArtifacts - The AWS S3 command artifacts, such as what to copy / rm and other arguments
	// String (Mandatory): description - Text describes what you are doing

	general.traceFunctionParams ("general.runOrDryRunS3Cmd", args)
	general.errorMissingMandatoryParam ("general.runOrDryRunS3Cmd",
		[baseCmd: "$args.baseCmd",
		 cmdArtifacts: "$args.cmdArtifacts",
		 description: "$args.description"]
	)

	boolean dryRun = args.dryRun ?: false
	boolean recursive = args.recursive ?: false
	String excludeItems = args.excludeItems ?: ""

	String recursiveFlag = ""
	if (recursive) {
		recursiveFlag = "--recursive"
	}

	String excludeParam = ""
	if (excludeItems) {
		ArrayList items = excludeItems.split('\\,')
		items.each { item ->
			excludeParam += " --exclude $item"
		}
	}

	String cmd = args.baseCmd
	if (dryRun) {
		cmd += " --dryrun"
	}
	cmd += " $args.cmdArtifacts $recursiveFlag $excludeParam"
    echo "S3cmd: ${cmd}"
	if (toolInstalled (generalProperties.defaultContainerTool)) {
		env.DPACKAGER_TOOL=generalProperties.defaultContainerTool
		dpackagerAwsCommand = general.setAwsDpackagerCommand ("", "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}")
		echo "Running sh cmd: |$cmd| using dpackager"
		sh "$dpackagerAwsCommand -- $cmd"
	} else {
		echo "Running local sh cmd: |$cmd|"
		withCredentials([string(credentialsId: 'jenkins2-aws-secret-key-id', variable: 'AWS_ACCESS_KEY_ID'),
		string(credentialsId: 'jenkins2-aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')]) {
			sh "$cmd"
		}
	}
}

def lsPath (String path = WORKSPACE, String header = "content") {
	// Show a path/dir content. Usualy to see which artifacts were downloaded (names, size, permissions).
	echo "============= Dir $path $header ==============="
	try {
		sh "ls -la $path"
	} catch (error) {
		echo "path |$path| does not exist"
	}
	echo "===================================================================================="
}

boolean filesAreEqual(String file1, String description1, String file2, String description2) {
	boolean filesEqual = true
	if (!fileExists("$file2")) {
		sh "touch $file2"
	}
	echo "First file |$file1| - $description1 is: ============="
	sh "cat $file1"
	echo "Second file |$file2| - $description2 is: ============="
	sh "cat $file2"
	echo "==========================="
	try {
		sh "cmp $file1 $file2" // status is 1 if files are not equal
	} catch (err) {
		filesEqual = false
		echo "Files are not equal"
	}
	echo "filesEqual: |$filesEqual|"
	return filesEqual
}

def raiseErrorOnFailureStatus (boolean status, String description) {
	if (status) {
		echo "Error: $description"
		if (currentBuild.currentResult != "ABORTED") {
			currentBuild.result = 'FAILURE'
			error("$description")
		}
	}
}

def buildModesList (String buildModes) {
	// When all modes are wanted, we send empty string to ninja and tests. But for publish artifacts we need the actual list.
	echo "Creating buildModesList from buildModes: |$buildModes|"

	if (buildModes) {
		return [buildModes]
	} else {
		return ["release", "debug" ,"dev"]
	}
}

def cleanDockerImage (String dockerImage, boolean dryRun = false){
	// Stop and delete containers and images of a given dockerImage
	String dpackagerTool = generalProperties.defaultContainerTool
	String getContainersCmd = "$dpackagerTool ps -a | grep $dockerImage | wc -l"
	def numOfRunningContainers = general.runOrDryRunShOutput (dryRun, getContainersCmd, "Get running dockers upon ID")
	if ("${numOfRunningContainers}" == "0"){
		echo "There are no running $dockerImage containers"
	} else {
		String stopContainersCmd = "$dpackagerTool stop \$($dpackagerTool ps --all | grep $dockerImage | awk '{print \$1}')"
		general.runOrDryRunSh (dryRun, stopContainersCmd, "Stop docker")
		String removeContainersCmd = "$dpackagerTool rm \$($dpackagerTool ps --all | grep $dockerImage | awk '{print \$1}')"
		general.runOrDryRunSh (dryRun, removeContainersCmd, "remove docker xontainers (rm)")
	}
	String getImagesIdCmd = "$dpackagerTool images $dockerImage -q | uniq"
	def dockerImageIds = general.runOrDryRunShOutput (dryRun, getImagesIdCmd, "Get images to clean upon ID")
	if (!dockerImageIds.isEmpty()) {
	    String removeImageCmd = "$dpackagerTool rmi ${dockerImage} --force"
	    general.runOrDryRunSh (dryRun, removeImageCmd, "remove docker images (rmi)")
	}
}

def dockerCredentials(boolean debug=false) {
	String docker_username = "${generalProperties.jenkinsDockerUserID}"
	String docker_password = "${generalProperties.jenkinsDockerPasswordID}"
	if (debug) {
		docker_username = "${generalProperties.debugDockerUserID}"
		docker_password = "${generalProperties.debugDockerPasswordID}"
	}
	return [ docker_username, docker_password ]
}

String addTrailingSlashIfMissing(String url) {
	if(url.charAt(url.length()-1)!="/"){
    url += "/"
	}
	return url
}

boolean toolInstalled (String toolCommand){
	installed = true
	try {
		sh "which $toolCommand"
	} catch (error) {
		echo "Could not find the tool $toolCommand Error: $error"
		installed = false
	}
	return installed
}

String addS3PrefixIfMissing(String url) {
	if (! url.contains("s3://")) {
		url = "s3://$url"
	}
	return url
}

String addHttpPrefixIfMissing(String url) {
	if (! url.contains("http://")) {
		url = "http://$url"
	}
	return url
}

String addHttpsS3PrefixIfMissing(String url) {
	if (! url.contains("https://s3.amazonaws.com/")) {
		url = "https://s3.amazonaws.com/$url"
	}
	return url
}

String setTestingUrlIfNeeded(String url) {
	if (jenkins.debugBuild()) {
		if (! url.contains("/testing/")) {
			url = url.replaceAll(generalProperties.baseDownloadsUrl, generalProperties.debugBaseDownloadsUrl)
			echo "Testing URL: $url"
		}
	}
	return url
}

String removeTrailingSlash (String url) {
	return url.replaceFirst("/*\$", "")
}

String removeHttpFromUrl (String url) {
	return url.replaceFirst("http://", "")
}

def traceFunctionParams (String functionName, args) {
	echo "$functionName parameters:"
	args.each{ k, v -> println "${k}:|${v}|" }
}

def setMandatoryList (boolean mandatoryFlag, def mandatoryIfTrue, def mandatoryIfFalse) {
	def mandatoryArgs = []
	if (mandatoryFlag) {
		mandatoryArgs = mandatoryIfTrue
	} else {
		mandatoryArgs = mandatoryIfFalse
	}
	return mandatoryArgs
}

def errorMissingMandatoryParam (String functionName, args) {
	String missingArgs = ""
	args.each { name, value ->
		echo "Checking mandatory param for $functionName name: |$name|, value: |$value|"
		if (value == null || value == "") {
			missingArgs += "$name, "
		}

		if (missingArgs) {
			error ("Missing mandatory parameter(s) on $functionName: $missingArgs")
		}
	}
}

String setAwsDpackagerCommand (String dpackagerCommand = "", String pkgCheckoutDir = gitProperties.scyllaPkgCheckoutDir) {
	if (! dpackagerCommand) {
		dpackagerCommand = "$pkgCheckoutDir/${generalProperties.dpackagerPath}"
	}
	dpackagerCommand += ' -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID'
	return dpackagerCommand
}

String setGceDpackagerCommand (String dpackagerCommand = "", String pkgCheckoutDir = gitProperties.scyllaPkgCheckoutDir) {
	if (! dpackagerCommand) {
		dpackagerCommand = "$pkgCheckoutDir/${generalProperties.dpackagerPath}"
	}
	dpackagerCommand += ' -e GOOGLE_APPLICATION_CREDENTIALS=$GOOGLE_APPLICATION_CREDENTIALS -v $GOOGLE_APPLICATION_CREDENTIALS:$GOOGLE_APPLICATION_CREDENTIALS'
	env.DOCKER_IMAGE = "image_ubuntu20.04"
	return dpackagerCommand
}

def setAzureDpackagerCommand () {
	dpackagerCommand = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.dpackagerPath}"
	dpackagerCommand += ' -e AUZRE_TENANT_ID=$AZURE_TENANT_ID'
	dpackagerCommand += ' -e AZURE_CLIENT_SECRET=$AZURE_CLIENT_SECRET'
	dpackagerCommand += ' -e AZURE_CLIENT_ID=$AZURE_CLIENT_ID'
	dpackagerCommand += ' -e AZURE_SUBSCRIPTION_ID=${AZURE_SUBSCRIPTION_ID}'
	return dpackagerCommand
}

String setGpgDpackagerCommand (String dpackagerCommand = "", String pkgCheckoutDir = gitProperties.scyllaPkgCheckoutDir) {
	if (! dpackagerCommand) {
		dpackagerCommand = "$pkgCheckoutDir/${generalProperties.dpackagerPath}"
	}
	dpackagerCommand += ' -e SCYLLA_GPG_KEYID=$SCYLLA_GPG_KEYID'
	dpackagerCommand += ' -v $SCYLLA_GPG_PUBLIC_KEY:$SCYLLA_GPG_PUBLIC_KEY'
	dpackagerCommand += ' -e SCYLLA_GPG_PUBLIC_KEY=$SCYLLA_GPG_PUBLIC_KEY'
	dpackagerCommand += ' -v $SCYLLA_GPG_PRIVATE_KEY:$SCYLLA_GPG_PRIVATE_KEY'
	dpackagerCommand += ' -e SCYLLA_GPG_PRIVATE_KEY=$SCYLLA_GPG_PRIVATE_KEY'
	dpackagerCommand += " -e artifacts_prefix=\"$branchProperties.productName\""
	return dpackagerCommand
}

int parseIntSafe(String s) {
  try {
    return Integer.parseInt(s)
  } catch (NumberFormatException e) {
    return 0
  }
}

boolean enterpriseBuild() {
	if (branchProperties.productName.contains("enterprise")) {
		echo "This is an enterprise build."
		return true
	} else {
		echo "This is not an enterprise build."
		return false
	}
}

def renameIfOldNameExists (String oldPath, String newPath, boolean dryRun = false) {
	echo "renameIfOldNameExists: Going to rename |$oldPath| (if exists) to |$newPath| (if not already exists)"
	boolean oldExists = fileExists "$oldPath"
	boolean newExists = fileExists "$newPath"
	if (dryRun || (oldExists && ! newExists)) {
		general.runOrDryRunSh (dryRun, "mv $oldPath $newPath", "Rename old file to new file")
	} else if (newExists) {
		echo "Nothing to rename. New name file exists"
	} else if (!oldExists) {
		error ("Nothing to rename - old file does not exist")
	}
}

boolean fileExistsOnPath(String file, String path=WORKSPACE) {
	boolean exists = fileExists "$path/$file"
	if (exists) {
		echo "File |$file| exists on path |$path|"
		return true
	} else {
		echo "File |$file| does not exist on path |$path|"
		return false
	}
}

boolean fileExistsOnCloud(String url) {
	url = general.addS3PrefixIfMissing(url)
	try {
		def lsOutput = general.runOrDryRunShOutput (false, "aws s3 ls $url", "Check if $url exists")
		echo "aws s3 ls output: |$lsOutput|"
		if (lsOutput) {
			echo "URL: |$url| exists."
			return true
		} else {
			echo "URL: |$url| does not exist."
			return false
		}
	} catch (error) {
		echo "URL: |$url| does not exist."
		return false
	}
}

def writeTextToLogAndFile (String text, String file) {
	sh "echo \"$text\" | tee --append $file"
}

return this
