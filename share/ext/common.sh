# config

IOS_TOOLCHAIN_ROOT=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain
ARM_DARWIN_ROOT=/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer
IOS_SDK_VERSION=8.1

ANDROID_ROOT=~/android
ANDROID_NDK_VERSION=10b
ANDROID_VERSION=14
ANDROID_GCC_VERSION='4.8'

FLASCC=~/local/FlasCC1.0/sdk

function download() {
    mkdir -p ../download
    [ ! -f ../download/$FILE_URL ] && curl -O $BASE_URL/$FILE_URL && mv $FILE_URL ../download
}

function cmi_make() {
    set -e
    make -j8
    make install
    set +e
}

function cmi_unpack() {
    tar xfz ../../download/$FILE_URL --strip-components=1
}

function cmi_do() {
    rm -rf $PREFIX
    rm -rf tmp
    mkdir tmp
    mkdir -p $PREFIX
    pushd tmp  >/dev/null
    cmi_unpack
    [ -f ../patch_$VERSION ] && echo "Applying patch ../patch_$VERSION" && patch -p1 < ../patch_$VERSION

    ${CONFIGURE_WRAPPER} ./configure $CONFIGURE_ARGS $2 \
        --disable-shared \
        --prefix=${PREFIX} \
        --bindir=${PREFIX}/bin/$1 \
        --libdir=${PREFIX}/lib/$1 \
        --with-http=off \
        --with-html=off \
        --with-ftp=off \
        --with-x=no

    cmi_make
}

function cmi_cross() {
    if [[ $2 == "js-web" ]]; then
        # Cross compiling protobuf for js-web with --host doesn't work
        # Unknown host in reported by configure script
        # TODO: Use another target, e.g. i386-freebsd as for as3-web?
        cmi_do $1
    else
        cmi_do $1 "--host=$2"
    fi

    local TGZ="$PRODUCT-$VERSION-$1.tar.gz"
    pushd $PREFIX  >/dev/null
    tar cfz $TGZ lib
    popd >/dev/null

    echo "../build/$TGZ created"
    mv $PREFIX/$TGZ ../build

    rm -rf tmp
    #rm -rf $PREFIX
}

function cmi_buildplatform() {
    cmi_do $1 ""

    local TGZ="$PRODUCT-$VERSION-$1.tar.gz"
    local TGZ_COMMON="$PRODUCT-$VERSION-common.tar.gz"
    pushd $PREFIX  >/dev/null
    tar cfz $TGZ lib bin
    tar cfz $TGZ_COMMON include share
    popd >/dev/null
    popd >/dev/null

    mv $PREFIX/$TGZ ../build
    echo "../build/$TGZ created"
    mv $PREFIX/$TGZ_COMMON ../build
    echo "../build/$TGZ_COMMON created"

    rm -rf tmp
    rm -rf $PREFIX
}

function cmi() {
    export PREFIX=`pwd`/build

    case $1 in
        armv7-darwin)
            [ ! -e "$ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk" ] && echo "No SDK found at $ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk" && exit 1
            # NOTE: We set this PATH in order to use libtool from iOS SDK
            # Otherwise we get the following error "malformed object (unknown load command 1)"
            export PATH=$IOS_TOOLCHAIN_ROOT/usr/bin:$PATH
            export CPPFLAGS="-arch armv7 -isysroot $ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk"
            # NOTE: Default libc++ changed from libstdc++ to libc++ on Maverick/iOS7.
            # Force libstdc++ for now
            export CXXFLAGS="${CXXFLAGS} -stdlib=libstdc++ -arch armv7 -isysroot $ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk"
            export CFLAGS="${CPPFLAGS}"
            # NOTE: We use the gcc-compiler as preprocessor. The preprocessor seems to only work with x86-arch.
            # Wrong include-directories and defines are selected.
            export CPP="$IOS_TOOLCHAIN_ROOT/usr/bin/clang -E"
            export CC=$IOS_TOOLCHAIN_ROOT/usr/bin/clang
            export CXX=$IOS_TOOLCHAIN_ROOT/usr/bin/clang++
            export AR=$ARM_DARWIN_ROOT/usr/bin/ar
            export RANLIB=$ARM_DARWIN_ROOT/usr/bin/ranlib
            cmi_cross $1 arm-darwin
            ;;

        arm64-darwin)
            # Essentially the same environment vars as armv7-darwin but with "-arch arm64".

            [ ! -e "$ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk" ] && echo "No SDK found at $ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk" && exit 1
            # NOTE: We set this PATH in order to use libtool from iOS SDK
            # Otherwise we get the following error "malformed object (unknown load command 1)"
            export PATH=$IOS_TOOLCHAIN_ROOT/usr/bin:$PATH
            export CPPFLAGS="-arch arm64 -isysroot $ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk"
            # NOTE: Default libc++ changed from libstdc++ to libc++ on Maverick/iOS7.
            # Force libstdc++ for now
            export CXXFLAGS="${CXXFLAGS} -stdlib=libstdc++ -arch arm64 -isysroot $ARM_DARWIN_ROOT/SDKs/iPhoneOS${IOS_SDK_VERSION}.sdk"
            export CFLAGS="${CPPFLAGS}"
            # NOTE: We use the gcc-compiler as preprocessor. The preprocessor seems to only work with x86-arch.
            # Wrong include-directories and defines are selected.
            export CPP="$IOS_TOOLCHAIN_ROOT/usr/bin/clang -E"
            export CC=$IOS_TOOLCHAIN_ROOT/usr/bin/clang
            export CXX=$IOS_TOOLCHAIN_ROOT/usr/bin/clang++
            export AR=$ARM_DARWIN_ROOT/usr/bin/ar
            export RANLIB=$ARM_DARWIN_ROOT/usr/bin/ranlib
            cmi_cross $1 arm-darwin
            ;;

         armv7-android)
            local platform=`uname | awk '{print tolower($0)}'`
            local bin="${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/toolchains/arm-linux-androideabi-${ANDROID_GCC_VERSION}/prebuilt/${platform}-x86_64/bin"
            local sysroot="--sysroot=${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_VERSION}/arch-arm"
            #  -fstack-protector
