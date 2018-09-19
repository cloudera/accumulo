#!/usr/bin/env bash
# Copyright (c) 2017 Cloudera, Inc. All rights reserved.
# This is the post build file. The file is dependent on cdh cauldron repo.

set -x
set -e

if [ -z "${GBN}" ]; then
    echo "GBN not defined"
    exit 1
fi
if [ -z "$VERSION" ]; then
   echo "Version number not defined"
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

PRODUCT_NAME=accumuloc6

# populate parcels and generate manifest.json
mkdir -p /accumulo/output-repo/parcels
cp -v /accumulo/build-parcel/ACCUMULO-*.parcel /accumulo/output-repo/parcels
$VIRTUAL_DIR/bin/parcelmanifest /accumulo/output-repo/parcels

# create build.json
user=jenkins
EXPIRE_DAYS=10
EXPIRATION=$(date --d "+${EXPIRE_DAYS} days" +%Y%m%d-%H%M%S)
if [ -z $RELEASE_CANDIDATE ]; then
	BUILD_JSON_EXIPIRATION="--expiry ${EXPIRATION}"
fi

cd /accumulo/output-repo/

$VIRTUAL_DIR/bin/buildjson \
	-o build.json -p ${PRODUCT_NAME} --version ${VERSION} --gbn $GBN \
	-os redhat6 -os redhat7 -os sles12 -os ubuntu1604 \
	--build-environment $BUILD_URL ${BUILD_JSON_EXIPIRATION} \
	--user ${user} \
        add_parcels --product-parcels ${PRODUCT_NAME} parcels
#        add_os_repo --product-base ${PRODUCT_NAME} . add_source

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
curl "http://builddb.infra.cloudera.com/addtag?gbn=${GBN}&value=${VERSION}"

echo "Tags added to the build are:"
curl "http://builddb.infra.cloudera.com/gettags?gbn=${GBN}"
echo ""
