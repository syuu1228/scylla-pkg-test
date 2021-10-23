#!/bin/bash
set -e
set -x
echo "Create Unified deb temp repo script: $0"
echo "============== env: =============="
env
echo "=================================="
echo "Parameters via env (should not be empty):"
echo "artifacts_prefix: |$artifacts_prefix|. Options: scylla|scylla-enterprise|scylla-manager"
echo "s3_repo_path_prefix: |$s3_repo_path_prefix| Prefix of Address on cloud such as downloads.scylladb.com/unstable/scylla/<branch>/deb/unified"
echo "scylla_pkg_repo: |$scylla_pkg_repo| such as scylladb-master"
echo "list_file: |$list_file| such as scylla.list or scylla-debug.list"
echo "build_arm: |$build_arm| default: false or null or not set - so no arm will be built"

function error_if_not_found {
	item=$1
  find . -name "$item" | grep . || { echo "could not find any candidate for $item" ; DEB_NOT_FOUND=true; }
}

sign_command=""

if [ -f /usr/bin/dpkg-sig ]; then
   sign_command="dpkg-sig -k $SCYLLA_GPG_KEYID --sign builder"
fi

echo "sign_command $sign_command"

rm -rf ~/.gnupg
mkdir -p ~/.gnupg
chmod 777 ~/.gnupg/
cat << EOS > ~/.gnupg/gpg.conf
personal-digest-preferences SHA512
cert-digest-algo SHA512
default-preference-list SHA512 SHA384 SHA256 SHA224 AES256 AES192 AES CAST5 ZLIB BZIP2 ZIP Uncompressed
EOS


gpg --import $SCYLLA_GPG_PUBLIC_KEY
gpg --import --allow-secret-key-import $SCYLLA_GPG_PRIVATE_KEY

DEB_NOT_FOUND=false

x86_arch="amd64"
arm_arch="arm64"
all_archs="all"
architectures=$x86_arch
if [ "x$build_arm" != "x" ]; then
	architectures="$x86_arch,$arm_arch"
fi

rm -Rf debs
mkdir debs

if [[ $artifacts_prefix == *"manager"* ]]; then
	architecture_specific_deb_names="server,client,agent"
else
	architecture_specific_deb_names="conf,kernel-conf,node-exporter,python3,server-dbg,server"
	all_architectures_deb_names="jmx,machine-image,tools,tools-core"
	# all archs deb packages
	for n in ${all_architectures_deb_names//,/ }
	do
		echo "deb: |$n|"
		error_if_not_found "${artifacts_prefix}-${n}_*_$all_archs.deb"
		find . -name "${artifacts_prefix}-${n}_*_$all_archs.deb" -exec cp '{}' debs ';' -print
	done
fi

# architecture specific
for a in ${architectures//,/ }
do
	echo "arch: |$a|"
	if [[ $artifacts_prefix != *"manager"* ]]; then
		error_if_not_found "${artifacts_prefix}_*_$a.deb"
	fi
	find . -name "${artifacts_prefix}_*_$a.deb" -exec cp '{}' debs ';' -print
	for n in ${architecture_specific_deb_names//,/ }
	do
		echo "deb: |$n|"
		error_if_not_found "${artifacts_prefix}-${n}_*_$a.deb"
		find . -name "${artifacts_prefix}-${n}_*_$a.deb" -exec cp '{}' debs ';' -print
	done
done

echo "===== debs dir after all copies ====="
ls debs
echo "============="
if $DEB_NOT_FOUND ; then
  echo "Some deb items were not found. Exiting."
  exit 1
fi

cd debs
for i in scylla*.deb; do
    echo "sign: $sign_command $i"
    $sign_command $i
done
cd ..

rm -Rf ~/.aptly

echo "aptly version:"
aptly version
echo "Running aptly repo create"
aptly repo create -distribution=stable -component=main scylladb-n
echo "Running aptly repo add"
aptly repo add scylladb-n debs/*.deb
echo "Running aptly snapshot create"
aptly snapshot create scylladb-n from repo scylladb-n
echo "Running aptly publish snapshot"
aptly publish snapshot -gpg-key=$SCYLLA_GPG_KEYID scylladb-n scylladb

rm -Rf tmp_repo
mkdir tmp_repo
cp -av ~/.aptly/public/scylladb/* tmp_repo

if [ "x$build_arm" != "x" ]; then
	echo "deb [arch=$x86_arch,$arm_arch] http://${s3_repo_path_prefix}${scylla_pkg_repo} stable main" >> tmp_repo/$list_file
else
	echo "deb [arch=$x86_arch] http://${s3_repo_path_prefix}${scylla_pkg_repo} stable main" >> tmp_repo/$list_file
fi

echo "$0 ended successfully"
