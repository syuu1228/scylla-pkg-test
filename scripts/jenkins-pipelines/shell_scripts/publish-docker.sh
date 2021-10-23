#!/bin/bash
set -euo pipefail

DIR=$(dirname $(readlink -f $0))
source $DIR/sh-utils.sh

PROGRAM=$(basename $0)
DRY_RUN=false
PROMOTE_RELEASE=false
PROMOTE_UNSTABLE=false
PROMOTE_STABLE=false
CONTAINER_REGISTRY="docker.io"
CONTAINER_REGISTRY_ORGANIZATION="$CONTAINER_REGISTRY/scylladb"
STABLE_VERSION_ID=""

if which docker >/dev/null 2>&1 ; then
  container_tool=${DPACKAGER_TOOL-docker}
elif which podman >/dev/null 2>&1 ; then
  container_tool=${DPACKAGER_TOOL-podman}
else
  die "Please make sure you install either podman or docker on this machine to run this script"
fi

if echo $container_tool | grep podman >/dev/null ; then
  podmanArgs="--format docker"
else
  echo "Starting docker service only if docker-ce is installed and being used"
  run_cmd sudo systemctl start docker
  podmanArgs=""
fi

function usage {
  echo "Usage: $PROGRAM --release_name=<nn.nn> [--stable_version_id=<nn.nn.cccc>] --container-tag-name=<version and sha> --origin_container_repo=<name> --target_container_repo=<name> --scylla_repo_url=<url> [--promote_latest] [--promote_unstable] [--promote_stable] [--dry_run] [--create_manifest]"
  echo ""
  echo "    --release_name=<nn.nn>      e.g. 3.0 or 2018.1"
  echo "    --stable_version_id=<nn.nn.cccc> Needed on release promotion only. e.g. 1.2.3 or 2.3-rc1 or 2018.1.12"
  echo "    --container-tag-name=<version and sha> e.g. 666.development-0.20200513.c06cdcdb3 or 4.1.rc0-0.20200511.0760107b9fa. On promotion this is both the source and target. On daily it is the target"
  echo "    --origin_container_repo=<name> e.g. scylla or scylla-enterprise or scylla-nightly"
  echo "    --target_container_repo=<name> e.g. scylla or scylla-enterprise or scylla-nightly"
  echo "    --promote_latest 	          Whether to promote this docker to the latest path"
  echo "    --promote_unstable          Whether to update latest unstable release to the latest path"
  echo "    --promote_stable            Whether to update latest stable release to the latest path"
  echo "    --dry_run                   Print commands instead of running them"
  echo "    --scylla_repo_url           Specified Scylla repo URL"
}

function promote {
  run_cmd $container_tool login $CONTAINER_REGISTRY -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
  run_cmd $container_tool pull $CONTAINER_REGISTRY_ORGANIZATION/$ORIGIN_CONTAINER_REPO:$CONTAINER_TAG_NAME
  if $PROMOTE_UNSTABLE ; then
    echo "Promote to latest unstable:"
    run_cmd $container_tool tag $CONTAINER_REGISTRY_ORGANIZATION/$ORIGIN_CONTAINER_REPO:$CONTAINER_TAG_NAME $CONTAINER_REGISTRY_ORGANIZATION/$TARGET_CONTAINER_REPO:$RELEASE_NAME
    run_cmd $container_tool push $CONTAINER_REGISTRY_ORGANIZATION/$TARGET_CONTAINER_REPO:$RELEASE_NAME
  else
    if $PROMOTE_STABLE ; then
      echo "Promote to latest stable:"
      export STABLE_VERSION_ID
      run_cmd $container_tool tag $CONTAINER_REGISTRY_ORGANIZATION/$ORIGIN_CONTAINER_REPO:$CONTAINER_TAG_NAME $CONTAINER_REGISTRY_ORGANIZATION/$TARGET_CONTAINER_REPO:$STABLE_VERSION_ID
      run_cmd $container_tool push $CONTAINER_REGISTRY_ORGANIZATION/$TARGET_CONTAINER_REPO:$STABLE_VERSION_ID
    fi
	  if $PROMOTE_RELEASE ; then
      echo "Promote to latest release:"
      run_cmd $container_tool tag $CONTAINER_REGISTRY_ORGANIZATION/$ORIGIN_CONTAINER_REPO:$CONTAINER_TAG_NAME $CONTAINER_REGISTRY_ORGANIZATION/$TARGET_CONTAINER_REPO:latest
      run_cmd $container_tool push $CONTAINER_REGISTRY_ORGANIZATION/$TARGET_CONTAINER_REPO:latest
    fi
  fi
  run_cmd $container_tool logout $CONTAINER_REGISTRY
}

for i in "$@"
do
case $i in
    --release_name*)
    RELEASE_NAME="${i#*=}"
    shift
    ;;
    --stable_version_id*)
    STABLE_VERSION_ID="${i#*=}"
    shift
    ;;
    --container-tag-name*)
    CONTAINER_TAG_NAME="${i#*=}"
    shift
    ;;
    --origin_container_repo*)
    ORIGIN_CONTAINER_REPO="${i#*=}"
    shift
    ;;
    --target_container_repo*)
    TARGET_CONTAINER_REPO="${i#*=}"
    shift
    ;;
    --promote_latest)
    PROMOTE_RELEASE=true
    shift
    ;;
    --promote_unstable)
    PROMOTE_UNSTABLE=true
    shift
    ;;
    --promote_stable)
    PROMOTE_STABLE=true
    shift
    ;;
    --dry_run)
    DRY_RUN=true
    shift
    ;;
    *)
    echo "Error: unknown command line option: |$i|"
    usage
    exit 1
    ;;
esac
done

echo "$PROGRAM got these Parameters:"
echo "   --release_name          = \"$RELEASE_NAME\""
echo "   --stable_version_id     = \"$STABLE_VERSION_ID\""
echo "   --container-tag-name    = \"$CONTAINER_TAG_NAME\""
echo "   --origin_container_repo = \"$ORIGIN_CONTAINER_REPO\""
echo "   --target_container_repo = \"$TARGET_CONTAINER_REPO\""
echo "   --promote_latest        = \"$PROMOTE_RELEASE\""
echo "   --promote_unstable      = \"$PROMOTE_UNSTABLE\""
echo "   --promote_stable        = \"$PROMOTE_STABLE\""
echo "   --dry_run               = \"$DRY_RUN\""
echo "=================="

fail_if_param_missing "$RELEASE_NAME" '--release_name'
fail_if_param_missing "$CONTAINER_TAG_NAME" '--container-tag-name'
fail_if_param_missing "$ORIGIN_CONTAINER_REPO" '--origin_container_repo'
fail_if_param_missing "$TARGET_CONTAINER_REPO" '--target_container_repo'

if $PROMOTE_STABLE ; then
  fail_if_param_missing "$STABLE_VERSION_ID" '--stable_version_id'
fi

promote

echo "$PROGRAM done."

exit 0
