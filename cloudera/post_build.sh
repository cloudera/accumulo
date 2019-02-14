#!/usr/bin/env bash
# Copyright (c) 2017 Cloudera, Inc. All rights reserved.
# This is the post build file. The file is dependent on cdh cauldron repo.

set -x
set -e

if [ -z "$GBN" ]; then
    echo "GBN not defined"
    exit 1
fi
if [ -z "$ACCUMULO_VERSION" ]; then
   echo "ACCUMULO_VERSION is not defined"
   exit 1
fi
if [ -z "$CDH_VERSION" ]; then
   echo "CDH_VERSION is not defined"
   exit 1
fi

# Ensure that the s3 creds are generated and available before the build starts.
if [ ! -f /tmp/s3-auth-file ]; then
   echo "S3 creds are not generated"
   exit 1
fi

rm -rf /accumulo/output-repo/

VAL=accumulo-$RANDOM
export VIRTUAL_DIR="/tmp/$VAL"

virtualenv $VIRTUAL_DIR
source $VIRTUAL_DIR/bin/activate
cd /cdh/lib/python/cauldron
$VIRTUAL_DIR/bin/pip install --upgrade pip==9.0.1
$VIRTUAL_DIR/bin/pip install --upgrade setuptools==33.1.1
$VIRTUAL_DIR/bin/pip install -r requirements.txt
$VIRTUAL_DIR/bin/python setup.py install

PRODUCT_NAME=accumulo_c6
COMPONENT_NAME=accumulo_c6

mkdir -p /accumulo/output-repo
cd /accumulo/output-repo

S3_ROOT=accumulo6/${ACCUMULO_VERSION}
S3_PARCELS=${S3_ROOT}/parcels
S3_CSD=${S3_ROOT}/csd
S3_MAVEN=${S3_ROOT}/maven-repository

# populate parcels and generate manifest.json
mkdir -p ${S3_PARCELS} ${S3_CSD} ${S3_MAVEN}
cp -v /accumulo/build-parcel/ACCUMULO-*.parcel ${S3_PARCELS}
$VIRTUAL_DIR/bin/parcelmanifest ${S3_PARCELS}

# copying maven artifacts
mkdir -p ${S3_MAVEN}/org/apache
cp -a /accumulo/assemble/target/accumulo-${ACCUMULO_VERSION}-*-repository/org/apache/accumulo ${S3_MAVEN}/org/apache

# getting csd
CM_GBN=$(curl -s 'http://builddb.infra.cloudera.com/query?product=cm&user=jenkins&version=6.x.0&tag=official')
curl -s "http://cloudera-build-us-west-1.vpc.cloudera.com/s3/build/${CM_GBN}/cm6/6.x.0/generic/maven/com/cloudera/csd/ACCUMULO_C6/6.x.0/ACCUMULO_C6-6.x.0.jar" -o ${S3_CSD}/ACCUMULO_C6-${CDH_VERSION}.jar

# create build.json
user=jenkins
EXPIRE_DAYS=10
EXPIRATION=$(date --d "+${EXPIRE_DAYS} days" +%Y%m%d-%H%M%S)
if [ -z $RELEASE_CANDIDATE ]; then
	BUILD_JSON_EXIPIRATION="--expiry ${EXPIRATION}"
fi

$VIRTUAL_DIR/bin/buildjson \
	-o build.json -p ${PRODUCT_NAME} --version ${ACCUMULO_VERSION} \
	--gbn $GBN -os redhat6 -os redhat7 -os sles12 -os ubuntu1604 \
	--build-environment $BUILD_URL ${BUILD_JSON_EXIPIRATION} \
	--user ${user} \
        add_parcels --product-parcels ${COMPONENT_NAME} ${S3_PARCELS} \
	add_csd --files ${COMPONENT_NAME} ${S3_CSD}/*.jar \
	add_maven --product-base ${COMPONENT_NAME} ${S3_MAVEN}

# upload it to s3
$VIRTUAL_DIR/bin/upload s3 \
	--auth-file /tmp/s3-auth-file --create-html-listing $GBN \
	--base build/${GBN} /accumulo/output-repo/:.
curl http://builddb.infra.cloudera.com/save?gbn=$GBN

# add tags for gbn
curl "http://builddb.infra.cloudera.com/addtag?gbn=${GBN}&value=FULL_BUILD"
echo "Marking build as official? ${OFFICIAL}"
if [ "${OFFICIAL}" == "true" ]; then
	curl "http://builddb.infra.cloudera.com/addtag?gbn=${GBN}&value=official"
fi
if [[ -n "${RELEASE_CANDIDATE}" ]]; then
	echo "Marking this as Release Candidate #${RELEASE_CANDIDATE}"
	curl "http://builddb.infra.cloudera.com/addtag?gbn=${GBN}&value=release_candidate"
	curl "http://builddb.infra.cloudera.com/addtag?gbn=${GBN}&value=rc-${RELEASE_CANDIDATE}"
fi
curl "http://builddb.infra.cloudera.com/addtag?gbn=${GBN}&value=${ACCUMULO_VERSION}"

echo "Tags added to the build are:"
curl "http://builddb.infra.cloudera.com/gettags?gbn=${GBN}"
echo ""