#            local stl="${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/stlport/stlport"
            local stl="${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/include"
            local stl_lib="${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a"
            local stl_arch="${stl_lib}/include"

            export CFLAGS="${CFLAGS} ${sysroot} -fpic -ffunction-sections -funwind-tables -D__ARM_ARCH_5__ -D__ARM_ARCH_5T__ -D__ARM_ARCH_5E__ -D__ARM_ARCH_5TE__  -Wno-psabi -march=armv7-a -mfloat-abi=softfp -mfpu=vfp -mthumb -Os -fomit-frame-pointer -fno-strict-aliasing -finline-limit=64 -DANDROID -Wa,--noexecstack"
            export CPPFLAGS=${CFLAGS}
            export CXXFLAGS="${CXXFLAGS} -I${stl} -I${stl_arch} ${CFLAGS}"
            export LDFLAGS="${sysroot} -Wl,--fix-cortex-a8  -Wl,--no-undefined -Wl,-z,noexecstack -L${stl_lib} -lgnustl_static -lsupc++"
            export CPP=${bin}/arm-linux-androideabi-cpp
            export CC=${bin}/arm-linux-androideabi-gcc
            export CXX=${bin}/arm-linux-androideabi-g++
            export AR=${bin}/arm-linux-androideabi-ar
            export AS=${bin}/arm-linux-androideabi-as
            export LD=${bin}/arm-linux-androideabi-ld
            export RANLIB=${bin}/arm-linux-androideabi-ranlib
            cmi_cross $1 arm-linux
            ;;

        darwin)
            # NOTE: Default libc++ changed from libstdc++ to libc++ on Maverick/iOS7.
            # Force libstdc++ for now
            export CPPFLAGS="-m32"
            export CXXFLAGS="${CXXFLAGS} -m32 -stdlib=libstdc++ "
            export CFLAGS="${CFLAGS} -m32"
            export LDFLAGS="-m32"
            cmi_buildplatform $1
            ;;

        x86_64-darwin)
            # NOTE: Default libc++ changed from libstdc++ to libc++ on Maverick/iOS7.
            # Force libstdc++ for now
            export CXXFLAGS="${CXXFLAGS} -stdlib=libstdc++"
            cmi_buildplatform $1
            ;;

        linux)
            cmi_buildplatform $1
            ;;

        x86_64-linux)
            cmi_buildplatform $1
            ;;

        win32)
            cmi_buildplatform $1
            ;;

        i586-mingw32msvc)
            export CPP=i586-mingw32msvc-cpp
            export CC=i586-mingw32msvc-gcc
            export CXX=i586-mingw32msvc-g++
            export AR=i586-mingw32msvc-ar
            export RANLIB=i586-mingw32msvc-ranlib
            cmi_cross $1 $1
            ;;

        js-web)
            export CONFIGURE_WRAPPER=emconfigure
            cmi_cross $1 $1
            ;;
        as3-web)
            export CPP="$FLASCC/usr/bin/cpp"
            export CC=$FLASCC/usr/bin/gcc
            export CXX=$FLASCC/usr/bin/g++
            export AR=$FLASCC/usr/bin/ar
            export RANLIB=$FLASCC/usr/bin/ranlib
            # NOTE: We use a fake platform in order to make configure-scripts happy
            cmi_cross $1 i386-freebsd
            ;;

        *)
            echo "Unknown target $1" && exit 1
            ;;
    esac
}
