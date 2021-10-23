#!/bin/bash
set -e
PROGRAM=$(basename $0)
DRY_RUN=false

function usage {
  echo "Usage: $PROGRAM --version=<version> --platform=<platform> --product=<scylla|scylla-enterprise> --target=<URL> --base_url=<URL> [--dist_names=dist1,dist2] [--dry_run]"
  echo ""
  echo "    --version=<version number. Example: 3.0>"
  echo "    --platform=<unix version>. Example: ubuntu,centos,debian"
  echo "    --product=<scylla|scylla-enterprise>"
  echo "    --target=URL to put the repo/list file"
  echo "    --base_url for repo files"
  echo "    --dist_names optional comma separated list of dist names such as focal,bionic,xenial or stretch,buster"
  echo "    --dry_run True to skip doing things - just print"
  exit 1
}

DIR=$(dirname $(readlink -f $0))
source $DIR/sh-utils.sh

for i in "$@"
do
case $i in
    --version*)
    version="${i#*=}"
    shift
    ;;
    --platform*)
    platform="${i#*=}"
    shift
    ;;
    --product*)
    product="${i#*=}"
    shift
    ;;
    --target*)
    target="${i#*=}"
    shift
    ;;
    --base_url*)
    base_url="${i#*=}"
    shift
    ;;
    --dist_names*)
    dist_names="${i#*=}"
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

if [[ $platform =~ "centos" ]]; then
  archTool="rpm"
  repoFile=".repo.in"
  repoSuffix=".repo"
else
  archTool="deb"
  repoFile=".list.in"
  repoSuffix=".list"
fi

echo "$PROGRAM got these Parameters:"
echo "   --version   = \"$version\""
echo "   --platform  = \"$platform\""
echo "   --product   = \"$product\""
echo "   --target    = \"$target\""
echo "   --base_url  = \"$base_url\""
echo "   --dist_names= \"$dist_names\""
echo "   --dry_run   = \"$DRY_RUN\""
echo "=================="

fail_if_param_missing "$version" '--version'
fail_if_param_missing "$platform" '--platform'
fail_if_param_missing "$product" '--product'
fail_if_param_missing "$target" '--target'
fail_if_param_missing "$base_url" '--base_url'

sed_out_repo_file="scylla-$version$repoSuffix"
target="s3://$target"
centos_empty_repo_target="s3://$base_url/downloads/$product/rpm/$platform/scylladb-$version"
sed_in_repo_file="dist/$platform/scylla$repoFile"

echo "sed_out_repo_file: |$sed_out_repo_file|"
echo "target: |$target|"
echo "sed_in_repo_file: |$sed_in_repo_file|"


echo "Generating $platform repo file $sed_out_repo_file on cloud repo $target"

sed -e "s~@@VERSION@@~$version~" -e "s~@@PRODUCT@@~$product~" -e "s~@@BASE_URL@@~$base_url~" $sed_in_repo_file > $sed_out_repo_file

if $DRY_RUN ; then
  S3_CP_CMD="aws s3 cp --dryrun"
else
  S3_CP_CMD="aws s3 cp --no-progress"
fi

$S3_CP_CMD $sed_out_repo_file $target

# For backword compatibility let's leave also the dist specific files
for i in ${dist_names//,/ }
do
  dist_file_name="scylla-$version-$i$repoSuffix"
  cp $sed_out_repo_file $dist_file_name
  $S3_CP_CMD $dist_file_name $target
done

if [[ $platform =~ "centos" ]]; then
  echo "Create empty centos repo's (needed for AMI building)"
  empty_repo="build/empty-repo"
  mkdir -p $empty_repo
  createrepo $empty_repo

  $S3_CP_CMD --recursive $empty_repo $centos_empty_repo_target/x86_64/
  $S3_CP_CMD --recursive $empty_repo $centos_empty_repo_target/noarch/
fi
