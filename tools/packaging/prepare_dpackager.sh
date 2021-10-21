#!/bin/bash -e

bv=$(buildah --version)
if (( $? != 0 )); then
    echo install buildah 1.19.3 or later
    exit 1
fi

build_image() {
  dockerfile=$1
  image=$2
  for arch in "${archs[@]}"; do
      image_id_file="$(mktemp)"
      buildah bud --arch="$arch" --no-cache --pull -f tools/packaging/$dockerfile --iidfile "$image_id_file"
      buildah manifest add --all "$(<tools/packaging/$image)" "$(<$image_id_file)"
      rm "$image_id_file"
  done
}

test_image() {
  image=$1
  echo "Done building $(<tools/packaging/$image). You can now test it, and push with"
  echo ""
  echo "    podman manifest push --all $(<tools/packaging/$image) docker://$(<tools/packaging/$image)"
}

# translate to array of version components
bv="${bv#buildah version }"
bv="${bv% (*}"
bv=(${bv//./ })

maj=${bv[0]}
min=${bv[1]}
patch=${bv[2]}

BUILD_UBUNTU=true
BUILD_FEDORA=true
while [ $# -gt 0 ]; do
    case "$1" in
        "--ubuntu")
            BUILD_FEDORA=false
            shift 1
            ;;
        "--fedora")
            BUILD_UBUNTU=false
            shift 1
            ;;
         *)
            print_usage
            ;;
    esac
done

ok=$(( maj > 1 || ( maj == 1 && min > 19 ) || ( maj == 1 && min == 19 && patch >= 3 ) ))

if (( ! ok )); then
    echo install buildah 1.19.3 or later
    exit 1
fi

archs=(amd64 arm64)

if [[ ! -f  /proc/sys/fs/binfmt_misc/qemu-aarch64 || ! -f /proc/sys/fs/binfmt_misc/qemu-s390x ]]; then
    echo install qemu-user-static
    exit 1
fi

TAG=`date +%Y%m%d.%H%M`

if [ $BUILD_FEDORA == "true" ]; then
  image="image_fedora-33"
  echo "docker.io/scylladb/scylla-packaging:fedora-33-$TAG" > tools/packaging/$image
  buildah manifest create "$(<tools/packaging/$image)"
  build_image Dockerfile $image
fi
if [ $BUILD_UBUNTU == "true" ]; then
  image="image_ubuntu20.04"
  echo "docker.io/scylladb/scylla-packaging:ubuntu20.04-$TAG" > tools/packaging/$image
  buildah manifest create "$(<tools/packaging/$image)"
  build_image Dockerfile_deb $image
fi

test_image "image_fedora-33"
test_image "image_ubuntu20.04"
