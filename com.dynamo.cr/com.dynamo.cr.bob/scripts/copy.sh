# NOTE: This script is only used for CI
# The corresponding file for development is build.xml

set -e
mkdir -p lib/x86-linux
mkdir -p lib/x86-darwin
mkdir -p lib/x86_64-darwin
mkdir -p lib/x86-win32
mkdir -p libexec/x86-linux
mkdir -p libexec/x86-darwin
mkdir -p libexec/x86-win32
mkdir -p libexec/armv7-darwin
mkdir -p libexec/armv7-android
mkdir -p libexec/js-web

SHA1=`git log --pretty=%H -n1`

cp -v $DYNAMO_HOME/archive/${SHA1}/go/darwin/apkc libexec/x86-darwin/apkc
cp -v $DYNAMO_HOME/archive/${SHA1}/go/linux/apkc libexec/x86-linux/apkc
cp -v $DYNAMO_HOME/archive/${SHA1}/go/win32/apkc.exe libexec/x86-win32/apkc.exe

cp -v $DYNAMO_HOME/archive/${SHA1}/engine/share/builtins.zip lib/builtins.zip

jar cfM lib/android-res.zip -C $DYNAMO_HOME/ext/share/java/ res
cp -v $DYNAMO_HOME/archive/${SHA1}/engine/armv7-android/classes.dex lib/classes.dex
cp -v $DYNAMO_HOME/ext/share/java/android.jar lib/android.jar

cp -v $DYNAMO_HOME/archive/${SHA1}/engine/linux/libtexc_shared.so lib/x86-linux/libtexc_shared.so
cp -v $DYNAMO_HOME/archive/${SHA1}/engine/x86_64-darwin/libtexc_shared.dylib lib/x86_64-darwin/libtexc_shared.dylib
cp -v $DYNAMO_HOME/archive/${SHA1}/engine/win32/texc_shared.dll lib/x86-win32/texc_shared.dll

rm -rf tmp
mkdir -p tmp
tar xf ../../packages/luajit-2.0.3-win32.tar.gz -C tmp
tar xf ../../packages/luajit-2.0.3-linux.tar.gz -C tmp
tar xf ../../packages/luajit-2.0.3-darwin.tar.gz -C tmp

cp -v tmp/bin/linux/luajit libexec/x86-linux/luajit
cp -v tmp/bin/darwin/luajit libexec/x86-darwin/luajit
cp -v tmp/bin/win32/luajit.exe libexec/x86-win32/luajit.exe
jar cfM lib/luajit-share.zip -C $DYNAMO_HOME/ext/share/ luajit

copy () {
    cp -v $DYNAMO_HOME/archive/${SHA1}/engine/$1 libexec/$2
}

copy linux/dmengine x86-linux/dmengine
copy linux/dmengine_release x86-linux/dmengine_release
copy darwin/dmengine x86-darwin/dmengine
copy darwin/dmengine_release x86-darwin/dmengine_release
copy win32/dmengine.exe x86-win32/dmengine.exe
copy win32/dmengine_release.exe x86-win32/dmengine_release.exe
copy armv7-darwin/dmengine armv7-darwin/dmengine
copy armv7-darwin/dmengine_release armv7-darwin/dmengine_release
copy armv7-android/libdmengine.so armv7-android/libdmengine.so
copy armv7-android/libdmengine_release.so armv7-android/libdmengine_release.so
copy js-web/dmengine.js js-web/dmengine.js
#copy js-web/dmengine.js.mem js-web/dmengine.js.mem
copy js-web/dmengine_release.js js-web/dmengine_release.js
#copy js-web/dmengine_release.js.mem js-web/dmengine_release.js.mem

