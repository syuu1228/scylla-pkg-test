// This script will delete all build history within Jenkins folder based on folderName value.
// In order to run this script you need to open Jenkins script console (https://jenkins.scylladb.com/script)
// Before running the script user need to change folderName value to relevant folder (just replace PUT_FOLDER_NAME_HERE and keep the '"')
// By default the script will only print the name of the jobs,
// only after user verified the content of the folder, user need to uncomment lines 38-40 and run it again.

import com.cloudbees.hudson.plugins.folder.Folder

folderName = "PUT_FOLDER_NAME_HERE"

def allJobs= hudson.model.Hudson.getInstance().getItems()

for(int i=0; i<allJobs.size(); i++){
  def job = allJobs[i]
  if(job instanceof Folder){
    processFolderByName(job)
  }
}

void processFolderByName(Item folder){
  if(folder.getFullName().contains(folderName))
    processFolder(folder)
}

void processFolder(Item folder){
  folder.getItems().each{
    if(it instanceof com.cloudbees.hudson.plugins.folder.AbstractFolder){
      processFolder(it)
    } else {
      processJob(it)
    }
  }
}

void processJob(Item job){
  //TODO - Find a way to get user input from Jenkins console
  println  job.getFullName()
  // job.getBuilds().each { it.delete() }
  // job.nextBuildNumber = 1
  // job.save()
}
