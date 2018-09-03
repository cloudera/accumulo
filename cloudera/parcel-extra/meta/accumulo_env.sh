#!/bin/bash

#The following is written to aid local testing
if [ -z $PARCELS_ROOT ] ; then
	export MYDIR=`dirname "${BASH_SOURCE[0]}"`
	PARCELS_ROOT=`cd $MYDIR/../.. && pwd`
fi
ACCUMULO_DIRNAME=${PARCEL_DIRNAME-ACCUMULO}
export CDH_ACCUMULO_HOME=$PARCELS_ROOT/$ACCUMULO_DIRNAME/lib/accumulo

