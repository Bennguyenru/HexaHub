#!/usr/bin/env bash
# License: MIT
# Copyright 2022 The Defold Foundation

PLATFORM=$1
PWD=$(pwd)
SOURCE_DIR=${PWD}/source
BUILD_DIR=${PWD}/build/${PLATFORM}

if [ -z "$PLATFORM" ]; then
    echo "No platform specified!"
    exit 1
fi


CMAKE_FLAGS="-DCMAKE_BUILD_TYPE=Release ${CMAKE_FLAGS}"
CMAKE_FLAGS="-DSPIRV_CROSS_STATIC=ON ${CMAKE_FLAGS}"
CMAKE_FLAGS="-DSPIRV_CROSS_CLI=ON ${CMAKE_FLAGS}"
CMAKE_FLAGS="-DSPIRV_CROSS_SHARED=OFF ${CMAKE_FLAGS}"

case $PLATFORM in
    arm64-macos)
        CMAKE_FLAGS="-DCMAKE_OSX_ARCHITECTURES=arm64 ${CMAKE_FLAGS}"
        ;;
    x86_64-macos)
        CMAKE_FLAGS="-DCMAKE_OSX_ARCHITECTURES=x86_64 ${CMAKE_FLAGS}"
        ;;
esac

# Follow the build instructions on https://github.com/KhronosGroup/SPIRV-Cross.git

if [ -z "$SOURCE_DIR" ]; then
    git clone https://github.com/KhronosGroup/SPIRV-Cross.git $SOURCE_DIR
fi

# Build

mkdir -p ${BUILD_DIR}

pushd $BUILD_DIR

echo "CMAKE_FLAGS: '${CMAKE_FLAGS}"
cmake ${CMAKE_FLAGS} $SOURCE_DIR
make -j8

mkdir -p ./bin/$PLATFORM
cp -v ./spirv-cross ./bin/$PLATFORM
strip ./bin/$PLATFORM/spirv-cross

popd

# Package

VERSION=$(cd $SOURCE_DIR && git rev-parse --short HEAD)
echo VERSION=${VERSION}

PACKAGE=spirv-cross-${VERSION}-${PLATFORM}.tar.gz

pushd $BUILD_DIR
tar cfvz $PACKAGE bin
popd
