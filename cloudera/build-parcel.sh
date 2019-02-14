#!/usr/bin/env bash

# This script creates a parcel for a specified distribution. It does not
# compile any java code, it just translates the output of assemble project
# into parcel format, as well as generating the necessary files. It compiles
# the native bindings (libaccumulo.so).

# Environment variables:
# ACCUMULO_VERSION= version of accumulo (e.g. 1.9.2)
# CDH_VERSION= version of cdh (e.g. 6.0.0)
# GBN= the build number (e.g. 123456)
# DISTRO= the distribution name, optional (e.g. el7)

set -e
set -x

if [ -z "$ACCUMULO_VERSION" ]; then
	echo "ACCUMULO_VERSION is not defined."
	exit 1
fi
if [ -z "$CDH_VERSION" ]; then
	echo "CDH_VERSION is not defined."
	exit 1
fi
if [ -z "$GBN" ]; then
	echo "GBN is not defined."
	exit 1
fi

VERSION_FULL="${ACCUMULO_VERSION}-1.ACCUMULO${CDH_VERSION}.p0.${GBN}"

if [ -z "$DISTRO" ]; then
	VERSION_DISTRO="$VERSION_FULL"
	PARCEL_FILE="ACCUMULO-${VERSION_FULL}.parcel"
else
	VERSION_DISTRO="$VERSION_FULL.$DISTRO"
	PARCEL_FILE="ACCUMULO-${VERSION_FULL}-$DISTRO.parcel"
fi

# too few versions? let's set some, too
VERSION_PKG="${ACCUMULO_VERSION}+accumulocdh${CDH_VERSION}+0"
VERSION_COMPONENT="${ACCUMULO_VERSION}-accumulocdh${CDH_VERSION}"
VERSION_RELEASE="1.accumulocdh${CDH_VERSION}.p0.${GBN}"

ROOT=$(pwd)

OUTPUT_DIR="$ROOT/build-parcel"

PARCEL_DIRNAME="ACCUMULO-$VERSION_FULL"
PARCEL_DIR="$OUTPUT_DIR/$PARCEL_DIRNAME"
PARCEL_TMP="$OUTPUT_DIR/tmp"
PARCEL_NATIVE="$OUTPUT_DIR/native"

rm -rf "$PARCEL_DIR" "$PARCEL_TMP" "$PARCEL_NATIVE"

# unpacking the maven generated assemble tar
mkdir -p "$PARCEL_TMP"
tar xzf "$ROOT"/assemble/target/accumulo-${ACCUMULO_VERSION}-*-bin.tar.gz \
	-C "$PARCEL_TMP" --strip-components=1

# recompiling native libraries for the current architecture
mkdir -p "$PARCEL_NATIVE"
tar xzf "$ROOT"/server/native/target/accumulo-native-${ACCUMULO_VERSION}-*.tar.gz \
	-C "$PARCEL_NATIVE" --strip-components=1
(cd "$PARCEL_NATIVE" && make)

# initializing parcel content with cloudera/parcel-extra
cp -a cloudera/parcel-extra "$PARCEL_DIR"
find "$PARCEL_DIR" -name .gitignore -exec rm -f {} \;

# translating accumulo's assemble tar contents into parcel layout
mkdir -p "$PARCEL_DIR/lib/accumulo"
cp -a \
	"$PARCEL_TMP/bin" \
	"$PARCEL_TMP/conf" \
	"$PARCEL_TMP/lib" \
	"$PARCEL_TMP/proxy" \
	"$PARCEL_DIR/lib/accumulo"

# copying native libraries into the right place
mkdir -p "$PARCEL_DIR/lib/accumulo/lib/native"
cp "$PARCEL_NATIVE"/libaccumulo.* "$PARCEL_DIR/lib/accumulo/lib/native"

# copying cdh_version.properties
mkdir -p "$PARCEL_DIR/lib/accumulo/cloudera"
cp "$ROOT/cloudera/cdh_version.properties" "$PARCEL_DIR/lib/accumulo/cloudera"

# copying docs to the right place
mkdir -p "$PARCEL_DIR/share/doc"
cp -a "$PARCEL_TMP/docs/" \
	"$PARCEL_DIR/share/doc/accumulo-$VERSION_PKG"

# generating the contents of meta
mkdir -p "$PARCEL_DIR/meta"

# creating parcel.json
cat > "$PARCEL_DIR/meta/parcel.json" << EOT
{
  "schema_version": 1,
  "name": "ACCUMULO",
  "version": "$VERSION_FULL",
  "extraVersionInfo": {
    "fullVersion": "$VERSION_DISTRO",
    "baseVersion": "$ACCUMULO_VERSION",
    "patchCount": "0"
  },
  "depends": "CDH (>= 6.0), CDH (<< 7.0)",
  "setActiveSymlink": true,
  "provides": [
    "accumulo"
  ],
  "components": [
    {
      "name": "accumulo",
      "version": "$VERSION_COMPONENT",
      "pkg_version": "$VERSION_PKG",
      "pkg_release": "$VERSION_RELEASE"
    }
  ],
  "packages": [
    {
      "name": "accumulo",
      "version": "$VERSION_PKG"
    },
    {
      "name": "accumulo-master",
      "version": "$VERSION_PKG"
    },
    {
      "name": "accumulo-tserver",
      "version": "$VERSION_PKG"
    },
    {
      "name": "accumulo-gc",
      "version": "$VERSION_PKG"
    },
    {
      "name": "accumulo-monitor",
      "version": "$VERSION_PKG"
    },
    {
      "name": "accumulo-tracer",
      "version": "$VERSION_PKG"
    },
    {
      "name": "accumulo-doc",
      "version": "$VERSION_PKG"
    }
  ],
  "users": {
    "accumulo": {
      "home": "/var/lib/accumulo",
      "shell": "/sbin/nologin",
      "extra_groups": [],
      "longname": "Accumulo"
    }
  },
  "groups": [
  ],
  "scripts": {
    "defines": "accumulo_env.sh"
  }
}
EOT

# creating parcel tarball
(
	cd "$OUTPUT_DIR"
	tar czf "$OUTPUT_DIR/$PARCEL_FILE" "$PARCEL_DIRNAME"
)

