#!/usr/bin/env bash

# How to create a jar file
# https://introcs.cs.princeton.edu/java/85application/jar/jar.html

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

set -e

CLASS_NAME=ModelImporter

DEFOLD_DIR=$DYNAMO_HOME/../..

JNA=$DEFOLD_DIR/com.dynamo.cr/com.dynamo.cr.bob/lib/jna-5.10.0.jar
JNA_PLATFORM=$DEFOLD_DIR/com.dynamo.cr/com.dynamo.cr.bob/lib/jna-platform-5.10.0.jar

PACKAGE_PATH=com/dynamo/bob/pipeline
BOB_SRC_PATH=$DEFOLD_DIR/com.dynamo.cr/com.dynamo.cr.bob/src
DLIB_BUILD_DIR=$DEFOLD_DIR/engine/dlib/build/default/src/

MANIFEST=$SCRIPT_DIR/$CLASS_NAME.manifest
PACKAGE_CLASS=com.dynamo.bob.pipeline.$CLASS_NAME
JAR=$SCRIPT_DIR/$CLASS_NAME.jar

echo "Compiling class:" $PACKAGE_CLASS
javac -cp $JNA:$JNA_PLATFORM $SCRIPT_DIR/$CLASS_NAME.java

echo "Manifest-Version: 1.0" > $MANIFEST
echo "Main-Class: $PACKAGE_CLASS" >> $MANIFEST
echo "Class-Path: $JNA $JNA_PLATFORM" >> $MANIFEST

echo "Creating jar:" $JAR

pushd $BOB_SRC_PATH

jar cmf $MANIFEST $JAR $PACKAGE_PATH/$CLASS_NAME*.class

popd

echo "Cleanup"

rm $SCRIPT_DIR/$CLASS_NAME*.class
rm $MANIFEST

echo "Running jar:" $JAR

java -Djava.library.path=$DLIB_BUILD_DIR -Djna.library.path=$DLIB_BUILD_DIR -jar $JAR $*
