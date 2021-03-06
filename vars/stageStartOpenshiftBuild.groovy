def call(def context, def buildArgs = [:], def imageLabels = [:]) {
  stage('Build Openshift Image') {
    if (!context.environment) {
      println("Skipping for empty environment ...")
      return
    }

    timeout(context.openshiftBuildTimeout) {
      patchBuildConfig(context, buildArgs, imageLabels)
      sh "oc start-build ${context.componentId} --from-dir docker --follow -n ${context.targetProject}"
    }
  }
}

private void patchBuildConfig(def context, def buildArgs, def imageLabels) {
  // sanitize commit message
  def sanitizedGitCommitMessage = context.gitCommitMessage.replaceAll("[\r\n]+", " ").trim().replaceAll("[\"']+", "")
  
  // create the default ODS driven labels
  def odsImageLabels = []
	odsImageLabels.push("{\"name\":\"ods.build.source.repo.url\",\"value\":\"${context.gitUrl}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.source.repo.commit.sha\",\"value\":\"${context.gitCommit}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.source.repo.commit.msg\",\"value\":\"${sanitizedGitCommitMessage}\"}")
    odsImageLabels.push("{\"name\":\"ods.build.source.repo.commit.author\",\"value\":\"${context.gitCommitAuthor}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.source.repo.commit.timestamp\",\"value\":\"${context.gitCommitTime}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.source.repo.branch\",\"value\":\"${context.gitBranch}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.jenkins.job.url\",\"value\":\"${context.buildUrl}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.timestamp\",\"value\":\"${context.buildTime}\"}")
	odsImageLabels.push("{\"name\":\"ods.build.lib.version\",\"value\":\"${context.odsSharedLibVersion}\"}")
	
  // add additional ones - prefixed with ext.
  for (def key : imageLabels.keySet()) {
    def val = imageLabels[key]
    odsImageLabels.push("{\"name\": \"ext.${key}\", \"value\": \"${val}\"}")
  }
  
  // write the file - so people can pick it up in the Dockerfile
  writeFile file: 'docker/release.json', text: "[" + odsImageLabels.join(",") + "]"
  
  def patches = [
      '{"op": "replace", "path": "/spec/source", "value": {"type":"Binary"}}',
      """{"op": "replace", "path": "/spec/output/to/name", "value": "${context.componentId}:${context.tagversion}"}""",
      """{"op": "replace", "path": "/spec/output/imageLabels", "value": [
        ${odsImageLabels.join(",")}
      ]}"""
  ]

  // add build args
  def buildArgsItems = []
  for (def key : buildArgs.keySet()) {
    def val = buildArgs[key]
    buildArgsItems.push("{\"name\": \"${key}\", \"value\": \"${val}\"}")
  }
  if (buildArgsItems.size() > 0) {
    def buildArgsPatch = """{"op": "replace", "path": "/spec/strategy/dockerStrategy", "value": {"buildArgs": [
      ${buildArgsItems.join(",")}
    ]}}"""
    patches.push(buildArgsPatch)
  }

  sh """oc patch bc ${context.componentId} --type=json --patch '[${patches.join(",")}]' -n ${context.targetProject}"""
}

return this
