#!/bin/bash

BIDMACH_SCRIPTS="${BASH_SOURCE[0]}"
if [ ! `uname` = "Darwin" ]; then
  BIDMACH_SCRIPTS=`readlink -f "${BIDMACH_SCRIPTS}"`
else 
  while [ -L "${BIDMACH_SCRIPTS}" ]; do
    BIDMACH_SCRIPTS=`readlink "${BIDMACH_SCRIPTS}"`
  done
  alias wget='curl --retry 2 -O'
fi
export BIDMACH_SCRIPTS=`dirname "$BIDMACH_SCRIPTS"`
cd ${BIDMACH_SCRIPTS}
BIDMACH_SCRIPTS=`pwd`
BIDMACH_SCRIPTS="$( echo ${BIDMACH_SCRIPTS} | sed 's+/cygdrive/\([a-z]\)+\1:+' )" 


export BIDMACH_SCRIPTS=`dirname "$BIDMACH_SCRIPTS"`

${BIDMACH_SCRIPTS}/getrcv1.sh

${BIDMACH_SCRIPTS}/getuci.sh nips

${BIDMACH_SCRIPTS}/getuci.sh nytimes

# ${BIDMACH_SCRIPTS}/getuci.sh pubmed

${BIDMACH_SCRIPTS}/getdigits.sh



